package com.lqbz.ops.inspection.probes;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.http.auth.*;
import org.apache.http.client.config.*;
import org.apache.http.client.methods.*;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicSchemeFactory;
import org.apache.http.impl.auth.NTLMSchemeFactory;
import org.apache.http.impl.client.*;
import org.w3c.dom.*;

/** 标准 WSMan 远程 shell；只传输固定只读命令，不接受用户脚本。 */
final class WinrmTransport implements Closeable {
  static final int MAX_OUTPUT=1048576;
  private static final String SHELL="http://schemas.microsoft.com/wbem/wsman/1/windows/shell";
  private static final ScheduledExecutorService WATCHDOG=Executors.newSingleThreadScheduledExecutor(r -> {
    Thread thread=new Thread(r,"winrm-request-watchdog"); thread.setDaemon(true); return thread;
  });
  private final URI endpoint;
  private final CloseableHttpClient client;
  private final HttpClientContext httpContext=HttpClientContext.create();
  private final String authName;
  private final long deadline;
  private final long workDeadline;
  private final Thread owner=Thread.currentThread();
  private volatile HttpPost active;
  private volatile long requestDeadline;
  private final ScheduledFuture<?> watchdog;
  private String shellId;

  WinrmTransport(URI endpoint,String username,String password,String domain,String auth,int timeout,SSLContext context) {
    this.endpoint=endpoint; this.authName=auth; deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
    workDeadline=deadline-TimeUnit.MILLISECONDS.toNanos(Math.min(500,timeout/4));
    BasicCredentialsProvider credentials=new BasicCredentialsProvider();
    credentials.setCredentials(new AuthScope(endpoint.getHost(),endpoint.getPort()),"NTLM".equals(auth)
        ?new NTCredentials(username,password,"",domain):new UsernamePasswordCredentials(username,password));
    HttpClientBuilder builder=HttpClients.custom().setDefaultCredentialsProvider(credentials)
        .setDefaultAuthSchemeRegistry(RegistryBuilder.<AuthSchemeProvider>create()
            .register("Negotiate",ignored -> new NegotiateNtlmScheme())
            .register("NTLM",new NTLMSchemeFactory()).register("Basic",new BasicSchemeFactory()).build())
        .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement();
    // 保留 HttpClient 的标准证书链及主机名校验，禁止信任全部证书。
    if(context!=null) builder.setSSLContext(context);
    builder.setDefaultRequestConfig(clientConfig());
    client=builder.build();
    watchdog=WATCHDOG.scheduleAtFixedRate(() -> {
      HttpPost request=active;
      if(request!=null && (owner.isInterrupted() || System.nanoTime()>=requestDeadline)) request.abort();
    },10,10,TimeUnit.MILLISECONDS);
  }

