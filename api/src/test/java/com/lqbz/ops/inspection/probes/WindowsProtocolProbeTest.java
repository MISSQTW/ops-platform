package com.lqbz.ops.inspection.probes;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.inspection.ProbeCheck;
import com.lqbz.ops.inspection.ProbeSettings;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowsProtocolProbeTest {

    private HttpsServer server;

    private SSLContext context;

    private final List<String> actions = Collections.synchronizedList(new ArrayList<String>());
    private final CountDownLatch receiving=new CountDownLatch(1);
    private ExecutorService executor;

    private String output = "C:|1000|600\nD:|2000|400\n";

    private boolean deny;

    private boolean commandFault;

    private int receives;
    private long receiveDelay;
    private boolean ntlm;
    private boolean negotiateOnly;
    private int ntlmProofs;

    @BeforeEach
    void start() throws Exception {
        // 测试证书只用于本地协议夹具；客户端仍验证证书链与 localhost 主机名。
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = getClass().getResourceAsStream("/winrm/localhost.p12")) {
            keys.load(input, "fixture-password".toCharArray());
        }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, "fixture-password".toCharArray());
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        context = SSLContext.getInstance("TLS");
        context.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/wsman", exchange -> {
            String body = read(exchange.getRequestBody());
            String action;
            if (body.contains("/Create</a:Action>"))
                action = "Create";
            else if (body.contains("/Command</a:Action>"))
                action = "Command";
            else if (body.contains("/Receive</a:Action>"))
                action = "Receive";
            else
                action = "Delete";
            actions.add(action);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if(ntlm && !deny) {
                String scheme=negotiateOnly?"Negotiate":"NTLM";
                byte[] token=auth!=null && auth.startsWith(scheme+" ")?Base64.getDecoder().decode(auth.substring(scheme.length()+1)):new byte[0];
                if(token.length<12 || token[8]!=3) {
                    String challenge=scheme;
                    if(token.length>=12 && token[8]==1) {
                        byte[] message=new byte[32]; byte[] signature="NTLMSSP\0".getBytes(StandardCharsets.US_ASCII); System.arraycopy(signature,0,message,0,signature.length);
                        message[8]=2; message[16]=32; message[20]=1; message[21]=2;
                        for(int i=24;i<32;i++) message[i]=(byte)i;
                        challenge+=" "+Base64.getEncoder().encodeToString(message);
                    }
                    exchange.getResponseHeaders().set("WWW-Authenticate",challenge); exchange.sendResponseHeaders(401,-1); exchange.close(); return;
                }
                ntlmProofs++;
            } else if (deny || !("Basic " + Base64.getEncoder().encodeToString("inspection:sensitive-password".getBytes(StandardCharsets.UTF_8))).equals(auth)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"WinRM\"");
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            String value;
            if ("Create".equals(action))
                value = "<rsp:Shell><rsp:ShellId>shell-1</rsp:ShellId></rsp:Shell>";
            else if ("Command".equals(action)) {
                assertThat(body).contains("powershell.exe", "-EncodedCommand").doesNotContain("C:|", "sensitive-password");
                value = commandFault ? "<s:Fault><s:Reason>sensitive-password</s:Reason></s:Fault>" : "<rsp:CommandResponse><rsp:CommandId>command-1</rsp:CommandId></rsp:CommandResponse>";
            } else if ("Receive".equals(action)) {
                receives++;
                receiving.countDown();
                if(receiveDelay>0) try { Thread.sleep(receiveDelay); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                value = "<rsp:ReceiveResponse><rsp:Stream Name=\"stdout\" CommandId=\"command-1\">" + Base64.getEncoder().encodeToString(output.getBytes(StandardCharsets.UTF_8)) + "</rsp:Stream><rsp:CommandState CommandId=\"command-1\" State=\"http://schemas.microsoft.com/wbem/wsman/1/windows/shell/CommandState/Done\"><rsp:ExitCode>0</rsp:ExitCode></rsp:CommandState></rsp:ReceiveResponse>";
            } else
                value = "";
            byte[] response = ("<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:rsp=\"http://schemas.microsoft.com/wbem/wsman/1/windows/shell\"><s:Body>" + value + "</s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/soap+xml;charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(response);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void httpsAuthenticatedShellLifecycleAndThreshold() {
        List<ProbeCheck> checks = probe(Collections.<String, String>emptyMap());
        assertThat(checks).hasSize(3);
        assertThat(checks).anyMatch(c -> "WINRM".equals(c.category()) && c.passed());
        assertThat(checks).anyMatch(c -> "C:".equals(c.target()) && c.passed());
        assertThat(checks).anyMatch(c -> "D:".equals(c.target()) && !c.passed() && c.message().contains("80%"));
        assertThat(actions).containsSubsequence("Create", "Command", "Receive", "Delete");
    }

    @Test
    void exclusionsAndMissingDriveAreReported() {
        Map<String, String> options = new HashMap<>();
        options.put("driveLetters", "c:,E:");
        options.put("excludedDriveLetters", "D:");
        assertThat(probe(options)).anyMatch(c -> "E:".equals(c.target()) && !c.passed());
    }

    @Test
    void ntlmChallengeResponseCompletesShell() {
        ntlm=true; Map<String,String> options=new HashMap<>(); options.put("winrmAuth","NTLM"); options.put("domain","TEST");
        assertThat(probe(options)).anyMatch(c -> "WINRM".equals(c.category()) && c.passed());
        assertThat(ntlmProofs).isPositive(); assertThat(actions).containsSubsequence("Create","Command","Receive","Delete");
    }

    @Test
    void negotiateOnlyWinrmCompletesNtlmChallengeAndShell() {
        ntlm=true; negotiateOnly=true;
        Map<String,String> options=new HashMap<>(); options.put("winrmAuth","NTLM"); options.put("domain","TEST");
        List<ProbeCheck> checks=probe(options);
        assertThat(checks).anyMatch(c -> "WINRM".equals(c.category()) && c.passed());
        assertThat(ntlmProofs).isPositive();
        assertThat(actions).containsSubsequence("Create","Command","Receive","Delete");
    }

    @Test
    void authenticationFailureNeverExecutesCommandOrLeaksResponse() {
        deny = true;
        List<ProbeCheck> checks = probe(Collections.<String, String>emptyMap());
        assertThat(checks).allMatch(c -> !c.passed());
        assertThat(actions).doesNotContain("Command");
        assertThat(checks.get(0).message()).doesNotContain("sensitive-password");
    }

    @Test
    void soapFaultStillDeletesCreatedShell() {
        commandFault = true;
        assertThat(probe(Collections.<String, String>emptyMap())).allMatch(c -> !c.passed());
        assertThat(actions).containsSubsequence("Create", "Command", "Delete");
    }

    @Test
    void untrustedCertificateFailsBeforeCredentialsOrCommands() {
        assertThat(new WindowsProtocolProbe().check(asset(), settings(Collections.<String, String>emptyMap()))).allMatch(c -> !c.passed());
        assertThat(actions).isEmpty();
    }

    @Test
    void trustedCertificateForDifferentHostnameIsRejected() {
        Asset wrongName=new Asset("Windows夹具","项目","环境","SERVER","https://127.0.0.1:"+server.getAddress().getPort()+"/wsman","UNKNOWN");
        assertThat(new WindowsProtocolProbe(context).check(wrongName,settings(Collections.<String,String>emptyMap()))).allMatch(c -> !c.passed());
        assertThat(actions).isEmpty();
    }

    @Test
    void saveValidationSharesEndpointCredentialsAndDriveRules() {
        Map<String,String> options=new HashMap<>(); options.put("username","DOMAIN\\inspection"); options.put("winrmAuth","NTLM");
        WindowsProtocolProbe.validateSettings("windows.example.com",new ProbeSettings(options,Collections.<String,String>emptyMap()),false);
        assertThatThrownBy(() -> WindowsProtocolProbe.validateSettings("windows.example.com",new ProbeSettings(options,Collections.<String,String>emptyMap()),true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WindowsProtocolProbe.validateSettings("https://windows.example.com:65536",settings(options),true)).isInstanceOf(IllegalArgumentException.class);
        options.put("username","DOMAIN\\");
        assertThatThrownBy(() -> WindowsProtocolProbe.validateSettings("windows.example.com",settings(options),true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thresholdUsesExactBytesInsteadOfRoundedDisplay() {
        ProbeSettings settings=settings(Collections.<String,String>emptyMap());
        assertThat(WindowsProtocolProbe.diskChecks("C:|100000|20001\nD:|100000|20000\n",settings,0)).anyMatch(c -> "C:".equals(c.target()) && c.passed()).anyMatch(c -> "D:".equals(c.target()) && !c.passed());
    }

    @Test
    void malformedAndOversizedDiskOutputFailsAndDeletesShell() {
        output = "C:|0|0\n";
        assertThat(probe(Collections.<String, String>emptyMap())).anyMatch(c -> "DISK".equals(c.category()) && !c.passed());
        char[] huge = new char[1100000];
        Arrays.fill(huge, 'X');
        output = new String(huge);
        assertThat(probe(Collections.<String, String>emptyMap())).allMatch(c -> !c.passed());
        assertThat(actions.get(actions.size() - 1)).isEqualTo("Delete");
    }

    @Test
    void totalBudgetStopsBlockedReceive() {
        receiveDelay=2000; Map<String,String> options=new HashMap<>(); options.put("timeoutMillis","1000");
        long start=System.nanoTime(); assertThat(probe(options)).allMatch(c -> !c.passed());
        assertThat((System.nanoTime()-start)/1000000).isLessThan(1600L);
    }

    @Test
    void interruptionAbortsReceiveAndPreservesCancellationSignal() throws Exception {
        receiveDelay=5000; AtomicBoolean cancelled=new AtomicBoolean();
        Thread worker=new Thread(() -> {
            List<ProbeCheck> checks=probe(Collections.<String,String>emptyMap());
            cancelled.set(Thread.currentThread().isInterrupted() && !checks.get(0).passed());
        });
        worker.start(); assertThat(receiving.await(2,TimeUnit.SECONDS)).isTrue(); worker.interrupt(); worker.join(1500);
        assertThat(worker.isAlive()).isFalse(); assertThat(cancelled.get()).isTrue();
        assertThat(actions).contains("Delete");
    }

    @Test
    void insecureEndpointAndInvalidDriveNeverSendCredentials() {
        Asset insecure=new Asset("Windows夹具","项目","环境","SERVER","http://localhost:"+server.getAddress().getPort()+"/wsman","UNKNOWN");
        assertThat(new WindowsProtocolProbe(context).check(insecure,settings(Collections.<String,String>emptyMap()))).allMatch(c -> !c.passed());
        Map<String,String> options=new HashMap<>(); options.put("driveLetters","C:; Remove-Item *");
        assertThat(probe(options)).allMatch(c -> !c.passed()); assertThat(actions).isEmpty();
    }

    private List<ProbeCheck> probe(Map<String, String> options) {
        return new WindowsProtocolProbe(context).check(asset(), settings(options));
    }

    private Asset asset() {
        return new Asset("Windows夹具", "项目", "环境", "SERVER", "https://localhost:" + server.getAddress().getPort() + "/wsman", "UNKNOWN");
    }

    private ProbeSettings settings(Map<String, String> extra) {
        Map<String, String> options = new HashMap<>();
        options.put("username", "inspection");
        options.put("winrmAuth", "BASIC");
        options.put("timeoutMillis", "3000");
        options.putAll(extra);
        return new ProbeSettings(options, Collections.singletonMap("password", "sensitive-password"));
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int count;
        while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
