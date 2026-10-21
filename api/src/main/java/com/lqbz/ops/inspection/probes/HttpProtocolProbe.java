package com.lqbz.ops.inspection.probes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.inspection.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

@Component
public class HttpProtocolProbe implements ProtocolProbe {
  public boolean supports(String type) { return "HTTP".equalsIgnoreCase(type); }
  public List<ProbeCheck> check(Asset asset, ProbeSettings settings) {
    long start=System.nanoTime();
    try {
      int timeout=RedisProtocolProbe.timeout(settings); URI uri=URI.create(asset.getEndpoint());
      if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null) throw new IllegalArgumentException();
      String method=settings.text("httpMethod","GET").toUpperCase(Locale.ROOT);
      if(!Set.of("GET","HEAD","POST","PUT").contains(method)) throw new IllegalArgumentException();
      int expected=settings.number("expectedStatus",200); if(expected<100 || expected>599) throw new IllegalArgumentException();
      HttpRequest.Builder request=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeout));
      String headers=settings.secret("headers");
      if(!headers.isBlank()) {
        Map<String,String> values=new ObjectMapper().readValue(headers,new TypeReference<Map<String,String>>(){});
        for(var entry:values.entrySet()) request.header(entry.getKey(),entry.getValue());
      }
      request.method(method,HttpRequest.BodyPublishers.ofString(settings.secret("body"),StandardCharsets.UTF_8));
      HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeout)).followRedirects(HttpClient.Redirect.NEVER).build();
      CompletableFuture<HttpResponse<byte[]>> pending=null;
      try {
        // 请求自身的 timeout 不覆盖所有响应体读取；独立等待完整响应并在超时后取消。
        pending=client.sendAsync(request.build(), info -> new LimitedBody());
        HttpResponse<byte[]> response=pending.get(timeout,TimeUnit.MILLISECONDS);
        boolean passed=response.statusCode()==expected && new String(response.body(),StandardCharsets.UTF_8).contains(settings.text("responseContains",""));
        return result(passed,passed?"HTTP 状态码及响应断言通过":"HTTP 状态码或响应断言不匹配",start);
      } finally {
        if(pending!=null) pending.cancel(true);
        client.shutdownNow();
      }
    } catch(IllegalArgumentException e) { return result(false,"HTTP 配置无效",start); }
    catch(Exception e) { if(e instanceof InterruptedException) Thread.currentThread().interrupt(); return result(false,"HTTP 请求失败、超时或响应超限",start); }
  }
  private List<ProbeCheck> result(boolean passed,String message,long start) { return List.of(new ProbeCheck("HTTP","HTTP 请求与断言","HTTP",passed,message,RedisProtocolProbe.elapsed(start))); }

  // 在接收过程中限制响应体，避免先无限分配内存再做长度检查。
  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body=new CompletableFuture<>();
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody() { return body; }
    public void onSubscribe(Flow.Subscription value) { subscription=value; value.request(1); }
    public void onNext(List<ByteBuffer> buffers) {
      for(ByteBuffer buffer:buffers) {
        if(buffer.remaining()>1_048_576-bytes.size()) { subscription.cancel(); body.completeExceptionally(new IOException("响应超限")); return; }
        byte[] part=new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
      }
      subscription.request(1);
    }
    public void onError(Throwable error) { body.completeExceptionally(error); }
    public void onComplete() { body.complete(bytes.toByteArray()); }
  }
}
