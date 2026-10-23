package com.lqbz.ops.inspection.probes;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.inspection.ProbeCheck;
import com.lqbz.ops.inspection.ProbeSettings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(15)
class SystemProtocolProbeSshTest {

    private static final String USERNAME = "inspection";

    private static final String PASSWORD = "fixture-password-sensitive";

    private static final String DF = "Filesystem 1024-blocks Used Available Capacity Mounted on\n" + "/dev/root 1000 400 600 40% /\n/dev/data 2000 1600 400 80% /data\n";

    private final Queue<String> commands = new ConcurrentLinkedQueue<>();

    private final AtomicInteger passwordAttempts = new AtomicInteger();

    private final AtomicInteger publicKeyAttempts = new AtomicInteger();

    private SshServer server;

    private KeyPair hostKey;

    private KeyPair userKey;

    private String fingerprint;

    @BeforeEach
    void startRealSshServer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        hostKey = generator.generateKeyPair();
        userKey = generator.generateKeyPair();
        fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, hostKey.getPublic());
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        server.setPasswordAuthenticator((username, password, session) -> {
            passwordAttempts.incrementAndGet();
            return USERNAME.equals(username) && PASSWORD.equals(password);
        });
        server.setPublickeyAuthenticator((username, key, session) -> {
            publicKeyAttempts.incrementAndGet();
            return USERNAME.equals(username) && userKey.getPublic().equals(key);
        });
        server.setCommandFactory((channel, command) -> {
            commands.add(command);
            return new DfCommand(command);
        });
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        if (server != null)
            server.stop(true);
    }

    @Test
    void correctPasswordPerformsRealSshHandshakeAndReportsEachMount() {
        List<ProbeCheck> result = probe(com.lqbz.ops.compat.Java8.map("password", PASSWORD), com.lqbz.ops.compat.Java8.map("mountPoints", "/,/data"), fingerprint);
        assertThat(result).hasSize(3);
        assertThat(result).anyMatch(c -> "SSH".equals(c.category()) && c.passed());
        assertThat(result).anyMatch(c -> "/".equals(c.target()) && c.passed() && c.message().contains("使用率 40%"));
        assertThat(result).anyMatch(c -> "/data".equals(c.target()) && !c.passed() && c.message().contains("使用率 80%") && c.message().contains("阈值 80%"));
        assertThat(commands).containsExactly("LC_ALL=C df -Pk");
        assertThat(passwordAttempts.get()).isPositive();
        assertThat(result.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void passwordAuthenticationAndMountExclusionCanPassEveryCheck() {
        List<ProbeCheck> result = probe(com.lqbz.ops.compat.Java8.map("password", PASSWORD), com.lqbz.ops.compat.Java8.map("excludedMountPoints", "/data"), fingerprint);
        assertThat(result).hasSize(2).allMatch(ProbeCheck::passed);
        assertThat(result).noneMatch(c -> "/data".equals(c.target()));
        assertThat(commands).containsExactly("LC_ALL=C df -Pk");
    }

    @Test
    void wrongPasswordFailsBeforeExecutingDiskCommand() {
        List<ProbeCheck> result = probe(com.lqbz.ops.compat.Java8.map("password", "fixture-wrong-sensitive"), com.lqbz.ops.compat.Java8.map(), fingerprint);
        assertThat(result).isNotEmpty().allMatch(c -> !c.passed());
        assertThat(passwordAttempts.get()).isPositive();
        assertThat(commands).isEmpty();
        assertThat(result.toString()).doesNotContain(PASSWORD, "fixture-wrong-sensitive");
    }

    @Test
    void wrongHostFingerprintRejectsBeforeSendingPasswordOrCommand() {
        String wrong = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);
        List<ProbeCheck> result = probe(com.lqbz.ops.compat.Java8.map("password", PASSWORD), com.lqbz.ops.compat.Java8.map(), wrong);
        assertThat(result).isNotEmpty().allMatch(c -> !c.passed());
        assertThat(passwordAttempts.get()).isZero();
        assertThat(publicKeyAttempts.get()).isZero();
        assertThat(commands).isEmpty();
        assertThat(result.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void privateKeyAuthenticationUsesRealSignatureWithoutPasswordFallback() {
        // 临时用户私钥只留在内存中，不写磁盘或输出到测试日志。
        String key = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(userKey.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        List<ProbeCheck> result = probe(com.lqbz.ops.compat.Java8.map("privateKey", key), com.lqbz.ops.compat.Java8.map("excludedMountPoints", "/data"), fingerprint);
        assertThat(result).hasSize(2).allMatch(ProbeCheck::passed);
        assertThat(publicKeyAttempts.get()).isPositive();
        assertThat(passwordAttempts.get()).isZero();
        assertThat(commands).containsExactly("LC_ALL=C df -Pk");
        assertThat(result.toString()).doesNotContain("BEGIN PRIVATE KEY");
    }

    private List<ProbeCheck> probe(Map<String, String> secrets, Map<String, String> options, String hostFingerprint) {
        lombok.val configured = new java.util.HashMap<>(options);
        configured.put("username", USERNAME);
        configured.put("hostKey", hostFingerprint);
        configured.put("diskThreshold", "80");
        configured.put("timeoutMillis", "3000");
        Asset asset = new Asset("SSH夹具", "测试项目", "测试环境", "SERVER", "127.0.0.1:" + server.getPort(), "UNKNOWN");
        return new SystemProtocolProbe().check(asset, new ProbeSettings(configured, secrets));
    }

    // 真正的SSH会话通道只返回受控df内容，绝不启动宿主shell或执行宿主命令。
    private static final class DfCommand implements Command {

        private final String command;

        private OutputStream output;

        private ExitCallback callback;

        DfCommand(String command) {
            this.command = command;
        }

        public void setInputStream(InputStream input) {
        }

        public void setOutputStream(OutputStream output) {
            this.output = output;
        }

        public void setErrorStream(OutputStream error) {
        }

        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        public void start(ChannelSession channel, Environment environment) {
            com.lqbz.ops.compat.Java8.daemonFactory("ssh-fixture").newThread(() -> {
                try {
                    if (!"LC_ALL=C df -Pk".equals(command)) {
                        callback.onExit(127);
                        return;
                    }
                    output.write(DF.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    callback.onExit(0);
                } catch (IOException exception) {
                    callback.onExit(1);
                }
            }).start();
        }

        public void destroy(ChannelSession channel) {
        }
    }
}
