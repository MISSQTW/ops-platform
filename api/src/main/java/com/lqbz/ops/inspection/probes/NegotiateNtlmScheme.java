package com.lqbz.ops.inspection.probes;

import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.auth.ContextAwareAuthScheme;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.MalformedChallengeException;
import org.apache.http.impl.auth.NTLMScheme;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HttpContext;

/** 适配 WinRM 的 Negotiate 头承载 NTLM，握手与密码响应均由 Apache 实现。 */
final class NegotiateNtlmScheme implements ContextAwareAuthScheme {
  private final NTLMScheme delegate=new NTLMScheme();

  public String getSchemeName() { return "Negotiate"; }
  public String getParameter(String name) { return delegate.getParameter(name); }
  public String getRealm() { return delegate.getRealm(); }
  public boolean isConnectionBased() { return delegate.isConnectionBased(); }
  public boolean isComplete() { return delegate.isComplete(); }

  public void processChallenge(Header header) throws MalformedChallengeException {
    String value=header.getValue();
    if(value==null) throw new MalformedChallengeException("Negotiate 认证挑战为空");
    String[] parts=value.trim().split("\\s+",2);
    if(!"Negotiate".equalsIgnoreCase(parts[0])) throw new MalformedChallengeException("不支持的认证挑战");
    // 只适配认证头名称，不自行实现或修改 NTLM Type1/2/3 消息。
    delegate.processChallenge(new BasicHeader(header.getName(),"NTLM"+(parts.length==2?" "+parts[1]:"")));
  }

  public Header authenticate(Credentials credentials,HttpRequest request) throws AuthenticationException {
    Header header=delegate.authenticate(credentials,request);
    String value=header.getValue();
    if(!value.startsWith("NTLM ")) throw new AuthenticationException("NTLM 认证响应格式无效");
    return new BasicHeader(header.getName(),"Negotiate "+value.substring(5));
  }

  public Header authenticate(Credentials credentials,HttpRequest request,HttpContext context) throws AuthenticationException {
    return authenticate(credentials,request);
  }
}
