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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("project", asset.getProjectName());
    labels.put("environment", asset.getEnvironmentName());
    if (asset.getId() != null) labels.put("assetId", String.valueOf(asset.getId()));
    monitor.put("labels", labels);
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

  public MonitorStatus monitorStatus(long monitorId) throws Exception {
    String token = login();
    JsonNode data = getJson(token, "/api/monitor/" + monitorId).path("data");
    JsonNode monitor = data.has("monitor") ? data.path("monitor") : data;
    if (monitor.isMissingNode() || monitor.isNull()) return new MonitorStatus("UNKNOWN", null, false);
    JsonNode runtimeStatus = first(monitor, "monitorStatus", "available", "availability", "state");
    String status = mapRuntimeStatus(runtimeStatus);
    boolean disabled = monitor.path("status").isNumber() && monitor.path("status").asInt() == 0;
    if (disabled) status = "UNKNOWN";
    LocalDateTime collectedAt = parseTime(first(monitor, "lastCollectTime", "lastUpdateTime", "updateTime"));
    if (!disabled && "UNKNOWN".equals(status)) {
      JsonNode metrics = getJson(token, "/api/monitor/" + monitorId + "/metrics/summary").path("data");
      collectedAt = parseTime(metrics.path("time"));
      if (metrics.path("valueRows").isArray() && !metrics.path("valueRows").isEmpty()) status = "UP";
    }
    return new MonitorStatus(status, collectedAt, disabled);
  }

  public List<ExternalAlert> alerts(String status) throws Exception {
    String token = login();
    List<ExternalAlert> results = new ArrayList<>();
    for (int pageIndex = 0; pageIndex < 1000; pageIndex++) {
      String query = "/api/alerts?status=" + URLEncoder.encode(status, StandardCharsets.UTF_8)
          + "&pageIndex=" + pageIndex + "&pageSize=1000";
      JsonNode data = getJson(token, query).path("data");
      JsonNode content = data.has("content") ? data.path("content") : data;
      if (!content.isArray()) throw new IllegalStateException("HertzBeat 告警分页响应缺少 content");
      for (JsonNode item : content) {
        String externalId = text(first(item, "id", "alertId"));
        long monitorId = number(first(item, "monitorId", "targetId", "monitor"));
        if (externalId.isBlank() || monitorId <= 0) continue;
        LocalDateTime startedAt = parseTime(first(item, "firstTriggerTime", "startAt", "occurredAt", "lastTriggerTime"));
        LocalDateTime resolvedAt = parseTime(first(item, "endTime", "resolvedAt", "lastRecoverTime"));
        results.add(new ExternalAlert(externalId, monitorId, text(first(item, "priority", "level", "severity")),
            text(first(item, "content", "message", "name")), startedAt, resolvedAt));
      }
      boolean last = data.path("last").asBoolean(false);
      int totalPages = data.path("totalPages").asInt(-1);
      if (last || totalPages >= 0 && pageIndex + 1 >= totalPages || totalPages < 0 && content.size() < 1000) return results;
    }
    throw new IllegalStateException("HertzBeat 告警分页超过安全上限");
  }

  public MetricResult metrics(long monitorId, String instance, String app) throws Exception {
    String token = login();
    JsonNode current = getJson(token, "/api/monitor/" + monitorId + "/metrics/summary").path("data");
    LocalDateTime collectedAt = parseTime(current.path("time"));
    List<MonitoringMetric> latest = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    if (!current.path("valueRows").isEmpty()) latest.add(MonitoringMetric.availability(true, collectedAt));
    JsonNode fields = current.path("fields");
    JsonNode values = current.path("valueRows").isArray() && !current.path("valueRows").isEmpty()
        ? current.path("valueRows").get(0).path("values") : objectMapper.createArrayNode();
    for (int index = 0; index < Math.min(fields.size(), values.size()); index++) {
      JsonNode field = fields.get(index);
      if (!"responseTime".equals(field.path("name").asText())) continue;
      try {
        double value = Double.parseDouble(values.get(index).path("origin").asText("0"));
        latest.add(MonitoringMetric.duration("responseTime", value, field.path("unit").asText("ms"),
            parseTime(values.get(index).path("time"))));
      } catch (IllegalArgumentException exception) {
        errors.add("responseTime 解析失败：" + exception.getMessage());
      }
    }
    String metricFull = app + ".summary.responseTime";
    String historyPath = "/api/monitor/" + instance + "/metric/" + metricFull + "?history=1h&interval=true";
    List<MonitoringMetric> history = new ArrayList<>();
    JsonNode historyData = getJson(token, historyPath).path("data");
    String unit = historyData.path("field").path("unit").asText("ms");
    JsonNode series = historyData.path("values");
    if (series.isObject()) {
      series.fields().forEachRemaining(entry -> entry.getValue().forEach(value -> {
        try {
          history.add(MonitoringMetric.duration("responseTime", Double.parseDouble(value.path("origin").asText("0")),
              unit, parseTime(value.path("time"))));
        } catch (IllegalArgumentException ignored) {
          // 单项指标解析失败时保留其他有效数据。
        }
      }));
    }
    return new MetricResult(latest, history, collectedAt, errors);
  }

  public MetricTarget metricTarget(Asset asset) {
    MonitorTarget target = monitorTarget(asset);
    return new MetricTarget(target.app(), target.instance());
  }

  public void deleteMonitor(long monitorId) throws Exception {
    String token = login();
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/monitor/" + monitorId))
        .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + token).DELETE().build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 404) return;
    JsonNode json = response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
    if (json.path("msg").asText("").toLowerCase().contains("not exist")) return;
    if (response.statusCode() < 200 || response.statusCode() >= 300 || json.path("code").asInt(-1) != 0) {
      throw new IllegalStateException("HertzBeat 删除监控失败：" + json.path("msg").asText("HTTP " + response.statusCode()));
    }
  }

  private JsonNode getJson(String token, String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(Duration.ofSeconds(8))
        .header("Authorization", "Bearer " + token)
        .GET().build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode json = response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
    if (response.statusCode() == 404 || json.path("msg").asText("").toLowerCase().contains("not exist")) {
      throw new MonitorNotFoundException(path);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300 || json.path("code").asInt(-1) != 0) {
      throw new IllegalStateException("HertzBeat 查询失败：" + json.path("msg").asText("HTTP " + response.statusCode()));
    }
    return json;
  }

  private JsonNode first(JsonNode node, String... fields) {
    for (String field : fields) if (node.hasNonNull(field)) return node.path(field);
    return objectMapper.missingNode();
  }

  private String text(JsonNode node) { return node.isMissingNode() ? "" : node.asText(""); }
  private long number(JsonNode node) { return node.isNumber() ? node.asLong() : parseLong(node.asText("")); }
  private long parseLong(String value) { try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0; } }

  private String mapRuntimeStatus(JsonNode node) {
    if (node.isBoolean()) return node.asBoolean() ? "UP" : "DOWN";
    String value = node.asText("").toUpperCase();
    return switch (value) {
      case "UP", "AVAILABLE", "HEALTHY", "SUCCESS", "TRUE" -> "UP";
      case "DOWN", "UNAVAILABLE", "CRITICAL", "FAILED", "FALSE" -> "DOWN";
      default -> "UNKNOWN";
    };
  }

  private LocalDateTime parseTime(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return null;
    if (node.isNumber()) {
      long value = node.asLong();
      if (value < 10_000_000_000L) value *= 1000;
      return LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneId.systemDefault());
    }
    String value = node.asText("");
    if (value.isBlank()) return null;
    try { return LocalDateTime.parse(value); }
    catch (DateTimeParseException ignored) {
      try { return LocalDateTime.ofInstant(Instant.parse(value), ZoneId.systemDefault()); }
      catch (DateTimeParseException ignoredAgain) { return null; }
    }
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
  public record MonitorStatus(String status, LocalDateTime collectedAt, boolean disabled) {}
  public record ExternalAlert(String externalId, long monitorId, String level, String message,
                              LocalDateTime occurredAt, LocalDateTime resolvedAt) {}
  public record MetricResult(List<MonitoringMetric> latest, List<MonitoringMetric> history,
                             LocalDateTime collectedAt, List<String> errors) {}
  public record MetricTarget(String app, String instance) {}
  private record MonitorTarget(String app, String instance, List<Map<String, Object>> params) {}

  public static class MonitorNotFoundException extends IllegalStateException {
    public MonitorNotFoundException(long monitorId) { super("HertzBeat 监控不存在：" + monitorId); }
    private MonitorNotFoundException(String path) { super("HertzBeat 监控不存在：" + path); }
  }
}
