package com.lqbz.ops.compat.http;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.http.Header;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

/** 基于 Apache HttpClient 4 的 Java 8 项目专用客户端，仅提供现有调用需要的 API。 */
public final class HttpClient {
  public enum Redirect { NEVER }
  private static final ThreadFactory DAEMON_THREADS = new ThreadFactory() {
    public Thread newThread(Runnable action) {
      Thread thread = new Thread(action, "java8-http");
      thread.setDaemon(true);
      return thread;
    }
  };
  private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(DAEMON_THREADS);
  private static final ExecutorService DISCONNECT = Executors.newCachedThreadPool(DAEMON_THREADS);
  private final ExecutorService executor = Executors.newCachedThreadPool(DAEMON_THREADS);
  private final Set<Exchange<?>> exchanges = ConcurrentHashMap.<Exchange<?>>newKeySet();
  private final Duration connectTimeout;
  private final AtomicBoolean stopped = new AtomicBoolean();

  private HttpClient(Builder builder) { connectTimeout = builder.connectTimeout; }
  public static Builder newBuilder() { return new Builder(); }
  public static HttpClient newHttpClient() { return newBuilder().build(); }

  public static final class Builder {
    private Duration connectTimeout;
    public Builder connectTimeout(Duration value) {
      HttpRequest.requirePositive(value);
      connectTimeout = value;
      return this;
    }
    public Builder followRedirects(Redirect policy) {
      java.util.Objects.requireNonNull(policy, "policy");
      return this;
    }
    public HttpClient build() { return new HttpClient(this); }
  }

  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    if (Thread.interrupted()) throw new InterruptedException();
    CompletableFuture<HttpResponse<T>> pending = sendAsync(request, handler);
    try {
      return pending.get();
    } catch (InterruptedException error) {
      pending.cancel(true);
      throw error;
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof IOException) throw (IOException) cause;
      if (cause instanceof RuntimeException) throw (RuntimeException) cause;
      throw new IOException("HTTP 请求失败", cause);
    }
  }

  public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    java.util.Objects.requireNonNull(request, "request");
    java.util.Objects.requireNonNull(handler, "handler");
    final Exchange<T> exchange = new Exchange<T>(request, handler);
    if (stopped.get()) {
      exchange.completeExceptionally(new IOException("HTTP 客户端已关闭"));
      return exchange;
    }
    exchanges.add(exchange);
    try {
      exchange.task = executor.submit(exchange);
      if (request.timeout != null) {
        exchange.timer = TIMER.schedule(new Runnable() {
          public void run() {
            if (exchange.completeExceptionally(new SocketTimeoutException("HTTP 请求超时"))) exchange.abort();
          }
        }, timeoutMillis(request.timeout), TimeUnit.MILLISECONDS);
        if (exchange.isDone()) exchange.timer.cancel(false);
      }
    } catch (RuntimeException error) {
      exchange.completeExceptionally(error);
      exchange.abort();
    }
    return exchange;
  }

  public void shutdownNow() {
    stopped.set(true);
    for (Exchange<?> exchange : exchanges) exchange.cancel(true);
    executor.shutdownNow();
  }

  private static int timeoutMillis(Duration timeout) {
    if (timeout == null) return 0;
    if (timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) >= 0) return Integer.MAX_VALUE;
    return (int) Math.max(1L, timeout.toMillis());
  }

  private final class Exchange<T> extends CompletableFuture<HttpResponse<T>> implements Runnable {
    private final HttpRequest request;
    private final HttpResponse.BodyHandler<T> handler;
    private volatile HttpEntityEnclosingRequestBase connection;
    private volatile CloseableHttpClient transport;
    private volatile CloseableHttpResponse response;
    private volatile Future<?> task;
    private volatile ScheduledFuture<?> timer;

    private Exchange(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      this.request = request;
      this.handler = handler;
    }

    public void run() {
      boolean streaming = false;
      try {
        if (isDone()) return;
        connection = new HttpEntityEnclosingRequestBase() {
          public String getMethod() { return request.method; }
        };
        connection.setURI(request.uri);
        connection.setConfig(RequestConfig.custom()
            .setConnectTimeout(timeoutMillis(connectTimeout))
            .setSocketTimeout(timeoutMillis(request.timeout))
            .setRedirectsEnabled(false).build());
        for (Map.Entry<String, List<String>> entry : request.headers.entrySet()) {
          for (String value : entry.getValue()) connection.addHeader(entry.getKey(), value);
        }
        // 保留 GET 等方法的显式请求体，不触发 HttpURLConnection 的方法自动转换。
        if (request.body.length > 0) connection.setEntity(new ByteArrayEntity(request.body));
        transport = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().build();
        if (isDone()) return;
        response = transport.execute(connection);
        int status = response.getStatusLine().getStatusCode();
        InputStream raw = response.getEntity() == null
            ? new ByteArrayInputStream(new byte[0]) : response.getEntity().getContent();
        InputStream stream = new FilterInputStream(raw) {
          public void close() throws IOException {
            try { super.close(); } finally { release(); }
          }
        };
        Map<String, List<String>> values = new java.util.TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        for (Header header : response.getAllHeaders()) {
          List<String> entries = values.get(header.getName());
          if (entries == null) { entries = new java.util.ArrayList<String>(); values.put(header.getName(), entries); }
          entries.add(header.getValue());
        }
        HttpResponse.Headers headers = new HttpResponse.Headers(values);
        T body;
        try { body = handler.read(stream, headers); }
        catch (Exception error) { stream.close(); throw error; }
        streaming = body == stream;
        if (!streaming) stream.close();
        if (!complete(new HttpResponse<T>(status, body, headers)) && streaming) {
          streaming = false;
          stream.close();
        }
      } catch (Exception error) {
        completeExceptionally(error);
      } finally {
        ScheduledFuture<?> timeoutTask = timer;
        if (timeoutTask != null) timeoutTask.cancel(false);
        if (!streaming) release();
      }
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
      boolean cancelled = super.cancel(mayInterruptIfRunning);
      // 即使响应头已完成，关闭客户端仍应中断未关闭的流式响应。
      abort();
      return cancelled;
    }

    private void abort() {
      Future<?> running = task;
      if (running != null) running.cancel(true);
      ScheduledFuture<?> timeoutTask = timer;
      if (timeoutTask != null) timeoutTask.cancel(false);
      // 中止请求可能涉及底层连接清理，交给守护线程避免阻塞超时调用方。
      DISCONNECT.execute(new Runnable() { public void run() { release(); } });
    }

    private void release() {
      {
        HttpEntityEnclosingRequestBase active = connection;
        if (active != null) active.abort();
        CloseableHttpResponse received = response;
        if (received != null) {
          try { received.close(); } catch (IOException ignored) { /* 已断开的连接无需重复报错。 */ }
        }
        CloseableHttpClient client = transport;
        if (client != null) {
          try { client.close(); } catch (IOException ignored) { /* 已断开的连接无需重复报错。 */ }
        }
        exchanges.remove(this);
      }
    }
  }
}
