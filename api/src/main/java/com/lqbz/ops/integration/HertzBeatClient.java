package com.lqbz.ops.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqbz.ops.domain.Asset;
import java.net.URI;
import java.net.URLEncoder;
import com.lqbz.ops.compat.http.HttpClient;
import com.lqbz.ops.compat.http.HttpRequest;
import com.lqbz.ops.compat.http.HttpResponse;
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

    // 仅缓存已成功写入的凭据指纹；重启后首次校准重新写入一次，不保存明文或导出密文。
    private final java.util.concurrent.ConcurrentHashMap<Long, String> writtenCredentialHashes = new java.util.concurrent.ConcurrentHashMap<>();

    public HertzBeatClient(ObjectMapper objectMapper, @Value("${ops.hertzbeat.url:http://hertzbeat:1157}") String baseUrl, @Value("${ops.hertzbeat.username:admin}") String username, @Value("${ops.hertzbeat.password:hertzbeat}") String password) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lqbz.ops.inspection.InspectionProfileService inspectionProfiles;

    public Status status() {
        long startedAt = System.nanoTime();
        try {
            boolean connected = !com.lqbz.ops.compat.Java8.isBlank(login());
            return new Status(connected, baseUrl, elapsedMillis(startedAt), connected ? "认证连接正常" : "认证失败");
        } catch (Exception exception) {
            return new Status(false, baseUrl, elapsedMillis(startedAt), "连接失败：" + exception.getClass().getSimpleName());
        }
    }

    public SyncResult sync(Asset asset) throws Exception {
        return syncMonitor(asset, 60, true);
    }

    public SyncResult syncMonitor(Asset asset, int intervalSeconds, boolean enabled) throws Exception {
        if (intervalSeconds < 10)
            throw new IllegalArgumentException("HertzBeat 固定采集间隔不能小于 10 秒");
        String token = login();
        if (com.lqbz.ops.compat.Java8.isBlank(token))
            throw new IllegalStateException("HertzBeat 认证失败");
        MonitorTarget target = monitorTarget(asset);
        Long existingMonitorId = asset.getHertzbeatMonitorId();
        if (existingMonitorId == null && asset.getId() != null)
            existingMonitorId = findMonitorByAssetId(token, asset.getId());
        JsonNode existing = existingMonitorId == null ? null : getMonitorDetails(token, existingMonitorId);
        Map<String, Object> monitor = new LinkedHashMap<>();
        if (existingMonitorId != null) {
            monitor.put("id", existingMonitorId);
            long jobId = existing.path("monitor").path("jobId").asLong(0);
            if (jobId > 0)
                monitor.put("jobId", jobId);
        }
        monitor.put("name", asset.getName());
        monitor.put("app", target.app());
        monitor.put("instance", target.instance());
        monitor.put("intervals", intervalSeconds);
        monitor.put("scheduleType", "fixed");
        monitor.put("status", enabled ? 1 : 0);
        monitor.put("type", 0);
        monitor.put("description", asset.getProjectName() + " / " + asset.getEnvironmentName());
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("managedBy", "ops-platform");
        labels.put("project", asset.getProjectName());
        labels.put("environment", asset.getEnvironmentName());
        if (asset.getId() != null)
            labels.put("assetId", String.valueOf(asset.getId()));
        monitor.put("labels", labels);
        Map<String, Object> body = com.lqbz.ops.compat.Java8.map("monitor", monitor, "params", enrichParams(target.params(), existingMonitorId, existing));
        URI requestUri = URI.create(baseUrl + "/api/monitor");
        HttpRequest.BodyPublisher requestBody = HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body));
        HttpRequest request = HttpRequest.newBuilder(requestUri).timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json").header("Authorization", "Bearer " + token).method(existingMonitorId == null ? "POST" : "PUT", requestBody).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = objectMapper.readTree(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300 || json.path("code").asInt(-1) != 0) {
            String action = existingMonitorId == null ? "创建" : "更新";
            throw new IllegalStateException("HertzBeat " + action + "监控失败：" + json.path("msg").asText("HTTP " + response.statusCode()));
        }
        long monitorId = existingMonitorId == null ? json.path("data").asLong(0) : existingMonitorId;
        if (monitorId <= 0 && existingMonitorId == null) {
            Long recovered = asset.getId() == null ? null : findMonitorByAssetId(token, asset.getId());
            monitorId = asset.getId() == null ? findMonitorId(token, asset.getName(), target.app()) : recovered == null ? 0 : recovered;
        }
        if (monitorId <= 0)
            throw new IllegalStateException("HertzBeat 未返回监控 ID");
        String credentialHash = credentialFingerprint(asset, monitorId);
        if (credentialHash == null)
            writtenCredentialHashes.remove(monitorId);
        else
            writtenCredentialHashes.put(monitorId, credentialHash);
        return new SyncResult(monitorId, "同步成功");
    }

    public AlertRule upsertAlertRule(AlertRuleSpec spec) throws Exception {
        if (spec.period() == null || spec.period() < 1)
            throw new IllegalArgumentException("告警执行周期必须大于 0 秒");
        if (spec.times() == null || spec.times() < 1)
            throw new IllegalArgumentException("连续失败次数必须大于 0");
        String token = login();
        if (com.lqbz.ops.compat.Java8.isBlank(token))
            throw new IllegalStateException("HertzBeat 认证失败");
        AlertRule managed = spec.id() == null ? findManagedAlertRule(token, spec.planId(), spec.assetId()) : null;
        Long ruleId = spec.id();
        if (ruleId == null && managed != null)
            ruleId = managed.id();
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("managedBy", "ops-platform");
        labels.put("severity", spec.level().toLowerCase());
        labels.put("planId", String.valueOf(spec.planId()));
        labels.put("assetId", String.valueOf(spec.assetId()));
        Map<String, Object> body = new LinkedHashMap<>();
        if (ruleId != null)
            body.put("id", ruleId);
        body.put("name", spec.name());
        body.put("type", "realtime_metric");
        body.put("expr", spec.expression());
        body.put("period", spec.period());
        body.put("times", spec.times());
        body.put("labels", labels);
        body.put("annotations", com.lqbz.ops.compat.Java8.map("summary", spec.name()));
        body.put("template", "");
        body.put("datasource", "hertzbeat");
        body.put("enable", spec.enable());
        JsonNode response = sendJson(token, "/api/alert/define", ruleId == null ? "POST" : "PUT", body);
        if (response.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("HertzBeat 同步告警规则失败：" + response.path("msg").asText("未知错误"));
        }
        return ruleId == null ? requireManagedAlertRule(token, spec.planId(), spec.assetId()) : getAlertRule(token, ruleId);
    }

    public AlertRule getAlertRule(long ruleId) throws Exception {
        return getAlertRule(login(), ruleId);
    }

    public void deleteAlertRule(long ruleId) throws Exception {
        String token = login();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/alert/define/" + ruleId)).timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + token).DELETE().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = com.lqbz.ops.compat.Java8.isBlank(response.body()) ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
        if (response.statusCode() == 404 || json.path("code").asInt(-1) == 3 || json.path("msg").asText("").toLowerCase().contains("not exist"))
            return;
        if (response.statusCode() < 200 || response.statusCode() >= 300 || json.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("HertzBeat 删除告警规则失败：" + json.path("msg").asText("HTTP " + response.statusCode()));
        }
    }

    public MonitorStatus monitorStatus(long monitorId) throws Exception {
        String token = login();
        JsonNode data = getMonitorDetails(token, monitorId);
        JsonNode monitor = data.has("monitor") ? data.path("monitor") : data;
        if (monitor.isMissingNode() || monitor.isNull())
            return new MonitorStatus("UNKNOWN", null, false);
        JsonNode runtimeStatus = first(monitor, "monitorStatus", "available", "availability", "state");
        String status = mapRuntimeStatus(runtimeStatus);
        if ("UNKNOWN".equals(status) && monitor.path("status").isNumber()) {
            status = com.lqbz.ops.compat.Java8.eval(() -> {
                switch(monitor.path("status").asInt()) {
                    case 1:
                        return "UP";
                    case 2:
                        return "DOWN";
                    default:
                        return "UNKNOWN";
                }
            });
        }
        boolean disabled = monitor.path("status").isNumber() && monitor.path("status").asInt() == 0;
        if (disabled)
            status = "UNKNOWN";
        LocalDateTime collectedAt = parseTime(first(monitor, "lastCollectTime", "lastUpdateTime", "updateTime"));
        if (!disabled) {
            JsonNode metrics = getJson(token, "/api/monitor/" + monitorId + "/metrics/summary").path("data");
            collectedAt = parseTime(metrics.path("time"));
            if ("UNKNOWN".equals(status) && metrics.path("valueRows").isArray() && !metrics.path("valueRows").isEmpty())
                status = "UP";
        }
        return new MonitorStatus(status, collectedAt, disabled);
    }

    public MonitorConfig getMonitorConfig(long monitorId) throws Exception {
        JsonNode data = getMonitorDetails(login(), monitorId);
        JsonNode monitor = data.has("monitor") ? data.path("monitor") : data;
        return new MonitorConfig(monitor.path("id").asLong(), monitor.path("name").asText(), monitor.path("app").asText(), monitor.path("instance").asText(), monitor.path("intervals").asInt(), monitor.path("scheduleType").asText(), monitor.path("status").asInt() != 0, stringMap(monitor.path("labels")), objectList(data.path("params")));
    }

    public List<ExternalAlert> alerts(String status) throws Exception {
        String token = login();
        List<ExternalAlert> results = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < 1000; pageIndex++) {
            String query = "/api/alerts?status=" + org.springframework.web.util.UriUtils.encodeQueryParam(status, StandardCharsets.UTF_8) + "&pageIndex=" + pageIndex + "&pageSize=1000";
            JsonNode data = getJson(token, query).path("data");
            JsonNode content = data.has("content") ? data.path("content") : data;
            if (!content.isArray())
                throw new IllegalStateException("HertzBeat 告警分页响应缺少 content");
            for (JsonNode item : content) {
                String externalId = text(first(item, "id", "alertId"));
                long monitorId = number(first(item, "monitorId", "targetId", "monitor"));
                JsonNode labels = item.path("labels");
                if (monitorId <= 0)
                    monitorId = number(first(labels, "monitorId", "monitor_id"));
                long assetId = number(labels.path("assetId"));
                if (com.lqbz.ops.compat.Java8.isBlank(externalId) || monitorId <= 0 && assetId <= 0)
                    continue;
                LocalDateTime startedAt = parseTime(first(item, "firstTriggerTime", "startAt", "occurredAt", "lastTriggerTime"));
                LocalDateTime resolvedAt = parseTime(first(item, "endAt", "endTime", "resolvedAt", "lastRecoverTime"));
                String level = text(first(item, "priority", "level", "severity"));
                if (com.lqbz.ops.compat.Java8.isBlank(level))
                    level = text(first(labels, "severity", "priority", "level"));
                results.add(new ExternalAlert(externalId, monitorId, level, alertMessage(item), startedAt, resolvedAt, assetId > 0 ? assetId : null));
            }
            boolean last = data.path("last").asBoolean(false);
            int totalPages = data.path("totalPages").asInt(-1);
            if (last || totalPages >= 0 && pageIndex + 1 >= totalPages || totalPages < 0 && content.size() < 1000)
                return results;
        }
        throw new IllegalStateException("HertzBeat 告警分页超过安全上限");
    }

    public MetricResult metrics(long monitorId, String instance, String app) throws Exception {
        String token = login();
        JsonNode current = getJson(token, "/api/monitor/" + monitorId + "/metrics/summary").path("data");
        LocalDateTime collectedAt = parseTime(current.path("time"));
        List<MonitoringMetric> latest = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (!current.path("valueRows").isEmpty())
            latest.add(MonitoringMetric.availability(true, collectedAt));
        JsonNode fields = current.path("fields");
        JsonNode values = current.path("valueRows").isArray() && !current.path("valueRows").isEmpty() ? current.path("valueRows").get(0).path("values") : objectMapper.createArrayNode();
        for (int index = 0; index < Math.min(fields.size(), values.size()); index++) {
            JsonNode field = fields.get(index);
            if (!"responseTime".equals(field.path("name").asText()))
                continue;
            try {
                double value = Double.parseDouble(values.get(index).path("origin").asText("0"));
                latest.add(MonitoringMetric.duration("responseTime", value, field.path("unit").asText("ms"), parseTime(values.get(index).path("time"))));
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
                    history.add(MonitoringMetric.duration("responseTime", Double.parseDouble(value.path("origin").asText("0")), unit, parseTime(value.path("time"))));
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

    public List<Map<String, Object>> monitorParameters(Asset asset) {
        return monitorTarget(asset).params();
    }

    public boolean monitorParametersMatch(Asset asset, List<Map<String, Object>> actual) {
        if (actual == null)
            return false;
        for (Map<String, Object> expected : monitorParameters(asset)) {
            List<Map<String, Object>> matching = actual.stream().filter(parameter -> expected.get("field").equals(parameter.get("field"))).collect(java.util.stream.Collectors.toList());
            if (matching.size() != 1)
                return false;
            String value = String.valueOf(matching.get(0).get("paramValue"));
            // HertzBeat只读API将密码固定遮罩；版本由用户名核验，实际密码由每次采集认证验证。
            boolean maskedPassword = "password".equals(expected.get("field")) && Integer.valueOf(2).equals(expected.get("type")) && "******".equals(value);
            if (!maskedPassword && !String.valueOf(expected.get("paramValue")).equals(value))
                return false;
        }
        return true;
    }

    public boolean monitorCredentialsMatch(Asset asset, long monitorId) throws Exception {
        String actual = credentialFingerprint(asset, monitorId);
        return actual == null || actual.equals(writtenCredentialHashes.get(monitorId));
    }

    private String credentialFingerprint(Asset asset, long monitorId) throws Exception {
        List<Map<String, Object>> expected = monitorParameters(asset).stream().filter(parameter -> Integer.valueOf(2).equals(parameter.get("type"))).collect(java.util.stream.Collectors.toList());
        if (expected.isEmpty())
            return null;
        // 官方单Monitor导出保留稳定的密文；将其与期望凭据一起散列，仅保留指纹。
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/monitors/export?ids=" + monitorId + "&type=JSON")).timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + login()).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new IllegalStateException();
            JsonNode export = objectMapper.readTree(response.body());
            if (!export.isArray() || export.size() != 1 || !asset.getName().equals(export.get(0).path("monitor").path("name").asText()))
                throw new IllegalStateException();
            List<Map<String, Object>> params = objectList(export.get(0).path("params"));
            lombok.val digest = java.security.MessageDigest.getInstance("SHA-256");
            for (lombok.val parameter : expected) {
                lombok.val matches = params.stream().filter(actual -> parameter.get("field").equals(actual.get("field"))).collect(java.util.stream.Collectors.toList());
                if (matches.size() != 1 || matches.get(0).get("value") == null || "******".equals(matches.get(0).get("value")))
                    throw new IllegalStateException();
                for (Object part : com.lqbz.ops.compat.Java8.list(parameter.get("field"), parameter.get("paramValue"), matches.get(0).get("value"))) {
                    byte[] bytes = String.valueOf(part).getBytes(StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return com.lqbz.ops.compat.Java8.hex(digest.digest());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HertzBeat凭据校验已中断");
        } catch (Exception exception) {
            // 解析异常可能包含敏感原始响应，禁止沿异常链向外传播。
            throw new IllegalStateException("HertzBeat凭据校验失败，请核对单Monitor导出权限与版本");
        }
    }

    public void deleteMonitor(long monitorId) throws Exception {
        writtenCredentialHashes.remove(monitorId);
        String token = login();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/monitor/" + monitorId)).timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + token).DELETE().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404)
            return;
        JsonNode json = com.lqbz.ops.compat.Java8.isBlank(response.body()) ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
        if (json.path("msg").asText("").toLowerCase().contains("not exist"))
            return;
        if (response.statusCode() < 200 || response.statusCode() >= 300 || json.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("HertzBeat 删除监控失败：" + json.path("msg").asText("HTTP " + response.statusCode()));
        }
    }

    private JsonNode getJson(String token, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = com.lqbz.ops.compat.Java8.isBlank(response.body()) ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
        if (response.statusCode() == 404 || json.path("msg").asText("").toLowerCase().contains("not exist")) {
            throw new MonitorNotFoundException(path);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300 || json.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("HertzBeat 查询失败：" + json.path("msg").asText("HTTP " + response.statusCode()));
        }
        return json;
    }

    private JsonNode sendJson(String token, String path, String method, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json").header("Authorization", "Bearer " + token).method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = com.lqbz.ops.compat.Java8.isBlank(response.body()) ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HertzBeat 请求失败：" + json.path("msg").asText("HTTP " + response.statusCode()));
        }
        return json;
    }

    private AlertRule findManagedAlertRule(String token, Long planId, Long assetId) throws Exception {
        List<AlertRule> matches = new ArrayList<>();
        int pageIndex = 0;
        while (true) {
            JsonNode data = getJson(token, "/api/alert/defines?pageIndex=" + pageIndex + "&pageSize=100").path("data");
            JsonNode content = data.path("content");
            if (!content.isArray())
                throw new IllegalStateException("HertzBeat 告警规则列表响应缺少 content");
            for (JsonNode item : content) {
                JsonNode labels = item.path("labels");
                if ("ops-platform".equals(labels.path("managedBy").asText()) && String.valueOf(planId).equals(labels.path("planId").asText()) && String.valueOf(assetId).equals(labels.path("assetId").asText())) {
                    matches.add(alertRule(item));
                }
            }
            boolean last = data.path("last").asBoolean(true);
            int totalPages = data.path("totalPages").asInt(pageIndex + 1);
            if (last || pageIndex + 1 >= totalPages)
                break;
            pageIndex++;
        }
        if (matches.size() > 1)
            throw new IllegalStateException("HertzBeat 存在重复受管告警规则");
        return matches.isEmpty() ? null : matches.get(0);
    }

    private AlertRule requireManagedAlertRule(String token, Long planId, Long assetId) throws Exception {
        AlertRule rule = findManagedAlertRule(token, planId, assetId);
        if (rule == null)
            throw new AlertRuleNotFoundException(planId + ":" + assetId);
        return rule;
    }

    private AlertRule getAlertRule(String token, long ruleId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/alert/define/" + ruleId)).timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = com.lqbz.ops.compat.Java8.isBlank(response.body()) ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
        if (response.statusCode() == 404 || json.path("code").asInt(-1) == 3 || json.path("msg").asText("").toLowerCase().contains("not exist")) {
            throw new AlertRuleNotFoundException(String.valueOf(ruleId));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300 || json.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("HertzBeat 查询告警规则失败：" + json.path("msg").asText("HTTP " + response.statusCode()));
        }
        return alertRule(json.path("data"));
    }

    private AlertRule alertRule(JsonNode node) {
        return new AlertRule(node.path("id").asLong(), node.path("name").asText(), node.path("type").asText(), node.path("expr").asText(), node.path("period").asInt(), node.path("times").asInt(), node.path("enable").asBoolean(), node.path("datasource").asText(), stringMap(node.path("labels")), stringMap(node.path("annotations")));
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node.isObject())
            node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return values;
    }

    private List<Map<String, Object>> objectList(JsonNode node) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (node.isArray())
            node.forEach(item -> {
                Map<String, Object> value = new LinkedHashMap<>();
                item.fields().forEachRemaining(entry -> value.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class)));
                values.add(value);
            });
        return values;
    }

    private String alertMessage(JsonNode item) {
        boolean availabilityRule = false;
        for (JsonNode candidate : com.lqbz.ops.compat.Java8.list(item.path("content"), item.path("message"), item.path("annotations").path("description"), item.path("annotations").path("summary"), item.path("name"))) {
            String value = candidate.asText("").trim();
            if (com.lqbz.ops.compat.Java8.isBlank(value))
                continue;
            // 自动生成的规则标识不是故障详情；保留可用性语义，不猜测具体失败原因。
            if (value.matches("ops-plan-\\d+-asset-\\d+")) {
                availabilityRule = true;
                continue;
            }
            return value;
        }
        return availabilityRule ? "服务可用性检查失败" : "监控源未提供告警详情";
    }

    private JsonNode first(JsonNode node, String... fields) {
        for (String field : fields) if (node.hasNonNull(field))
            return node.path(field);
        return objectMapper.missingNode();
    }

    private String text(JsonNode node) {
        return node.isMissingNode() ? "" : node.asText("");
    }

    private long number(JsonNode node) {
        return node.isNumber() ? node.asLong() : parseLong(node.asText(""));
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String mapRuntimeStatus(JsonNode node) {
        if (node.isBoolean())
            return node.asBoolean() ? "UP" : "DOWN";
        String value = node.asText("").toUpperCase();
        return com.lqbz.ops.compat.Java8.eval(() -> {
            switch(value) {
                case "UP":
                case "AVAILABLE":
                case "HEALTHY":
                case "SUCCESS":
                case "TRUE":
                    return "UP";
                case "DOWN":
                case "UNAVAILABLE":
                case "CRITICAL":
                case "FAILED":
                case "FALSE":
                    return "DOWN";
                default:
                    return "UNKNOWN";
            }
        });
    }

    private LocalDateTime parseTime(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull())
            return null;
        if (node.isNumber()) {
            long value = node.asLong();
            if (value < 10_000_000_000L)
                value *= 1000;
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneId.systemDefault());
        }
        String value = node.asText("");
        if (com.lqbz.ops.compat.Java8.isBlank(value))
            return null;
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.ofInstant(Instant.parse(value), ZoneId.systemDefault());
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private JsonNode getMonitorDetails(String token, long monitorId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/monitor/" + monitorId)).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = com.lqbz.ops.compat.Java8.isBlank(response.body()) ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
        if (response.statusCode() == 404 || json.path("code").asInt(-1) == 3 || json.path("msg").asText("").toLowerCase().contains("not exist")) {
            throw new MonitorNotFoundException(monitorId);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300 || json.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("HertzBeat 查询监控详情失败：" + json.path("msg").asText("HTTP " + response.statusCode()));
        }
        return json.path("data");
    }

    private List<Map<String, Object>> enrichParams(List<Map<String, Object>> params, Long monitorId, JsonNode existing) {
        if (monitorId == null || existing == null)
            return params;
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
        }).collect(java.util.stream.Collectors.toList());
    }

    private Long findMonitorByAssetId(String token, Long assetId) throws Exception {
        Long matchedId = null;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        Integer expectedPages = null;
        Long expectedElements = null;
        Integer expectedPageSize = null;
        java.util.Set<String> expectedMetadata = null;
        for (int pageIndex = 0; pageIndex < 100; pageIndex++) {
            JsonNode data = getJson(token, "/api/monitors?pageIndex=" + pageIndex + "&pageSize=100").path("data");
            JsonNode content = data.path("content");
            if (!content.isArray())
                throw new IllegalStateException("HertzBeat 监控列表响应缺少 content，禁止创建");
            java.util.Set<String> metadata = new java.util.HashSet<>();
            for (String field : com.lqbz.ops.compat.Java8.list("totalPages", "totalElements", "size", "last", "number", "numberOfElements", "first", "empty")) {
                if (data.has(field))
                    metadata.add(field);
            }
            if (expectedMetadata != null && !expectedMetadata.equals(metadata)) {
                throw new IllegalStateException("HertzBeat 监控列表跨页元数据契约发生变化");
            }
            expectedMetadata = metadata;
            if (data.has("first") && (!data.path("first").isBoolean() || data.path("first").asBoolean() != (pageIndex == 0)) || data.has("empty") && (!data.path("empty").isBoolean() || data.path("empty").asBoolean() != content.isEmpty())) {
                throw new IllegalStateException("HertzBeat 监控列表首页或空页标识矛盾");
            }
            if (data.has("number") && (!data.path("number").isIntegralNumber() || !data.path("number").canConvertToInt() || data.path("number").asInt(-1) != pageIndex)) {
                throw new IllegalStateException("HertzBeat 监控列表页码不一致");
            }
            if (data.has("numberOfElements") && (!data.path("numberOfElements").isIntegralNumber() || !data.path("numberOfElements").canConvertToLong() || data.path("numberOfElements").asLong(-1) != content.size())) {
                throw new IllegalStateException("HertzBeat 监控列表当前页数量不一致");
            }
            if (data.has("size")) {
                int size = data.path("size").asInt(-1);
                if (!data.path("size").isIntegralNumber() || !data.path("size").canConvertToInt() || size < 1 || size > 100 || content.size() > size || expectedPageSize != null && expectedPageSize != size) {
                    throw new IllegalStateException("HertzBeat 监控列表分页大小不一致");
                }
                expectedPageSize = size;
            } else if (expectedPageSize != null)
                throw new IllegalStateException("HertzBeat 监控列表缺少分页大小");
            if (data.has("totalElements")) {
                long total = data.path("totalElements").asLong(-1);
                if (!data.path("totalElements").isIntegralNumber() || !data.path("totalElements").canConvertToLong() || total < 0 || expectedElements != null && expectedElements != total) {
                    throw new IllegalStateException("HertzBeat 监控列表总数发生变化");
                }
                expectedElements = total;
            } else if (expectedElements != null)
                throw new IllegalStateException("HertzBeat 监控列表缺少总数");
            for (JsonNode monitor : content) {
                long monitorId = monitor.path("id").asLong(0);
                if (monitorId <= 0 || !seen.add(monitorId))
                    throw new IllegalStateException("HertzBeat 监控列表含无效或重复 ID");
                JsonNode labels = monitor.path("labels");
                if (!String.valueOf(assetId).equals(labels.path("assetId").asText()))
                    continue;
                String owner = labels.path("managedBy").asText("");
                if (!com.lqbz.ops.compat.Java8.isBlank(owner) && !"ops-platform".equals(owner))
                    throw new IllegalStateException("HertzBeat 服务标签已属于其他管理端");
                if (matchedId != null)
                    throw new IllegalStateException("HertzBeat 存在重复服务监控标签，禁止认领或创建");
                matchedId = monitorId;
            }
            boolean complete;
            if (data.has("totalPages")) {
                int totalPages = data.path("totalPages").asInt();
                if (!data.path("totalPages").isIntegralNumber() || !data.path("totalPages").canConvertToInt() || totalPages < 0 || totalPages > 100)
                    throw new IllegalStateException("HertzBeat 监控列表分页超出安全查询范围");
                if (expectedPages != null && expectedPages != totalPages)
                    throw new IllegalStateException("HertzBeat 监控列表总页数发生变化");
                expectedPages = totalPages;
                if (totalPages == 0 && (pageIndex != 0 || !seen.isEmpty()) || totalPages > 0 && pageIndex >= totalPages) {
                    throw new IllegalStateException("HertzBeat 监控列表总页数落后于已读取范围");
                }
                complete = pageIndex + 1 >= totalPages;
                if (data.has("last") && (!data.path("last").isBoolean() || data.path("last").asBoolean() != complete)) {
                    throw new IllegalStateException("HertzBeat 监控列表末页标识矛盾");
                }
            } else if (data.path("last").isBoolean()) {
                if (expectedPages != null)
                    throw new IllegalStateException("HertzBeat 监控列表缺少总页数");
                complete = data.path("last").asBoolean();
            } else {
                throw new IllegalStateException("HertzBeat 监控列表缺少分页完成标识，禁止创建");
            }
            if (expectedElements != null && expectedPages != null) {
                int pageSize = expectedPageSize == null ? 100 : expectedPageSize;
                long calculatedPages = expectedElements / pageSize + (expectedElements % pageSize == 0 ? 0 : 1);
                if (calculatedPages != expectedPages)
                    throw new IllegalStateException("HertzBeat 监控列表页数与总数不一致");
            }
            if (expectedElements != null && (seen.size() > expectedElements || complete && seen.size() != expectedElements)) {
                throw new IllegalStateException("HertzBeat 监控列表已读数量与总数不一致");
            }
            if (content.isEmpty() && (pageIndex > 0 || !complete))
                throw new IllegalStateException("HertzBeat 监控列表后续页异常为空");
            if (complete)
                return matchedId;
        }
        throw new IllegalStateException("HertzBeat 监控列表超过100页，无法证明唯一归属");
    }

    private long findMonitorId(String token, String name, String app) throws Exception {
        String query = "?pageIndex=0&pageSize=20&name=" + encode(name) + "&app=" + encode(app);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/monitors" + query)).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            return 0;
        return findMonitorId(objectMapper.readTree(response.body()), name);
    }

    private long findMonitorId(JsonNode node, String name) {
        if (node.isObject() && name.equals(node.path("name").asText()) && node.path("id").canConvertToLong()) {
            return node.path("id").asLong();
        }
        for (JsonNode child : node) {
            long monitorId = findMonitorId(child, name);
            if (monitorId > 0)
                return monitorId;
        }
        return 0;
    }

    private String encode(String value) {
        return org.springframework.web.util.UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8);
    }

    private String login() throws Exception {
        String body = objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("identifier", username, "credential", password));
        HttpRequest login = HttpRequest.newBuilder(URI.create(baseUrl + "/api/account/auth/form")).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = httpClient.send(login, HttpResponse.BodyHandlers.ofString());
        JsonNode json = objectMapper.readTree(response.body());
        if (response.statusCode() != 200 || json.path("code").asInt(-1) != 0)
            return "";
        return json.path("data").path("token").asText("");
    }

    private MonitorTarget monitorTarget(Asset asset) {
        if (inspectionProfiles != null && asset.getId() != null) {
            com.lqbz.ops.inspection.InspectionProfileService.Bridge bridge = inspectionProfiles.bridge(asset);
            if (bridge != null) {
                URI uri = bridge.uri();
                int port = uri.getPort() > 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
                return new MonitorTarget("website", "inspection-" + asset.getId() + ":" + port, com.lqbz.ops.compat.Java8.list(param("host", uri.getHost(), 1), param("port", String.valueOf(port), 0), param("uri", uri.getRawPath(), 1), param("ssl", String.valueOf("https".equalsIgnoreCase(uri.getScheme())), 1), param("timeout", "45000", 0), param("authType", "Basic Auth", 1), param("username", bridge.username(), 1), param("password", bridge.password(), 2)));
            }
        }
        com.lqbz.ops.domain.MonitoringServiceContract contract = com.lqbz.ops.domain.MonitoringServiceContract.validate(asset.getType(), asset.getEndpoint());
        URI endpoint = URI.create(asset.getEndpoint().contains("://") ? asset.getEndpoint() : "tcp://" + asset.getEndpoint());
        String host = endpoint.getHost();
        if (host == null || com.lqbz.ops.compat.Java8.isBlank(host))
            throw new IllegalArgumentException("服务连接地址无法识别");
        if ("HTTP".equalsIgnoreCase(asset.getType())) {
            int port = endpoint.getPort() > 0 ? endpoint.getPort() : "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
            String uri = endpoint.getRawPath() == null || com.lqbz.ops.compat.Java8.isBlank(endpoint.getRawPath()) ? "/" : endpoint.getRawPath();
            if (endpoint.getRawQuery() != null)
                uri += "?" + endpoint.getRawQuery();
            boolean ssl = "https".equalsIgnoreCase(endpoint.getScheme());
            return new MonitorTarget("website", host + ":" + port, com.lqbz.ops.compat.Java8.list(param("host", host, 1), param("port", String.valueOf(port), 0), param("uri", uri, 1), param("ssl", String.valueOf(ssl), 1), param("timeout", "6000", 0)));
        }
        int port = endpoint.getPort() > 0 ? endpoint.getPort() : contract.defaultPort();
        return new MonitorTarget("port", host + ":" + port, com.lqbz.ops.compat.Java8.list(param("host", host, 1), param("port", String.valueOf(port), 0), param("timeout", "6000", 0)));
    }

    private Map<String, Object> param(String field, String value, int type) {
        return com.lqbz.ops.compat.Java8.map("field", field, "paramValue", value, "type", type);
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(1, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class Status {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public Status(@com.fasterxml.jackson.annotation.JsonProperty("connected") boolean connected, @com.fasterxml.jackson.annotation.JsonProperty("endpoint") String endpoint, @com.fasterxml.jackson.annotation.JsonProperty("durationMillis") long durationMillis, @com.fasterxml.jackson.annotation.JsonProperty("message") String message) {
            this.connected = connected;
            this.endpoint = endpoint;
            this.durationMillis = durationMillis;
            this.message = message;
        }

        private final boolean connected;

        public boolean connected() {
            return this.connected;
        }

        private final String endpoint;

        public String endpoint() {
            return this.endpoint;
        }

        private final long durationMillis;

        public long durationMillis() {
            return this.durationMillis;
        }

        private final String message;

        public String message() {
            return this.message;
        }
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class SyncResult {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public SyncResult(@com.fasterxml.jackson.annotation.JsonProperty("monitorId") long monitorId, @com.fasterxml.jackson.annotation.JsonProperty("message") String message) {
            this.monitorId = monitorId;
            this.message = message;
        }

        private final long monitorId;

        public long monitorId() {
            return this.monitorId;
        }

        private final String message;

        public String message() {
            return this.message;
        }
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class AlertRuleSpec {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public AlertRuleSpec(@com.fasterxml.jackson.annotation.JsonProperty("id") Long id, @com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("expression") String expression, @com.fasterxml.jackson.annotation.JsonProperty("period") Integer period, @com.fasterxml.jackson.annotation.JsonProperty("times") Integer times, @com.fasterxml.jackson.annotation.JsonProperty("level") String level, @com.fasterxml.jackson.annotation.JsonProperty("planId") Long planId, @com.fasterxml.jackson.annotation.JsonProperty("assetId") Long assetId, @com.fasterxml.jackson.annotation.JsonProperty("enable") boolean enable) {
            this.id = id;
            this.name = name;
            this.expression = expression;
            this.period = period;
            this.times = times;
            this.level = level;
            this.planId = planId;
            this.assetId = assetId;
            this.enable = enable;
        }

        private final Long id;

        public Long id() {
            return this.id;
        }

        private final String name;

        public String name() {
            return this.name;
        }

        private final String expression;

        public String expression() {
            return this.expression;
        }

        private final Integer period;

        public Integer period() {
            return this.period;
        }

        private final Integer times;

        public Integer times() {
            return this.times;
        }

        private final String level;

        public String level() {
            return this.level;
        }

        private final Long planId;

        public Long planId() {
            return this.planId;
        }

        private final Long assetId;

        public Long assetId() {
            return this.assetId;
        }

        private final boolean enable;

        public boolean enable() {
            return this.enable;
        }
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class AlertRule {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public AlertRule(@com.fasterxml.jackson.annotation.JsonProperty("id") long id, @com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("type") String type, @com.fasterxml.jackson.annotation.JsonProperty("expression") String expression, @com.fasterxml.jackson.annotation.JsonProperty("period") int period, @com.fasterxml.jackson.annotation.JsonProperty("times") int times, @com.fasterxml.jackson.annotation.JsonProperty("enable") boolean enable, @com.fasterxml.jackson.annotation.JsonProperty("datasource") String datasource, @com.fasterxml.jackson.annotation.JsonProperty("labels") Map<String, String> labels, @com.fasterxml.jackson.annotation.JsonProperty("annotations") Map<String, String> annotations) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.expression = expression;
            this.period = period;
            this.times = times;
            this.enable = enable;
            this.datasource = datasource;
            this.labels = labels;
            this.annotations = annotations;
        }

        private final long id;

        public long id() {
            return this.id;
        }

        private final String name;

        public String name() {
            return this.name;
        }

        private final String type;

        public String type() {
            return this.type;
        }

        private final String expression;

        public String expression() {
            return this.expression;
        }

        private final int period;

        public int period() {
            return this.period;
        }

        private final int times;

        public int times() {
            return this.times;
        }

        private final boolean enable;

        public boolean enable() {
            return this.enable;
        }

        private final String datasource;

        public String datasource() {
            return this.datasource;
        }

        private final Map<String, String> labels;

        public Map<String, String> labels() {
            return this.labels;
        }

        private final Map<String, String> annotations;

        public Map<String, String> annotations() {
            return this.annotations;
        }
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class MonitorStatus {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public MonitorStatus(@com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("collectedAt") LocalDateTime collectedAt, @com.fasterxml.jackson.annotation.JsonProperty("disabled") boolean disabled) {
            this.status = status;
            this.collectedAt = collectedAt;
            this.disabled = disabled;
        }

        private final String status;

        public String status() {
            return this.status;
        }

        private final LocalDateTime collectedAt;

        public LocalDateTime collectedAt() {
            return this.collectedAt;
        }

        private final boolean disabled;

        public boolean disabled() {
            return this.disabled;
        }
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class MonitorConfig {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public MonitorConfig(@com.fasterxml.jackson.annotation.JsonProperty("id") long id, @com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("app") String app, @com.fasterxml.jackson.annotation.JsonProperty("instance") String instance, @com.fasterxml.jackson.annotation.JsonProperty("intervalSeconds") int intervalSeconds, @com.fasterxml.jackson.annotation.JsonProperty("scheduleType") String scheduleType, @com.fasterxml.jackson.annotation.JsonProperty("enabled") boolean enabled, @com.fasterxml.jackson.annotation.JsonProperty("labels") Map<String, String> labels, @com.fasterxml.jackson.annotation.JsonProperty("params") List<Map<String, Object>> params) {
            this.id = id;
            this.name = name;
            this.app = app;
            this.instance = instance;
            this.intervalSeconds = intervalSeconds;
            this.scheduleType = scheduleType;
            this.enabled = enabled;
            this.labels = labels;
            this.params = params;
        }

        private final long id;

        public long id() {
            return this.id;
        }

        private final String name;

        public String name() {
            return this.name;
        }

        private final String app;

        public String app() {
            return this.app;
        }

        private final String instance;

        public String instance() {
            return this.instance;
        }

        private final int intervalSeconds;

        public int intervalSeconds() {
            return this.intervalSeconds;
        }

        private final String scheduleType;

        public String scheduleType() {
            return this.scheduleType;
        }

        private final boolean enabled;

        public boolean enabled() {
            return this.enabled;
        }

        private final Map<String, String> labels;

        public Map<String, String> labels() {
            return this.labels;
        }

        private final List<Map<String, Object>> params;

        public List<Map<String, Object>> params() {
            return this.params;
        }
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class ExternalAlert {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public ExternalAlert(@com.fasterxml.jackson.annotation.JsonProperty("externalId") String externalId, @com.fasterxml.jackson.annotation.JsonProperty("monitorId") long monitorId, @com.fasterxml.jackson.annotation.JsonProperty("level") String level, @com.fasterxml.jackson.annotation.JsonProperty("message") String message, @com.fasterxml.jackson.annotation.JsonProperty("occurredAt") LocalDateTime occurredAt, @com.fasterxml.jackson.annotation.JsonProperty("resolvedAt") LocalDateTime resolvedAt, @com.fasterxml.jackson.annotation.JsonProperty("assetId") Long assetId) {
            this.externalId = externalId;
            this.monitorId = monitorId;
            this.level = level;
            this.message = message;
            this.occurredAt = occurredAt;
            this.resolvedAt = resolvedAt;
            this.assetId = assetId;
        }

        private final String externalId;

        public String externalId() {
            return this.externalId;
        }

        private final long monitorId;

        public long monitorId() {
            return this.monitorId;
        }

        private final String level;

        public String level() {
            return this.level;
        }

        private final String message;

        public String message() {
            return this.message;
        }

        private final LocalDateTime occurredAt;

        public LocalDateTime occurredAt() {
            return this.occurredAt;
        }

        private final LocalDateTime resolvedAt;

        public LocalDateTime resolvedAt() {
            return this.resolvedAt;
        }

        private final Long assetId;

        public Long assetId() {
            return this.assetId;
        }

        public ExternalAlert(String externalId, long monitorId, String level, String message, LocalDateTime occurredAt, LocalDateTime resolvedAt) {
            this(externalId, monitorId, level, message, occurredAt, resolvedAt, null);
        }
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class MetricResult {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public MetricResult(@com.fasterxml.jackson.annotation.JsonProperty("latest") List<MonitoringMetric> latest, @com.fasterxml.jackson.annotation.JsonProperty("history") List<MonitoringMetric> history, @com.fasterxml.jackson.annotation.JsonProperty("collectedAt") LocalDateTime collectedAt, @com.fasterxml.jackson.annotation.JsonProperty("errors") List<String> errors) {
            this.latest = latest;
            this.history = history;
            this.collectedAt = collectedAt;
            this.errors = errors;
        }

        private final List<MonitoringMetric> latest;

        public List<MonitoringMetric> latest() {
            return this.latest;
        }

        private final List<MonitoringMetric> history;

        public List<MonitoringMetric> history() {
            return this.history;
        }

        private final LocalDateTime collectedAt;

        public LocalDateTime collectedAt() {
            return this.collectedAt;
        }

        private final List<String> errors;

        public List<String> errors() {
            return this.errors;
        }
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class MetricTarget {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public MetricTarget(@com.fasterxml.jackson.annotation.JsonProperty("app") String app, @com.fasterxml.jackson.annotation.JsonProperty("instance") String instance) {
            this.app = app;
            this.instance = instance;
        }

        private final String app;

        public String app() {
            return this.app;
        }

        private final String instance;

        public String instance() {
            return this.instance;
        }
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private final static class MonitorTarget {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public MonitorTarget(@com.fasterxml.jackson.annotation.JsonProperty("app") String app, @com.fasterxml.jackson.annotation.JsonProperty("instance") String instance, @com.fasterxml.jackson.annotation.JsonProperty("params") List<Map<String, Object>> params) {
            this.app = app;
            this.instance = instance;
            this.params = params;
        }

        private final String app;

        public String app() {
            return this.app;
        }

        private final String instance;

        public String instance() {
            return this.instance;
        }

        private final List<Map<String, Object>> params;

        public List<Map<String, Object>> params() {
            return this.params;
        }
    }

    public static class MonitorNotFoundException extends IllegalStateException {

        public MonitorNotFoundException(long monitorId) {
            super("HertzBeat 监控不存在：" + monitorId);
        }

        private MonitorNotFoundException(String path) {
            super("HertzBeat 监控不存在：" + path);
        }
    }

    public static class AlertRuleNotFoundException extends IllegalStateException {

        public AlertRuleNotFoundException(String identity) {
            super("HertzBeat 告警规则不存在：" + identity);
        }
    }
}
