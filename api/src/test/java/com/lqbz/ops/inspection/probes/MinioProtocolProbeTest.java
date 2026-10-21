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
  private final ProbeSettings settings = new ProbeSettings(Map.of("bucket", "inspection"), Map.of("accessKey", "access", "secretKey", "secret"));

  @Test void checksConfiguredBucketUsingS3Client() throws Exception {
    MinioClient client = mock(MinioClient.class);
    when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    var probe = new MinioProtocolProbe() {
      @Override protected MinioClient createClient(String endpoint, ProbeSettings settings) { return client; }
    };
    assertThat(probe.check(asset, settings).getFirst().passed()).isTrue();
    var args = org.mockito.ArgumentCaptor.forClass(BucketExistsArgs.class);
    verify(client).bucketExists(args.capture());
    assertThat(args.getValue().bucket()).isEqualTo("inspection");
  }

  @Test void missingBucketAndClientErrorsAreFixedFailures() throws Exception {
    MinioClient client = mock(MinioClient.class);
    var probe = new MinioProtocolProbe() {
      @Override protected MinioClient createClient(String endpoint, ProbeSettings settings) { return client; }
    };
    assertThat(probe.check(asset, settings).getFirst().message()).isEqualTo("MinIO 指定桶不存在或不可访问");
    when(client.bucketExists(any(BucketExistsArgs.class))).thenThrow(new RuntimeException("secret"));
    var failure = probe.check(asset, settings).getFirst();
    assertThat(failure.passed()).isFalse();
    assertThat(failure.message()).isEqualTo("MinIO S3 检查失败");
    assertThat(failure.toString()).doesNotContain("secret");
  }

  @Test void rejectsMissingBucketCredentialsAndCredentialBearingEndpoint() {
    var probe = new MinioProtocolProbe();
    assertThat(probe.check(asset, new ProbeSettings(Map.of(), Map.of())).getFirst().passed()).isFalse();
    var bad = new Asset("s3", "p", "e", "MinIO", "http://user:secret@localhost:9000", "UNKNOWN");
    assertThat(probe.check(bad, settings).getFirst().toString()).doesNotContain("secret", "user:");
    assertThat(probe.supports("MinIO")).isTrue();
    assertThat(probe.supports("Kafka")).isFalse();
  }
  @Test void sendsSignedS3HeadRequestToRealHttpFixture() throws Exception {
    var requestMethod = new java.util.concurrent.atomic.AtomicReference<String>();
    var requestPath = new java.util.concurrent.atomic.AtomicReference<String>();
    var authorization = new java.util.concurrent.atomic.AtomicReference<String>();
    var status = new java.util.concurrent.atomic.AtomicInteger(200);
    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
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
      var target = new Asset("s3", "p", "e", "MinIO", "127.0.0.1:" + server.getAddress().getPort(), "UNKNOWN");
      var probe = new MinioProtocolProbe();
      assertThat(probe.check(target, settings).getFirst().passed()).isTrue();
      assertThat(requestMethod.get()).isEqualTo("HEAD");
      assertThat(requestPath.get()).isEqualTo("/inspection");
      assertThat(authorization.get()).startsWith("AWS4-HMAC-SHA256 Credential=access/");
      status.set(404);
      assertThat(probe.check(target, settings).getFirst().passed()).isFalse();
      status.set(403);
      var denied = probe.check(target, settings).getFirst();
      assertThat(denied.passed()).isFalse();
      assertThat(denied.message()).isEqualTo("MinIO S3 检查失败");
    } finally { server.stop(0); }
  }

  @Test void clientCloseFailureDoesNotReturnSuccess() throws Exception {
    MinioClient client = mock(MinioClient.class);
    when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    doThrow(new Exception("secret")).when(client).close();
    var probe = new MinioProtocolProbe() {
      @Override protected MinioClient createClient(String endpoint, ProbeSettings settings) { return client; }
    };
    assertThat(probe.check(asset, settings).getFirst().passed()).isFalse();
  }

  @Test void usesMinioDefaultPortForEndpointsWithoutPort() throws Exception {
    MinioClient client = mock(MinioClient.class);
    when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    var endpoint = new java.util.concurrent.atomic.AtomicReference<String>();
    var probe = new MinioProtocolProbe() {
      @Override protected MinioClient createClient(String value, ProbeSettings settings) {
        endpoint.set(value);
        return client;
      }
    };
    var target = new Asset("s3", "p", "e", "MinIO", "localhost", "UNKNOWN");
    assertThat(probe.check(target, settings).getFirst().passed()).isTrue();
    assertThat(endpoint.get()).isEqualTo("http://localhost:9000");
    target.update("s3", "p", "e", "MinIO", "https://localhost", "UNKNOWN");
    assertThat(probe.check(target, settings).getFirst().passed()).isTrue();
    assertThat(endpoint.get()).isEqualTo("https://localhost:9000");
  }

}
