package com.lqbz.ops.inspection.probes;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.inspection.ProbeSettings;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MinioProtocolProbeTest {

    private final Asset asset = new Asset("s3", "p", "e", "MinIO", "127.0.0.1:9000", "UNKNOWN");

    private final ProbeSettings settings = new ProbeSettings(com.lqbz.ops.compat.Java8.map("bucket", "inspection"), com.lqbz.ops.compat.Java8.map("accessKey", "access", "secretKey", "secret"));

    @Test
    void checksConfiguredBucketUsingS3Client() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        lombok.val probe = new MinioProtocolProbe() {

            @Override
            protected MinioClient createClient(String endpoint, ProbeSettings settings) {
                return client;
            }
        };
        assertThat(probe.check(asset, settings).get(0).passed()).isTrue();
        lombok.val args = org.mockito.ArgumentCaptor.forClass(BucketExistsArgs.class);
        verify(client).bucketExists(args.capture());
        assertThat(args.getValue().bucket()).isEqualTo("inspection");
    }

    @Test
    void missingBucketAndClientErrorsAreFixedFailures() throws Exception {
        MinioClient client = mock(MinioClient.class);
        lombok.val probe = new MinioProtocolProbe() {

            @Override
            protected MinioClient createClient(String endpoint, ProbeSettings settings) {
                return client;
            }
        };
        assertThat(probe.check(asset, settings).get(0).message()).isEqualTo("MinIO 指定桶不存在或不可访问");
        when(client.bucketExists(any(BucketExistsArgs.class))).thenThrow(new RuntimeException("secret"));
        lombok.val failure = probe.check(asset, settings).get(0);
        assertThat(failure.passed()).isFalse();
        assertThat(failure.message()).isEqualTo("MinIO S3 检查失败");
        assertThat(failure.toString()).doesNotContain("secret");
    }

    @Test
    void rejectsMissingBucketCredentialsAndCredentialBearingEndpoint() {
        lombok.val probe = new MinioProtocolProbe();
        assertThat(probe.check(asset, new ProbeSettings(com.lqbz.ops.compat.Java8.map(), com.lqbz.ops.compat.Java8.map())).get(0).passed()).isFalse();
        lombok.val bad = new Asset("s3", "p", "e", "MinIO", "http://user:secret@localhost:9000", "UNKNOWN");
        assertThat(probe.check(bad, settings).get(0).toString()).doesNotContain("secret", "user:");
        assertThat(probe.supports("MinIO")).isTrue();
        assertThat(probe.supports("Kafka")).isFalse();
    }

    @Test
    void sendsSignedS3HeadRequestToRealHttpFixture() throws Exception {
        lombok.val requestMethod = new java.util.concurrent.atomic.AtomicReference<String>();
        lombok.val requestPath = new java.util.concurrent.atomic.AtomicReference<String>();
        lombok.val authorization = new java.util.concurrent.atomic.AtomicReference<String>();
        lombok.val status = new java.util.concurrent.atomic.AtomicInteger(200);
        lombok.val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        try {
            lombok.val target = new Asset("s3", "p", "e", "MinIO", "127.0.0.1:" + server.getAddress().getPort(), "UNKNOWN");
            lombok.val probe = new MinioProtocolProbe();
            assertThat(probe.check(target, settings).get(0).passed()).isTrue();
            assertThat(requestMethod.get()).isEqualTo("HEAD");
            assertThat(requestPath.get()).isEqualTo("/inspection");
            assertThat(authorization.get()).startsWith("AWS4-HMAC-SHA256 Credential=access/");
            status.set(404);
            assertThat(probe.check(target, settings).get(0).passed()).isFalse();
            status.set(403);
            lombok.val denied = probe.check(target, settings).get(0);
            assertThat(denied.passed()).isFalse();
            assertThat(denied.message()).isEqualTo("MinIO S3 检查失败");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void clientCloseFailureDoesNotReturnSuccess() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        doThrow(new Exception("secret")).when(client).close();
        lombok.val probe = new MinioProtocolProbe() {

            @Override
            protected MinioClient createClient(String endpoint, ProbeSettings settings) {
                return client;
            }
        };
        assertThat(probe.check(asset, settings).get(0).passed()).isFalse();
    }

    @Test
    void usesMinioDefaultPortForEndpointsWithoutPort() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        lombok.val endpoint = new java.util.concurrent.atomic.AtomicReference<String>();
        lombok.val probe = new MinioProtocolProbe() {

            @Override
            protected MinioClient createClient(String value, ProbeSettings settings) {
                endpoint.set(value);
                return client;
            }
        };
        lombok.val target = new Asset("s3", "p", "e", "MinIO", "localhost", "UNKNOWN");
        assertThat(probe.check(target, settings).get(0).passed()).isTrue();
        assertThat(endpoint.get()).isEqualTo("http://localhost:9000");
        target.update("s3", "p", "e", "MinIO", "https://localhost", "UNKNOWN");
        assertThat(probe.check(target, settings).get(0).passed()).isTrue();
        assertThat(endpoint.get()).isEqualTo("https://localhost:9000");
    }
}
