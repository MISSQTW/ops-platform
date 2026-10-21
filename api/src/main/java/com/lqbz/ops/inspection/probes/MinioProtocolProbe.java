package com.lqbz.ops.inspection.probes;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.inspection.ProbeCheck;
import com.lqbz.ops.inspection.ProbeSettings;
import com.lqbz.ops.inspection.ProtocolProbe;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;

@Component
public class MinioProtocolProbe implements ProtocolProbe {
  @Override public boolean supports(String type) { return "MINIO".equalsIgnoreCase(type); }

  @Override public List<ProbeCheck> check(Asset asset, ProbeSettings settings) {
    long started = System.nanoTime();
    String target = "";
    boolean passed = false;
    String message = "MinIO S3 检查失败";
    try {
      int timeout = settings.timeoutMillis();
      String bucket = settings.text("bucket", "");
      if (timeout < 1000 || timeout > 10000 || bucket.isBlank()
          || settings.secret("accessKey").isBlank() || settings.secret("secretKey").isBlank()) throw new IllegalArgumentException();
      String endpoint = asset.getEndpoint();
      URI uri = URI.create(endpoint.contains("://") ? endpoint : (settings.flag("tls") ? "https://" : "http://") + endpoint);
      if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
          || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
          || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))
          || (settings.flag("tls") && !uri.getScheme().equals("https"))) throw new IllegalArgumentException();
      if (uri.getPort() == -1) uri = URI.create(uri.getScheme() + "://" + uri.getHost() + ":9000");
      target = uri.getRawAuthority();
      try (MinioClient client = createClient(uri.toString(), settings)) {
        passed = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
      }
      message = passed ? "MinIO S3 指定桶访问通过" : "MinIO 指定桶不存在或不可访问";
    } catch (Exception e) {
      passed = false;
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      // 不向结果传递 SDK 异常原文，防止泄露签名或服务端响应内容。
    }
    return List.of(new ProbeCheck("PROTOCOL", "MinIO S3 桶访问", target, passed, message,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
  }

  protected MinioClient createClient(String endpoint, ProbeSettings settings) {
    int timeout = settings.timeoutMillis();
    OkHttpClient http = new OkHttpClient.Builder().connectTimeout(timeout, TimeUnit.MILLISECONDS)
        .readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS)
        .callTimeout(timeout, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build();
    return MinioClient.builder().endpoint(endpoint)
        .credentials(settings.secret("accessKey"), settings.secret("secretKey"))
        .region("us-east-1").httpClient(http, true).build();
  }
}