  String readDisks() throws Exception {
    Document created=request("http://schemas.xmlsoap.org/ws/2004/09/transfer/Create",
        "<rsp:Shell><rsp:InputStreams>stdin</rsp:InputStreams><rsp:OutputStreams>stdout stderr</rsp:OutputStreams></rsp:Shell>",false);
    shellId=required(created,"ShellId");
    String encoded=Base64.getEncoder().encodeToString(WindowsProtocolProbe.DISK_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
    Document command=request(SHELL+"/Command","<rsp:CommandLine><rsp:Command>powershell.exe</rsp:Command><rsp:Arguments>-NoLogo -NoProfile -NonInteractive -EncodedCommand "+encoded+"</rsp:Arguments></rsp:CommandLine>",false);
    String commandId=required(command,"CommandId"); ByteArrayOutputStream output=new ByteArrayOutputStream();
    while(true) {
      Document received=request(SHELL+"/Receive","<rsp:Receive><rsp:DesiredStream CommandId=\""+xml(commandId)+"\">stdout stderr</rsp:DesiredStream></rsp:Receive>",false);
      NodeList streams=received.getElementsByTagNameNS(SHELL,"Stream");
      for(int i=0;i<streams.getLength();i++) {
        Element stream=(Element)streams.item(i);
        if(!commandId.equals(stream.getAttribute("CommandId"))) throw new IOException("命令标识不匹配");
        byte[] bytes=Base64.getMimeDecoder().decode(stream.getTextContent());
        if(output.size()+bytes.length>MAX_OUTPUT) throw new IOException("输出超出限制");
        if("stdout".equals(stream.getAttribute("Name"))) output.write(bytes);
        else if(bytes.length>0) throw new IOException("远端命令错误");
      }
      NodeList states=received.getElementsByTagNameNS(SHELL,"CommandState");
      for(int i=0;i<states.getLength();i++) {
        Element state=(Element)states.item(i);
        if(!commandId.equals(state.getAttribute("CommandId"))) throw new IOException("命令标识不匹配");
        if((SHELL+"/CommandState/Done").equals(state.getAttribute("State"))) {
          if(!"0".equals(required(received,"ExitCode"))) throw new IOException("远端命令失败");
          return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }
      }
    }
  }

  private Document request(String action,String body,boolean cleanup) throws Exception {
    long limit=cleanup?deadline:workDeadline;
    if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
    int remaining=(int)Math.min(Integer.MAX_VALUE,TimeUnit.NANOSECONDS.toMillis(limit-System.nanoTime()));
    if(remaining<1) throw new IOException("WinRM 超时");
    HttpPost post=new HttpPost(endpoint); requestDeadline=limit; active=post;
    post.setConfig(RequestConfig.copy(clientConfig()).setConnectTimeout(remaining).setSocketTimeout(remaining).setConnectionRequestTimeout(remaining).build());
    String envelope="<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:w=\"http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd\" xmlns:rsp=\""+SHELL+"\"><s:Header>"
        +"<a:To s:mustUnderstand=\"true\">"+xml(endpoint.toString())+"</a:To><w:ResourceURI s:mustUnderstand=\"true\">"+SHELL+"/cmd</w:ResourceURI>"
        +"<a:ReplyTo><a:Address s:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address></a:ReplyTo>"
        +"<a:Action s:mustUnderstand=\"true\">"+action+"</a:Action><a:MessageID>uuid:"+UUID.randomUUID()+"</a:MessageID>"
        +"<w:MaxEnvelopeSize s:mustUnderstand=\"true\">153600</w:MaxEnvelopeSize><w:OperationTimeout>PT"+Math.max(1,remaining/1000)+"S</w:OperationTimeout>"
        +(shellId==null?"<w:OptionSet><w:Option Name=\"WINRS_NOPROFILE\">TRUE</w:Option></w:OptionSet>":"<w:SelectorSet><w:Selector Name=\"ShellId\">"+xml(shellId)+"</w:Selector></w:SelectorSet>")
        +"</s:Header><s:Body>"+body+"</s:Body></s:Envelope>";
    post.setEntity(new StringEntity(envelope,StandardCharsets.UTF_8)); post.setHeader("Content-Type","application/soap+xml;charset=UTF-8");
    try(CloseableHttpResponse response=client.execute(post,httpContext)) {
      int status=response.getStatusLine().getStatusCode();
      if(status!=200) throw new IOException("WinRM 请求失败");
      if(response.getEntity()==null) throw new IOException("WinRM 响应为空");
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();
      try(InputStream input=response.getEntity().getContent()) {
        byte[] buffer=new byte[8192]; int count;
        while((count=input.read(buffer))!=-1) {
          if(bytes.size()+count>2*MAX_OUTPUT) throw new IOException("响应超出限制");
          bytes.write(buffer,0,count);
        }
      }
      DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
      Document document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes.toByteArray()));
      if(document.getElementsByTagNameNS("http://www.w3.org/2003/05/soap-envelope","Fault").getLength()>0) throw new IOException("WinRM SOAP 错误");
      return document;
    } finally { active=null; post.releaseConnection(); }
  }
  private RequestConfig clientConfig() {
    List<String> schemes="NTLM".equals(authName)?Arrays.asList("Negotiate","NTLM"):Collections.singletonList("Basic");
    return RequestConfig.custom().setTargetPreferredAuthSchemes(schemes).build();
  }
  private static String required(Document document,String name) throws IOException {
    NodeList nodes=document.getElementsByTagNameNS(SHELL,name);
    if(nodes.getLength()!=1 || nodes.item(0).getTextContent().trim().isEmpty()) throw new IOException("WinRM 响应字段缺失");
    return nodes.item(0).getTextContent().trim();
  }
  private static String xml(String value) { return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;"); }
  public void close() throws IOException {
    // 保留取消信号，并在预留的总预算内尽力释放远端 shell。
    boolean interrupted=Thread.interrupted();
    try {
      if(shellId!=null) try { request("http://schemas.xmlsoap.org/ws/2004/09/transfer/Delete","",true); } catch(Exception ignored) { }
    } finally {
      watchdog.cancel(false); HttpPost request=active; if(request!=null) request.abort(); client.close();
      if(interrupted) Thread.currentThread().interrupt();
    }
  }
}
