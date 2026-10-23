package com.lqbz.ops.inspection;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@lombok.EqualsAndHashCode()
@lombok.ToString()
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
public final class ProbeSettings {

    @com.fasterxml.jackson.annotation.JsonCreator()
    public ProbeSettings(@com.fasterxml.jackson.annotation.JsonProperty("options") Map<String, String> options, @com.fasterxml.jackson.annotation.JsonProperty("secrets") Map<String, String> secrets) {
        options = options == null ? com.lqbz.ops.compat.Java8.map() : com.lqbz.ops.compat.Java8.mapCopy(options);
        secrets = secrets == null ? com.lqbz.ops.compat.Java8.map() : com.lqbz.ops.compat.Java8.mapCopy(secrets);
        this.options = options;
        this.secrets = secrets;
    }

    private final Map<String, String> options;

    public Map<String, String> options() {
        return this.options;
    }

    private final Map<String, String> secrets;

    public Map<String, String> secrets() {
        return this.secrets;
    }

    public String text(String key, String fallback) {
        return options.getOrDefault(key, fallback);
    }

    public String secret(String key) {
        return secrets.getOrDefault(key, "");
    }

    public int number(String key, int fallback) {
        String value = options.get(key);
        return value == null || com.lqbz.ops.compat.Java8.isBlank(value) ? fallback : Integer.parseInt(value);
    }

    public boolean flag(String key) {
        return Boolean.parseBoolean(text(key, "false"));
    }

    public List<String> list(String key) {
        return Arrays.stream(text(key, "").split("[,\\n]")).map(String::trim).filter(s -> !com.lqbz.ops.compat.Java8.isBlank(s)).collect(java.util.stream.Collectors.toList());
    }

    public int timeoutMillis() {
        return number("timeoutMillis", 3000);
    }
}
