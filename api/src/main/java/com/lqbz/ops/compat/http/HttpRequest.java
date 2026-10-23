package com.lqbz.ops.compat.http;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 项目所需的 Java 8 HTTP 请求描述，不依赖 JDK 11 HTTP 模块。 */
public final class HttpRequest {
  final URI uri;
  final String method;
  final byte[] body;
  final Duration timeout;
  final Map<String, List<String>> headers;

  private HttpRequest(Builder builder) {
    uri = Objects.requireNonNull(builder.uri, "uri");
    method = builder.method;
    body = builder.body.bytes.clone();
    timeout = builder.timeout;
    Map<String, List<String>> copy = new LinkedHashMap<String, List<String>>();
    for (Map.Entry<String, List<String>> entry : builder.headers.entrySet()) {
      copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<String>(entry.getValue())));
    }
    headers = Collections.unmodifiableMap(copy);
  }

  public static Builder newBuilder(URI uri) { return new Builder().uri(uri); }
  public static Builder newBuilder() { return new Builder(); }

  public static final class BodyPublisher {
    private final byte[] bytes;
    private BodyPublisher(byte[] bytes) { this.bytes = bytes; }
  }

  public static final class BodyPublishers {
    private BodyPublishers() { }
    public static BodyPublisher ofString(String text) { return ofString(text, StandardCharsets.UTF_8); }
    public static BodyPublisher ofString(String text, Charset charset) {
      return new BodyPublisher(Objects.requireNonNull(text, "text").getBytes(charset));
    }
    public static BodyPublisher noBody() { return new BodyPublisher(new byte[0]); }
  }

  public static final class Builder {
    private URI uri;
    private String method = "GET";
    private BodyPublisher body = BodyPublishers.noBody();
    private Duration timeout;
    private final Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();

    public Builder uri(URI value) {
      Objects.requireNonNull(value, "uri");
      if (!("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme()))
          || value.getHost() == null) throw new IllegalArgumentException("无效的 HTTP URI");
      uri = value;
      return this;
    }
    public Builder timeout(Duration value) {
      requirePositive(value);
      timeout = value;
      return this;
    }
    public Builder header(String name, String value) {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
      if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
        throw new IllegalArgumentException("无效的 HTTP 请求头");
      }
      List<String> values = headers.get(name);
      if (values == null) { values = new ArrayList<String>(); headers.put(name, values); }
      values.add(value);
      return this;
    }
    public Builder headers(String... values) {
      if (values.length % 2 != 0) throw new IllegalArgumentException("请求头必须成对提供");
      for (int i = 0; i < values.length; i += 2) header(values[i], values[i + 1]);
      return this;
    }
    public Builder method(String value, BodyPublisher publisher) {
      if (value == null || !value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
        throw new IllegalArgumentException("无效的 HTTP 方法");
      }
      method = value;
      body = Objects.requireNonNull(publisher, "publisher");
      return this;
    }
    public Builder GET() { return method("GET", BodyPublishers.noBody()); }
    public Builder DELETE() { return method("DELETE", BodyPublishers.noBody()); }
    public Builder POST(BodyPublisher publisher) { return method("POST", publisher); }
    public Builder PUT(BodyPublisher publisher) { return method("PUT", publisher); }
    public HttpRequest build() { return new HttpRequest(this); }
  }

  static void requirePositive(Duration value) {
    if (Objects.requireNonNull(value, "timeout").isZero() || value.isNegative()) {
      throw new IllegalArgumentException("超时时间必须大于零");
    }
  }
}
