package com.lqbz.ops.compat.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 使用真实本地 HTTP 服务验证 Java 8 请求、流和超时语义。 */
public class HttpClientJava8Test {
  @Test
  public void protocolRoundTrip() throws Exception { runProtocolChecks(); }

  // 保留独立入口，以便迁移过程中直接使用真正的 Java 8 运行验证。
  public static void main(String[] args) throws Exception {
    runProtocolChecks();
    System.out.println("Java 8 HTTP 协议检查通过");
  }

  private static void runProtocolChecks() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService serverThreads = Executors.newCachedThreadPool();
    server.setExecutor(serverThreads);
    final AtomicBoolean targetVisited = new AtomicBoolean();
    server.createContext("/echo", exchange -> {
      String requestBody = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("X-Reply", "present");
      respond(exchange, 200, exchange.getRequestMethod() + ":" + requestBody + ":" + exchange.getRequestHeaders().getFirst("X-Check"));
    });
    server.createContext("/redirect", exchange -> {
      exchange.getResponseHeaders().add("Location", "/target");
      respond(exchange, 302, "移动");
    });
    server.createContext("/target", exchange -> { targetVisited.set(true); respond(exchange, 200, "目标"); });
    server.createContext("/error", exchange -> respond(exchange, 503, "服务不可用"));
    server.createContext("/charset", exchange -> {
      exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=ISO-8859-1");
      byte[] bytes = "café".getBytes("ISO-8859-1");
      exchange.sendResponseHeaders(200, bytes.length);
      try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    });
    server.createContext("/slow", exchange -> {
      try {
        exchange.sendResponseHeaders(200, 0);
        for (int i = 0; i < 30; i++) {
          exchange.getResponseBody().write('a');
          exchange.getResponseBody().flush();
          Thread.sleep(40);
        }
      } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
      catch (IOException ignored) { /* 超时取消会主动断开连接。 */ }
      finally { exchange.close(); }
    });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    try {
      for (String method : new String[] { "GET", "POST", "PUT", "DELETE", "PATCH" }) {
        String body = "中文请求";
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/echo"))
            .header("X-Check", "ok").method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 200, "状态码");
        check(response.body().equals(method + ":" + body + ":ok"), "请求方法、请求头及 UTF-8 请求体");
        check(response.headers().firstValue("x-reply").orElse("").equals("present"), "响应头大小写");
      }
      HttpResponse<String> redirect = client.send(HttpRequest.newBuilder(URI.create(base + "/redirect")).build(), HttpResponse.BodyHandlers.ofString());
      check(redirect.statusCode() == 302 && !targetVisited.get(), "默认禁止重定向");
      check(client.send(HttpRequest.newBuilder(URI.create(base + "/error")).build(), HttpResponse.BodyHandlers.ofString()).body().equals("服务不可用"), "错误响应体");
      check(client.send(HttpRequest.newBuilder(URI.create(base + "/charset")).build(), HttpResponse.BodyHandlers.ofString()).body().equals("café"), "响应声明的字符集");
      try (InputStream stream = client.send(HttpRequest.newBuilder(URI.create(base + "/error")).build(), HttpResponse.BodyHandlers.ofInputStream()).body()) {
        check(new String(readAll(stream), StandardCharsets.UTF_8).equals("服务不可用"), "流式响应");
      }
      long start = System.nanoTime();
      try {
        client.send(HttpRequest.newBuilder(URI.create(base + "/slow")).timeout(Duration.ofMillis(180)).build(), HttpResponse.BodyHandlers.ofString());
        throw new AssertionError("慢速持续响应未触发完整请求超时");
      } catch (SocketTimeoutException expected) {
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000, "完整请求及时超时");
      }
      final CountDownLatch interrupted = new CountDownLatch(1);
      Thread caller = new Thread(() -> {
        try {
          client.send(HttpRequest.newBuilder(URI.create(base + "/slow")).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException expected) { interrupted.countDown(); }
        catch (IOException error) { throw new AssertionError(error); }
      });
      caller.start();
      caller.interrupt();
      check(interrupted.await(1, TimeUnit.SECONDS), "同步调用可被中断");
      caller.join(1000);
      CompletableFuture<HttpResponse<String>> pending = client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/slow")).build(), HttpResponse.BodyHandlers.ofString());
      check(pending.cancel(true) && pending.isCancelled(), "异步取消");
    } finally {
      client.shutdownNow();
      server.stop(0);
      serverThreads.shutdownNow();
    }
  }

  private static void respond(HttpExchange exchange, int status, String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
  }
  private static byte[] readAll(InputStream stream) throws IOException {
    try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[1024];
      int count;
      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
      return output.toByteArray();
    }
  }
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
