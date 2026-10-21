package com.lqbz.ops.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqbz.ops.domain.Asset;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HertzBeatClientTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final List<CapturedRequest> requests = new ArrayList<>();
  private HttpServer server;
  private HertzBeatClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/api/account/auth/form", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"token\":\"test-token\"}}"));
    server.createContext("/api/monitors", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"content\":[{\"id\":321,\"name\":\"订单接口\"},{\"id\":322,\"name\":\"订单缓存\"}]}}"));
    server.createContext("/api/monitor", exchange -> {
      if ("GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 200, "{\"code\":0,\"data\":{\"monitor\":{\"id\":321,\"jobId\":654},\"params\":[{\"id\":11,\"monitorId\":321,\"field\":\"host\"},{\"id\":12,\"monitorId\":321,\"field\":\"port\"},{\"id\":13,\"monitorId\":321,\"field\":\"uri\"},{\"id\":14,\"monitorId\":321,\"field\":\"ssl\"},{\"id\":15,\"monitorId\":321,\"field\":\"timeout\"}]}}" );
        return;
      }
      requests.add(capture(exchange));
      respond(exchange, 200, "{\"code\":0,\"data\":null}");
    });
    server.createContext("/api/monitor/321/metrics/summary", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"time\":1792384800000,\"fields\":[{\"name\":\"responseTime\",\"unit\":\"fortnight\"},{\"name\":\"responseTime\",\"unit\":\"ms\"}],\"valueRows\":[{\"values\":[{\"origin\":\"invalid\",\"time\":1792384800000},{\"origin\":\"86\",\"time\":1792384800000}]}]}}"));
    server.createContext("/api/monitor/order.example.com:443/metric/website.summary.responseTime", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"field\":{\"name\":\"responseTime\",\"unit\":\"ms\"},\"values\":{\"default\":[{\"origin\":\"82\",\"time\":1792381200000},{\"origin\":\"86\",\"time\":1792384800000}]}}}"));
    server.createContext("/api/monitor/999", exchange -> respond(exchange, 404, ""));
    server.start();
    client = new HertzBeatClient(objectMapper, "http://localhost:" + server.getAddress().getPort(), "admin", "hertzbeat");
  }

  @Test
  void 深度巡检适配实例端口及密码遮罩但仍拒绝版本漂移() {
    var profiles = org.mockito.Mockito.mock(com.lqbz.ops.inspection.InspectionProfileService.class);
    var asset = new Asset("桥接", "测试", "测试", "HTTP", "http://target", "UNKNOWN");
    org.springframework.test.util.ReflectionTestUtils.setField(asset, "id", 9L);
    org.mockito.Mockito.when(profiles.bridge(asset)).thenReturn(new com.lqbz.ops.inspection.InspectionProfileService.Bridge(
        java.net.URI.create("http://api:8080/internal/inspection/9"), "inspection-9-r2", "derived-password"));
    org.springframework.test.util.ReflectionTestUtils.setField(client, "inspectionProfiles", profiles);
    assertThat(client.metricTarget(asset).instance()).isEqualTo("inspection-9:8080");
    var actual = client.monitorParameters(asset).stream().map(p -> new java.util.HashMap<String, Object>(p)).toList();
    actual.stream().filter(p -> "password".equals(p.get("field"))).forEach(p -> p.put("paramValue", "******"));
    assertThat(client.monitorParametersMatch(asset, new ArrayList<>(actual))).isTrue();
    actual.stream().filter(p -> "username".equals(p.get("field"))).forEach(p -> p.put("paramValue", "inspection-9-r1"));
    assertThat(client.monitorParametersMatch(asset, new ArrayList<>(actual))).isFalse();
  }

  @Test
  void 密码单独漂移通过官方单实例导出检测且不改写Monitor() throws Exception {
    var profiles = org.mockito.Mockito.mock(com.lqbz.ops.inspection.InspectionProfileService.class);
    var asset = new Asset("导出测试", "测试", "测试", "HTTP", "http://target", "UNKNOWN");
    org.springframework.test.util.ReflectionTestUtils.setField(asset, "id", 9L);
    org.mockito.Mockito.when(profiles.bridge(asset)).thenReturn(new com.lqbz.ops.inspection.InspectionProfileService.Bridge(
        java.net.URI.create("http://api:8080/internal/inspection/9"), "inspection-9-r2", "expected-password"));
    org.springframework.test.util.ReflectionTestUtils.setField(client, "inspectionProfiles", profiles);
    var stored = new java.util.concurrent.atomic.AtomicReference<>("stable-ciphertext-A");
    server.createContext("/api/monitors/export", exchange -> respond(exchange, 200,
        "[{\"monitor\":{\"name\":\"导出测试\"},\"params\":[{\"field\":\"password\",\"type\":2,\"value\":\"" + stored.get() + "\"}]}]"));
    assertThat(client.monitorCredentialsMatch(asset, 321)).isFalse();
    asset.markSynced(321L, "既有测试映射");
    client.syncMonitor(asset, 60, true);
    assertThat(client.monitorCredentialsMatch(asset, 321)).isTrue();
    assertThat(client.monitorCredentialsMatch(asset, 321)).isTrue();
    stored.set("external-drift");
    assertThat(client.monitorCredentialsMatch(asset, 321)).isFalse();
    assertThat(requests).hasSize(1);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void 创建成功但本地未提交时重试应按服务标签认领而不是重复创建() throws Exception {
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");
    org.springframework.test.util.ReflectionTestUtils.setField(asset, "id", 77L);
    var created = new java.util.concurrent.atomic.AtomicBoolean();
    server.removeContext("/api/monitors");
    server.createContext("/api/monitors", exchange -> respond(exchange, 200, created.get()
        ? "{\"code\":0,\"data\":{\"content\":[{\"id\":321,\"name\":\"订单接口\",\"labels\":{\"assetId\":\"77\",\"managedBy\":\"ops-platform\"}}],\"totalPages\":1,\"last\":true}}"
        : "{\"code\":0,\"data\":{\"content\":[],\"totalPages\":0,\"last\":true}}"));
    server.removeContext("/api/monitor");
    server.createContext("/api/monitor", exchange -> {
      if ("GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 200, "{\"code\":0,\"data\":{\"monitor\":{\"id\":321,\"jobId\":654},\"params\":[]}}");
      } else {
        requests.add(capture(exchange));
        created.set(true);
        respond(exchange, 200, "{\"code\":0,\"data\":321}");
      }
    });

    assertThat(client.syncMonitor(asset, 60, true).monitorId()).isEqualTo(321L);
    HertzBeatClient restarted = new HertzBeatClient(objectMapper, "http://localhost:" + server.getAddress().getPort(), "admin", "hertzbeat");
    assertThat(restarted.syncMonitor(asset, 60, true).monitorId()).isEqualTo(321L);

    assertThat(asset.getHertzbeatMonitorId()).isNull();
    assertThat(requests).extracting(CapturedRequest::method).containsExactly("POST", "PUT");
    assertThat(objectMapper.readTree(requests.getFirst().body()).path("monitor").path("labels").path("managedBy").asText())
        .isEqualTo("ops-platform");
  }

  @Test
  void 跨分页重复服务标签必须拒绝认领且不写外部资源() throws Exception {
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");
    org.springframework.test.util.ReflectionTestUtils.setField(asset, "id", 77L);
    server.removeContext("/api/monitors");
    server.createContext("/api/monitors", exchange -> {
      boolean first = exchange.getRequestURI().getQuery().contains("pageIndex=0");
      respond(exchange, 200, "{\"code\":0,\"data\":{\"content\":[{\"id\":" + (first ? 321 : 322)
          + ",\"name\":\"订单接口\",\"labels\":{\"assetId\":\"77\"}}],\"totalPages\":2,\"last\":" + !first + "}}");
    });

    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> client.syncMonitor(asset, 60, true)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("重复");
    assertThat(requests).isEmpty();
  }

  @Test
  void 无法核实监控列表时不得创建新监控() throws Exception {
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");
    org.springframework.test.util.ReflectionTestUtils.setField(asset, "id", 77L);
    server.removeContext("/api/monitors");
    server.createContext("/api/monitors", exchange -> respond(exchange, 503, "{\"code\":1,\"msg\":\"unavailable\"}"));

    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> client.syncMonitor(asset, 60, true)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(requests).isEmpty();
  }

  @Test
  void 持久化服务不得通过同名但其他服务的监控推断创建结果() throws Exception {
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");
    org.springframework.test.util.ReflectionTestUtils.setField(asset, "id", 77L);
    AtomicInteger listed = new AtomicInteger();
    server.removeContext("/api/monitors");
    server.createContext("/api/monitors", exchange -> respond(exchange, 200, listed.getAndIncrement() == 0
        ? "{\"code\":0,\"data\":{\"content\":[],\"totalPages\":0}}"
        : "{\"code\":0,\"data\":{\"content\":[{\"id\":321,\"name\":\"订单接口\",\"labels\":{\"assetId\":\"88\"}}],\"totalPages\":1}}"));

    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> client.syncMonitor(asset, 60, true)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("未返回监控 ID");
    assertThat(requests).extracting(CapturedRequest::method).containsExactly("POST");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "{\"content\":[{\"id\":321,\"labels\":{}}]}",
      "{\"content\":[{\"id\":321,\"labels\":{}}],\"totalPages\":0}",
      "{\"content\":[],\"totalPages\":101}",
      "{\"content\":[],\"totalPages\":1,\"last\":false,\"totalElements\":1}",
      "{\"content\":[],\"totalPages\":1,\"number\":1}",
      "{\"content\":[],\"totalPages\":1,\"totalElements\":1}",
      "{\"content\":[],\"totalPages\":0,\"first\":false}",
      "{\"content\":[],\"totalPages\":0,\"empty\":false}",
      "{\"content\":[],\"totalPages\":0,\"numberOfElements\":1}",
      "{\"content\":[{\"id\":321,\"labels\":{\"assetId\":\"77\",\"managedBy\":\"other-platform\"}}],\"totalPages\":1}"
  })
  void 不能证明安全归属的列表必须拒绝写入(String data) throws Exception {
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");
    org.springframework.test.util.ReflectionTestUtils.setField(asset, "id", 77L);
    server.removeContext("/api/monitors");
    server.createContext("/api/monitors", exchange -> respond(exchange, 200, "{\"code\":0,\"data\":" + data + "}"));

    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> client.syncMonitor(asset, 60, true)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(requests).isEmpty();
  }

  @Test
  void 首页面命中但后续页失败时不得提前认领() throws Exception {
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");
    org.springframework.test.util.ReflectionTestUtils.setField(asset, "id", 77L);
    server.removeContext("/api/monitors");
    server.createContext("/api/monitors", exchange -> {
      boolean first = exchange.getRequestURI().getQuery().contains("pageIndex=0");
      respond(exchange, first ? 200 : 503, first
          ? "{\"code\":0,\"data\":{\"content\":[{\"id\":321,\"labels\":{\"assetId\":\"77\"}}],\"totalPages\":2,\"last\":false}}"
          : "{\"code\":1,\"msg\":\"unavailable\"}");
    });

    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> client.syncMonitor(asset, 60, true)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(requests).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void 后页新增矛盾总页数不能认领也不能创建(boolean matchingAsset) throws Exception {
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");
    org.springframework.test.util.ReflectionTestUtils.setField(asset, "id", 77L);
    server.removeContext("/api/monitors");
    server.createContext("/api/monitors", exchange -> respond(exchange, 200,
        exchange.getRequestURI().getQuery().contains("pageIndex=0")
            ? "{\"code\":0,\"data\":{\"content\":[{\"id\":321,\"labels\":{\"assetId\":\""
                + (matchingAsset ? 77 : 88) + "\"}}],\"size\":1,\"last\":false}}"
            : "{\"code\":0,\"data\":{\"content\":[],\"size\":1,\"totalPages\":0,\"last\":true}}"));

    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> client.syncMonitor(asset, 60, true)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(requests).isEmpty();
  }

  @Test
  void HTTP资产应同步为网站监控() throws Exception {
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");

    HertzBeatClient.SyncResult result = client.sync(asset);

    assertThat(result.monitorId()).isEqualTo(321L);
    assertThat(requests).hasSize(1);
    JsonNode body = objectMapper.readTree(requests.getFirst().body());
    assertThat(body.path("monitor").path("app").asText()).isEqualTo("website");
    assertThat(body.path("monitor").path("labels").path("project").asText()).isEqualTo("订单平台");
    assertThat(body.path("monitor").path("labels").path("environment").asText()).isEqualTo("生产");
    assertThat(body.path("monitor").path("name").asText()).isEqualTo("订单接口");
    assertThat(body.path("params").toString()).contains("order.example.com", "ssl", "true");
    assertThat(requests.getFirst().authorization()).isEqualTo("Bearer test-token");
  }

  @Test
  void 应解析真实SingleAlert的受管资产标签级别和恢复时间() throws Exception {
    server.createContext("/api/alerts", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"content\":[{\"id\":42,\"labels\":{\"assetId\":\"77\",\"severity\":\"critical\"},\"content\":\"验收告警\",\"startAt\":1792381200000,\"endAt\":1792384800000}],\"last\":true}}"));

    List<HertzBeatClient.ExternalAlert> result = client.alerts("resolved");

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().assetId()).isEqualTo(77L);
    assertThat(result.getFirst().level()).isEqualTo("critical");
    assertThat(result.getFirst().resolvedAt()).isNotNull();
  }

  @Test
  void Redis资产应同步为端口监控() throws Exception {
    Asset asset = new Asset("订单缓存", "订单平台", "生产", "REDIS", "redis://cache.internal:6380", "UNKNOWN");

    client.sync(asset);

    JsonNode body = objectMapper.readTree(requests.getFirst().body());
    assertThat(body.path("monitor").path("app").asText()).isEqualTo("port");
    assertThat(body.path("params").toString()).contains("cache.internal", "6380", "6000");
  }

  @Test
  void 已有监控ID时应更新而不是重复创建() throws Exception {
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");
    asset.markSynced(321L, "同步成功");

    HertzBeatClient.SyncResult result = client.sync(asset);

    assertThat(result.monitorId()).isEqualTo(321L);
    assertThat(requests.getFirst().method()).isEqualTo("PUT");
    assertThat(requests.getFirst().path()).isEqualTo("/api/monitor");
    assertThat(objectMapper.readTree(requests.getFirst().body()).path("monitor").path("id").asLong()).isEqualTo(321L);
    assertThat(objectMapper.readTree(requests.getFirst().body()).path("monitor").path("jobId").asLong()).isEqualTo(654L);
    assertThat(objectMapper.readTree(requests.getFirst().body()).path("params").get(0).path("id").asLong()).isEqualTo(11L);
  }

  @Test
  void 计划频率和停用状态应下发到Monitor() throws Exception {
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");

    client.syncMonitor(asset, 300, false);

    JsonNode monitor = objectMapper.readTree(requests.getFirst().body()).path("monitor");
    assertThat(monitor.path("intervals").asInt()).isEqualTo(300);
    assertThat(monitor.path("status").asInt()).isZero();
  }

  @Test
  void Monitor固定间隔不能小于HertzBeat最小值() {
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");

    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> client.syncMonitor(asset, 9, true)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("HertzBeat 固定采集间隔不能小于 10 秒");
  }

  @Test
  void 应创建并回查受管告警规则ID() throws Exception {
    AtomicInteger listCalls = new AtomicInteger();
    server.createContext("/api/alert/define", exchange -> {
      requests.add(capture(exchange));
      respond(exchange, 200, "{\"code\":0,\"data\":null,\"msg\":\"Add success\"}");
    });
    server.createContext("/api/alert/defines", exchange -> {
      if (listCalls.getAndIncrement() == 0) {
        respond(exchange, 200, "{\"code\":0,\"data\":{\"content\":[]}}");
        return;
      }
      respond(exchange, 200,
          "{\"code\":0,\"data\":{\"content\":[{\"id\":901,\"name\":\"ops-plan-12-asset-77\",\"type\":\"realtime\",\"expr\":\"availability == 0\",\"period\":5,\"times\":2,\"labels\":{\"managedBy\":\"ops-platform\",\"planId\":\"12\",\"assetId\":\"77\"},\"annotations\":{\"summary\":\"ops-plan-12-asset-77\"},\"template\":\"\",\"datasource\":\"hertzbeat\",\"enable\":true,\"unknownField\":\"ignored\"}]}}");
    });
    HertzBeatClient.AlertRuleSpec spec = new HertzBeatClient.AlertRuleSpec(null, "ops-plan-12-asset-77",
        "availability == 0", 5, 2, "CRITICAL", 12L, 77L, true);

    HertzBeatClient.AlertRule result = client.upsertAlertRule(spec);

    assertThat(result.id()).isEqualTo(901L);
    assertThat(result.period()).isEqualTo(5);
    assertThat(result.times()).isEqualTo(2);
    assertThat(result.type()).isEqualTo("realtime");
    assertThat(result.datasource()).isEqualTo("hertzbeat");
    assertThat(result.annotations()).containsEntry("summary", "ops-plan-12-asset-77");
    assertThat(listCalls).hasValue(2);
    assertThat(requests).singleElement();
    JsonNode body = objectMapper.readTree(requests.getFirst().body());
    assertThat(requests.getFirst().method()).isEqualTo("POST");
    assertThat(body.path("type").asText()).isEqualTo("realtime_metric");
    assertThat(body.path("expr").asText()).isEqualTo("availability == 0");
    assertThat(body.path("labels").path("severity").asText()).isEqualTo("critical");
    assertThat(body.path("labels").path("planId").asText()).isEqualTo("12");
  }

  @Test
  void 创建前应按受管标签复用已有规则避免重复POST() throws Exception {
    server.createContext("/api/alert/defines", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"content\":[{\"id\":902,\"name\":\"旧名称\",\"type\":\"realtime\",\"expr\":\"availability == 0\",\"period\":5,\"times\":2,\"labels\":{\"managedBy\":\"ops-platform\",\"planId\":\"12\",\"assetId\":\"77\"},\"annotations\":{},\"template\":\"\",\"datasource\":\"hertzbeat\",\"enable\":true}]}}"));
    server.createContext("/api/alert/define", exchange -> {
      requests.add(capture(exchange));
      respond(exchange, 200, "{\"code\":0,\"data\":null,\"msg\":\"Modify success\"}");
    });
    server.createContext("/api/alert/define/902", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"id\":902,\"name\":\"ops-plan-12-asset-77\",\"type\":\"realtime\",\"expr\":\"availability == 0\",\"period\":5,\"times\":2,\"labels\":{\"managedBy\":\"ops-platform\",\"planId\":\"12\",\"assetId\":\"77\"},\"annotations\":{},\"template\":\"\",\"datasource\":\"hertzbeat\",\"enable\":true}}"));

    HertzBeatClient.AlertRule result = client.upsertAlertRule(new HertzBeatClient.AlertRuleSpec(null,
        "ops-plan-12-asset-77", "availability == 0", 5, 2, "CRITICAL", 12L, 77L, true));

    assertThat(result.id()).isEqualTo(902L);
    assertThat(requests).singleElement().satisfies(request -> {
      assertThat(request.method()).isEqualTo("PUT");
      assertThat(request.body()).contains("\"id\":902");
    });
  }

  @Test
  void 创建前应遍历分页查找受管规则() throws Exception {
    server.createContext("/api/alert/defines", exchange -> {
      String query = exchange.getRequestURI().getQuery();
      if (query != null && query.contains("pageIndex=1")) {
        respond(exchange, 200,
            "{\"code\":0,\"data\":{\"content\":[{\"id\":903,\"name\":\"旧名称\",\"type\":\"realtime\",\"expr\":\"availability == 0\",\"period\":5,\"times\":2,\"labels\":{\"managedBy\":\"ops-platform\",\"planId\":\"12\",\"assetId\":\"77\"},\"annotations\":{},\"template\":\"\",\"datasource\":\"hertzbeat\",\"enable\":true}],\"last\":true,\"totalPages\":2}}");
        return;
      }
      respond(exchange, 200,
          "{\"code\":0,\"data\":{\"content\":[],\"last\":false,\"totalPages\":2}}");
    });
    server.createContext("/api/alert/define", exchange -> {
      requests.add(capture(exchange));
      respond(exchange, 200, "{\"code\":0,\"data\":null,\"msg\":\"Modify success\"}");
    });
    server.createContext("/api/alert/define/903", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"id\":903,\"name\":\"ops-plan-12-asset-77\",\"type\":\"realtime\",\"expr\":\"availability == 0\",\"period\":5,\"times\":2,\"labels\":{\"managedBy\":\"ops-platform\",\"planId\":\"12\",\"assetId\":\"77\"},\"annotations\":{},\"template\":\"\",\"datasource\":\"hertzbeat\",\"enable\":true}}"));

    HertzBeatClient.AlertRule result = client.upsertAlertRule(new HertzBeatClient.AlertRuleSpec(null,
        "ops-plan-12-asset-77", "availability == 0", 5, 2, "CRITICAL", 12L, 77L, true));

    assertThat(result.id()).isEqualTo(903L);
    assertThat(requests).singleElement().satisfies(request -> assertThat(request.method()).isEqualTo("PUT"));
  }

  @Test
  void 已有告警规则应原ID更新且保留连续失败语义() throws Exception {
    server.createContext("/api/alert/define", exchange -> {
      requests.add(capture(exchange));
      respond(exchange, 200, "{\"code\":0,\"data\":null,\"msg\":\"Modify success\"}");
    });
    server.createContext("/api/alert/define/901", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"id\":901,\"name\":\"ops-plan-12-asset-77\",\"type\":\"realtime\",\"expr\":\"availability == 0\",\"period\":10,\"times\":3,\"labels\":{},\"annotations\":{},\"template\":\"\",\"datasource\":\"hertzbeat\",\"enable\":false}}"));

    HertzBeatClient.AlertRule result = client.upsertAlertRule(new HertzBeatClient.AlertRuleSpec(901L,
        "ops-plan-12-asset-77", "availability == 0", 10, 3, "WARNING", 12L, 77L, false));

    assertThat(requests.getFirst().method()).isEqualTo("PUT");
    JsonNode body = objectMapper.readTree(requests.getFirst().body());
    assertThat(body.path("id").asLong()).isEqualTo(901L);
    assertThat(body.path("period").asInt()).isEqualTo(10);
    assertThat(body.path("times").asInt()).isEqualTo(3);
    assertThat(body.path("enable").asBoolean()).isFalse();
    assertThat(result.enable()).isFalse();
  }

  @Test
  void 删除不存在的告警规则应视为幂等成功() throws Exception {
    server.createContext("/api/alert/define/999", exchange -> respond(exchange, 200,
        "{\"code\":3,\"data\":null,\"msg\":\"AlertDefine not exist.\"}"));

    client.deleteAlertRule(999L);
  }

  @Test
  void 应读取Monitor实际配置并忽略未知字段() throws Exception {
    server.createContext("/api/monitor/458", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"monitor\":{\"id\":458,\"name\":\"支付接口\",\"app\":\"website\",\"instance\":\"pay.example.com:443\",\"intervals\":300,\"scheduleType\":\"fixed\",\"status\":0,\"labels\":{\"assetId\":\"77\",\"project\":\"支付平台\"},\"unknownField\":\"ignored\"},\"params\":[{\"field\":\"host\",\"paramValue\":\"pay.example.com\",\"type\":1}]}}"));

    HertzBeatClient.MonitorConfig config = client.getMonitorConfig(458L);

    assertThat(config.id()).isEqualTo(458L);
    assertThat(config.name()).isEqualTo("支付接口");
    assertThat(config.app()).isEqualTo("website");
    assertThat(config.instance()).isEqualTo("pay.example.com:443");
    assertThat(config.intervalSeconds()).isEqualTo(300);
    assertThat(config.scheduleType()).isEqualTo("fixed");
    assertThat(config.enabled()).isFalse();
    assertThat(config.labels()).containsEntry("assetId", "77");
    assertThat(config.params()).singleElement().satisfies(param ->
        assertThat(param).containsEntry("field", "host").containsEntry("paramValue", "pay.example.com"));
  }

  @Test
  void 已有Monitor被外部删除时同步应返回可识别异常() {
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");
    asset.markSynced(999L, "同步成功");

    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> client.syncMonitor(asset, 60, true)))
        .isInstanceOf(HertzBeatClient.MonitorNotFoundException.class);
  }

  @Test
  void Monitor业务码3应映射为外部删除() {
    server.createContext("/api/monitor/459", exchange -> respond(exchange, 200,
        "{\"code\":3,\"data\":null,\"msg\":\"Monitor not exist.\"}"));

    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> client.getMonitorConfig(459L)))
        .isInstanceOf(HertzBeatClient.MonitorNotFoundException.class);
  }

  @Test
  void Monitor状态查询业务码3应映射为外部删除() {
    server.createContext("/api/monitor/460", exchange -> respond(exchange, 200,
        "{\"code\":3,\"data\":null,\"msg\":\"Resource missing\"}"));

    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> client.monitorStatus(460L)))
        .isInstanceOf(HertzBeatClient.MonitorNotFoundException.class);
  }

  @Test
  void 应解析实时指标和最近一小时趋势() throws Exception {
    HertzBeatClient.MetricResult result = client.metrics(321L, "order.example.com:443", "website");

    assertThat(result.latest()).extracting(MonitoringMetric::name).contains("availability", "responseTime");
    assertThat(result.latest()).filteredOn(metric -> "responseTime".equals(metric.name()))
        .extracting(MonitoringMetric::value).containsExactly(86L);
    assertThat(result.history()).hasSize(2);
  }

  @Test
  void 删除不存在的监控应视为幂等成功() throws Exception {
    client.deleteMonitor(999L);
  }

  @Test
  void 查询不存在的监控应返回可识别的映射失效异常() {
    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> client.monitorStatus(999L)))
        .isInstanceOf(HertzBeatClient.MonitorNotFoundException.class);
  }

  @Test
  void 数字不可用状态不能被空指标或历史成功指标覆盖() throws Exception {
    server.createContext("/api/monitor/456", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"monitor\":{\"id\":456,\"status\":2}}}"));
    server.createContext("/api/monitor/456/metrics/summary", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"valueRows\":[]}}"));

    assertThat(client.monitorStatus(456L).status()).isEqualTo("DOWN");
  }

  @Test
  void 数字可用状态应保留指标采集时间() throws Exception {
    server.createContext("/api/monitor/457", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"monitor\":{\"id\":457,\"status\":1}}}"));
    server.createContext("/api/monitor/457/metrics/summary", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"time\":1792384800000,\"valueRows\":[]}}"));

    HertzBeatClient.MonitorStatus result = client.monitorStatus(457L);
    assertThat(result.status()).isEqualTo("UP");
    assertThat(result.collectedAt()).isNotNull();
  }

  private CapturedRequest capture(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    return new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
        exchange.getRequestHeaders().getFirst("Authorization"), body);
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private record CapturedRequest(String method, String path, String authorization, String body) {}
}
