package com.lqbz.ops.compat.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Java 8 同步及异步响应；流式响应必须由调用方关闭 body。 */
public final class HttpResponse<T> {
  private final int status;
  private final T body;
  private final Headers headers;
  HttpResponse(int status, T body, Headers headers) {
    this.status = status;
    this.body = body;
    this.headers = headers;
  }
  public int statusCode() { return status; }
  public T body() { return body; }
  public Headers headers() { return headers; }

  public static final class Headers {
    private final Map<String, List<String>> values;
    Headers(Map<String, List<String>> source) {
      Map<String, List<String>> copy = new LinkedHashMap<String, List<String>>();
      for (Map.Entry<String, List<String>> entry : source.entrySet()) {
        if (entry.getKey() != null) copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<String>(entry.getValue())));
      }
      values = Collections.unmodifiableMap(copy);
    }
    public Optional<String> firstValue(String name) {
      for (Map.Entry<String, List<String>> entry : values.entrySet()) {
        if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) return Optional.of(entry.getValue().get(0));
      }
      return Optional.empty();
    }
    public Map<String, List<String>> map() { return values; }
  }

  public interface BodyHandler<T> {
    T read(InputStream stream, Headers headers) throws IOException;
  }

  public static final class BodyHandlers {
    private BodyHandlers() { }
    public static BodyHandler<InputStream> ofInputStream() {
      return new BodyHandler<InputStream>() {
        public InputStream read(InputStream stream, Headers headers) { return stream; }
      };
    }
    public static BodyHandler<String> ofString() { return ofString(null); }
    public static BodyHandler<String> ofString(final Charset charset) {
      return new BodyHandler<String>() {
        public String read(InputStream stream, Headers headers) throws IOException {
          Charset encoding = charset;
          if (encoding == null) {
            encoding = StandardCharsets.UTF_8;
            String contentType = headers.firstValue("Content-Type").orElse("");
            for (String part : contentType.split(";")) {
              String parameter = part.trim();
              if (parameter.toLowerCase(java.util.Locale.ROOT).startsWith("charset=")) {
                try { encoding = Charset.forName(parameter.substring(8).trim().replace("\"", "")); }
                catch (IllegalArgumentException ignored) { /* 未知编码沿用 UTF-8。 */ }
              }
            }
          }
          try (InputStream input = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) bytes.write(buffer, 0, length);
            return new String(bytes.toByteArray(), encoding);
          }
        }
      };
    }
  }
}
