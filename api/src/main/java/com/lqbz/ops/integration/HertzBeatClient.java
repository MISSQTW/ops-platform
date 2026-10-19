package com.lqbz.ops.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqbz.ops.domain.Asset;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HertzBeatClient {
  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final String username;
  private final String password;

  public HertzBeatClient(ObjectMapper objectMapper,
      @Value("${ops.hertzbeat.url:http://hertzbeat:1157}") String baseUrl,
      @Value("${ops.hertzbeat.username:admin}") String username,
      @Value("${ops.hertzbeat.password:hertzbeat}") String password) {
    this.objectMapper = objectMapper;
    this.baseUrl = baseUrl;
    this.username = username;
    this.password = password;
  }

  public Status status() {
    long startedAt = System.nanoTime();
    try {
      boolean connected = !login().isBlank();
      return new Status(connected, baseUrl, elapsedMillis(startedAt), connected ? "认证连接正常" : "认证失败");
    } catch (Exception exception) {
      return new Status(false, baseUrl, elapsedMillis(startedAt), "连接失败：" + exception.getClass().getSimpleName());
    }
  }

  public SyncResult sync(Asset asset) throws Exception {
    String token = login();
    if (token.isBlank()) throw new IllegalStateException("HertzBeat 认证失败");
    MonitorTarget target = monitorTarget(asset);
    Long existingMonitorId = asset.getHertzbeatMonitorId();
    JsonNode existing = existingMonitorId == null ? null : getMonitorDetails(token, existingMonitorId);
    Map<String, Object> monitor = new LinkedHashMap<>();
    if (existingMonitorId != null) {
      monitor.put("id", existingMonitorId);
      long jobId = existing.path("monitor").path("jobId").asLong(0);
      if (jobId > 0) monitor.put("jobId", jobId);
    }
    monitor.put("name", asset.getName());
    monitor.put("app", target.app());
    monitor.put("instance", target.instance());
    monitor.put("intervals", 60);
    monitor.put("scheduleType", "fixed");
    monitor.put("status", 1);
    monitor.put("type", 0);
    monitor.put("description", asset.getProjectName() + " / " + asset.getEnvironmentName());
    Map<String, Object> body = Map.of("monitor", monitor, "params", enrichParams(target.params(), existingMonitorId, existing));
    URI requestUri = URI.create(baseUrl + "/api/monitor");
    HttpRequest.BodyPublisher requestBody = HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body));
    HttpRequest request = HttpRequest.newBuilder(requestUri)
        .timeout(Duration.ofSeconds(8))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + token)
        .method(existingMonitorId == null ? "POST" : "PUT", requestBody)
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode json = objectMapper.readTree(response.body());
    if (response.statusCode() < 200 || response.statusCode() >= 300 || json.path("code").asInt(-1) != 0) {
      String action = existingMonitorId == null ? "创建" : "更新";
      throw new IllegalStateException("HertzBeat " + action + "监控失败：" + json.path("msg").asText("HTTP " + response.statusCode()));
    }
    long monitorId = existingMonitorId == null ? json.path("data").asLong(0) : existingMonitorId;
    if (monitorId <= 0 && existingMonitorId == null) monitorId = findMonitorId(token, asset.getName(), target.app());
    if (monitorId <= 0) throw new IllegalStateException("HertzBeat 未返回监控 ID");
    return new SyncResult(monitorId, "同步成功");
  }

  private JsonNode getMonitorDetails(String token, long monitorId) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/monitor/" + monitorId))
        .timeout(Duration.ofSeconds(5))
        .header("Authorization", "Bearer " + token)
        .GET()
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode json = objectMapper.readTree(response.body());
    if (response.statusCode() < 200 || response.statusCode() >= 300 || json.path("code").asInt(-1) != 0) {
      throw new IllegalStateException("HertzBeat 查询监控详情失败：" + json.path("msg").asText("HTTP " + response.statusCode()));
    }
    return json.path("data");
  }

  private List<Map<String, Object>> enrichParams(List<Map<String, Object>> params, Long monitorId, JsonNode existing) {
    if (monitorId == null || existing == null) return params;
    return params.stream().map(param -> {
      Map<String, Object> enriched = new LinkedHashMap<>(param);
      for (JsonNode oldParam : existing.path("params")) {
        if (param.get("field").equals(oldParam.path("field").asText())) {
          enriched.put("id", oldParam.path("id").asLong());
          enriched.put("monitorId", monitorId);
          break;
        }
      }
      return enriched;
    }).toList();
  }

  private long findMonitorId(String token, String name, String app) throws Exception {
    String query = "?pageIndex=0&pageSize=20&name=" + encode(name) + "&app=" + encode(app);
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/monitors" + query))
        .timeout(Duration.ofSeconds(5))
        .header("Authorization", "Bearer " + token)
        .GET()
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) return 0;
    return findMonitorId(objectMapper.readTree(response.body()), name);
  }

  private long findMonitorId(JsonNode node, String name) {
    if (node.isObject() && name.equals(node.path("name").asText()) && node.path("id").canConvertToLong()) {
      return node.path("id").asLong();
    }
    for (JsonNode child : node) {
      long monitorId = findMonitorId(child, name);
      if (monitorId > 0) return monitorId;
    }
    return 0;
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String login() throws Exception {
    String body = objectMapper.writeValueAsString(Map.of("identifier", username, "credential", password));
    HttpRequest login = HttpRequest.newBuilder(URI.create(baseUrl + "/api/account/auth/form"))
        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    HttpResponse<String> response = httpClient.send(login, HttpResponse.BodyHandlers.ofString());
    JsonNode json = objectMapper.readTree(response.body());
    if (response.statusCode() != 200 || json.path("code").asInt(-1) != 0) return "";
    return json.path("data").path("token").asText("");
  }

  private MonitorTarget monitorTarget(Asset asset) {
    URI endpoint = URI.create(asset.getEndpoint().contains("://") ? asset.getEndpoint() : "tcp://" + asset.getEndpoint());
    String host = endpoint.getHost();
    if (host == null || host.isBlank()) throw new IllegalArgumentException("资产连接地址无法识别");
    if ("HTTP".equalsIgnoreCase(asset.getType())) {
      int port = endpoint.getPort() > 0 ? endpoint.getPort() : "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
      String uri = endpoint.getRawPath() == null || endpoint.getRawPath().isBlank() ? "/" : endpoint.getRawPath();
      boolean ssl = "https".equalsIgnoreCase(endpoint.getScheme());
      return new MonitorTarget("website", host + ":" + port, List.of(
          param("host", host, 1), param("port", String.valueOf(port), 0), param("uri", uri, 1),
          param("ssl", String.valueOf(ssl), 2), param("timeout", "6000", 0)));
    }
    int port = endpoint.getPort() > 0 ? endpoint.getPort() : defaultPort(asset.getType());
    return new MonitorTarget("port", host + ":" + port, List.of(
        param("host", host, 1), param("port", String.valueOf(port), 0), param("timeout", "6000", 0)));
  }

  private Map<String, Object> param(String field, String value, int type) {
    return Map.of("field", field, "paramValue", value, "type", type);
  }

  private int defaultPort(String type) {
    return switch (type.toUpperCase()) {
      case "SERVER" -> 22;
      case "MYSQL" -> 3306;
      case "POSTGRESQL" -> 5432;
      case "REDIS" -> 6379;
      default -> throw new IllegalArgumentException("暂不支持同步该资产类型：" + type);
    };
  }

  private long elapsedMillis(long startedAt) { return Math.max(1, Duration.ofNanos(System.nanoTime() - startedAt).toMillis()); }

  public record Status(boolean connected, String endpoint, long durationMillis, String message) {}
  public record SyncResult(long monitorId, String message) {}
  private record MonitorTarget(String app, String instance, List<Map<String, Object>> params) {}
}
