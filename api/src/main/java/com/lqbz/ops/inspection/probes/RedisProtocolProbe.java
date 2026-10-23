package com.lqbz.ops.inspection.probes;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.inspection.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.springframework.stereotype.Component;

@Component
public class RedisProtocolProbe implements ProtocolProbe {

    public boolean supports(String type) {
        return "REDIS".equalsIgnoreCase(type);
    }

    public List<ProbeCheck> check(Asset asset, ProbeSettings settings) {
        long start = System.nanoTime();
        try {
            int timeout = timeout(settings);
            URI endpoint = endpoint(asset.getEndpoint(), 6379);
            try (Socket socket = settings.flag("tls") ? SSLSocketFactory.getDefault().createSocket() : new Socket()) {
                // 整次协议交互设置绝对截止时间，避免慢速持续回包重置每次读取超时。
                ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(com.lqbz.ops.compat.Java8.daemonFactory("redis-probe-timeout"));
                ScheduledFuture<?> deadline = timer.schedule(() -> {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }, timeout, TimeUnit.MILLISECONDS);
                try {
                    socket.connect(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()), timeout);
                    socket.setSoTimeout(timeout);
                    if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                        // 使用系统信任库，同时校验服务端主机名，禁止静默信任未知证书。
                        SSLParameters params = ssl.getSSLParameters();
                        params.setEndpointIdentificationAlgorithm("HTTPS");
                        ssl.setSSLParameters(params);
                        ssl.startHandshake();
                    }
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    String password = settings.secret("password"), username = settings.text("username", "");
                    if (!com.lqbz.ops.compat.Java8.isBlank(username) && password.isEmpty())
                        throw new IllegalArgumentException();
                    if (!password.isEmpty()) {
                        command(out, com.lqbz.ops.compat.Java8.isBlank(username) ? new String[] { "AUTH", password } : new String[] { "AUTH", username, password });
                        if (!"+OK".equals(reply(in)))
                            return result(false, "Redis 认证失败", start);
                    }
                    command(out, new String[] { "PING" });
                    return result("+PONG".equals(reply(in)), "Redis AUTH/PING 检查", start);
                } finally {
                    deadline.cancel(false);
                    timer.shutdownNow();
                }
            }
        } catch (IllegalArgumentException e) {
            return result(false, "Redis 配置无效", start);
        } catch (Exception e) {
            if (e instanceof InterruptedException)
                Thread.currentThread().interrupt();
            return result(false, "Redis 协议连接或认证失败", start);
        }
    }

    private static void command(OutputStream out, String[] values) throws IOException {
        out.write(("*" + values.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String value : values) {
            byte[] b = value.getBytes(StandardCharsets.UTF_8);
            out.write(("$" + b.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(b);
            out.write(new byte[] { 13, 10 });
        }
        out.flush();
    }

    private static String reply(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int i = 0; i < 4096; i++) {
            int b = in.read();
            if (b < 0)
                throw new EOFException();
            if (b == 13) {
                if (in.read() != 10)
                    throw new IOException();
                return new String(line.toByteArray(), StandardCharsets.UTF_8);
            }
            line.write(b);
        }
        throw new IOException();
    }

    private List<ProbeCheck> result(boolean passed, String message, long start) {
        return com.lqbz.ops.compat.Java8.list(new ProbeCheck("REDIS", "Redis AUTH/PING", "Redis", passed, message, elapsed(start)));
    }

    static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    static int timeout(ProbeSettings settings) {
        int timeout = settings.timeoutMillis();
        if (timeout < 1000 || timeout > 10000)
            throw new IllegalArgumentException();
        return timeout;
    }

    static URI endpoint(String text, int defaultPort) {
        URI uri = URI.create(text.contains("://") ? text : "tcp://" + text);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())))
            throw new IllegalArgumentException();
        int port = uri.getPort() < 0 ? defaultPort : uri.getPort();
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException();
        try {
            return new URI("tcp", null, uri.getHost(), port, null, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException();
        }
    }
}
