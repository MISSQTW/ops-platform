package com.lqbz.ops.inspection;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public record ProbeSettings(Map<String, String> options, Map<String, String> secrets) {
  public ProbeSettings {
    options = options == null ? Map.of() : Map.copyOf(options);
    secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
  }
  public String text(String key, String fallback) { return options.getOrDefault(key, fallback); }
  public String secret(String key) { return secrets.getOrDefault(key, ""); }
  public int number(String key, int fallback) {
    String value = options.get(key);
    return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
  }
  public boolean flag(String key) { return Boolean.parseBoolean(text(key, "false")); }
  public List<String> list(String key) {
    return Arrays.stream(text(key, "").split("[,\\n]")).map(String::trim).filter(s -> !s.isBlank()).toList();
  }
  public int timeoutMillis() { return number("timeoutMillis", 3000); }
}
