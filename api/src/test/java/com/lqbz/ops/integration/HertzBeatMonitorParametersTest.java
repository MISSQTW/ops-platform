package com.lqbz.ops.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqbz.ops.domain.Asset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HertzBeatMonitorParametersTest {
  private final HertzBeatClient client = new HertzBeatClient(new ObjectMapper(), "http://localhost", "", "");

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"http,false", "https,true"})
  void SSL开关必须使用普通字符串类型且脱敏回读不得被当成一致(String scheme, String enabled) {
    Asset asset = new Asset("接口", "系统", "生产", "HTTP", scheme + "://example.com/health", "UNKNOWN");
    var params = client.monitorParameters(asset);
    assertThat(params).contains(Map.of("field", "ssl", "paramValue", enabled, "type", 1));
    var masked = new java.util.ArrayList<>(params);
    int sslIndex = java.util.stream.IntStream.range(0, masked.size())
        .filter(index -> "ssl".equals(masked.get(index).get("field"))).findFirst().orElseThrow();
    masked.set(sslIndex, Map.of("field", "ssl", "paramValue", "******", "type", 2));
    assertThat(client.monitorParametersMatch(asset, masked)).isFalse();
  }

  @Test
  void 同主机路径变化和参数漂移必须判为不一致() {
    Asset asset = new Asset("接口", "系统", "生产", "HTTP", "https://example.com/ready", "UNKNOWN");
    List<Map<String, Object>> actual = List.of(
        Map.of("field", "host", "paramValue", "example.com", "id", 1),
        Map.of("field", "port", "paramValue", "443"),
        Map.of("field", "uri", "paramValue", "/health"),
        Map.of("field", "ssl", "paramValue", "true"),
        Map.of("field", "timeout", "paramValue", "6000"));
    assertThat(client.monitorParametersMatch(asset, actual)).isFalse();
    asset.update("接口", "系统", "生产", "HTTP", "https://example.com/health", "UNKNOWN");
    assertThat(client.monitorParametersMatch(asset, actual)).isTrue();
    assertThat(client.monitorParametersMatch(asset, actual.subList(0, 4))).isFalse();
    assertThat(client.monitorParametersMatch(asset, null)).isFalse();
    var duplicate = new java.util.ArrayList<>(actual);
    duplicate.add(actual.getFirst());
    assertThat(client.monitorParametersMatch(asset, duplicate)).isFalse();
    var drifted = new java.util.ArrayList<>(actual);
    drifted.set(3, Map.of("field", "ssl", "paramValue", "false"));
    assertThat(client.monitorParametersMatch(asset, drifted)).isFalse();
    drifted.set(3, actual.get(3));
    drifted.set(4, Map.of("field", "timeout", "paramValue", "1"));
    assertThat(client.monitorParametersMatch(asset, drifted)).isFalse();
    drifted.set(4, Map.of("field", "timeout"));
    assertThat(client.monitorParametersMatch(asset, drifted)).isFalse();
    asset.update("接口", "系统", "生产", "HTTP", "https://example.com/health?probe=ready", "UNKNOWN");
    assertThat(client.monitorParametersMatch(asset, actual)).isFalse();
    assertThat(client.monitorParameters(asset)).contains(Map.of("field", "uri", "paramValue", "/health?probe=ready", "type", 1));
  }
}
