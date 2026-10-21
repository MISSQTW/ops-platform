package com.lqbz.ops.inspection;

import static org.assertj.core.api.Assertions.*;
import com.lqbz.ops.domain.*;
import com.lqbz.ops.integration.HertzBeatClient;
import com.lqbz.ops.service.InspectionPlanMonitoringSyncService;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

// 显式运行的真实HertzBeat验收，默认单元测试不会执行IT后缀类。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
    "server.port=18889", "ops.inspection.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "ops.inspection.bridge-base-url=http://host.docker.internal:18889",
    "spring.datasource.url=jdbc:h2:mem:real_hb_bridge;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "ops.demo-data.enabled=false", "ops.hertzbeat.url=http://localhost:1157",
    "ops.hertzbeat.config-sync-initial-delay-ms=3600000", "ops.inspection.scheduler-initial-delay-ms=3600000"})
@ActiveProfiles("test")
class HertzBeatProtocolBridgeIT {
  @Autowired JdbcTemplate jdbc;
  @Autowired AssetRepository assets;
  @Autowired InspectionPlanRepository plans;
  @Autowired InspectionPlanBindingRepository bindings;
  @Autowired InspectionProfileService profiles;
  @Autowired InspectionProfileRepository profileRows;
  @Autowired InspectionPlanMonitoringSyncService sync;
  @Autowired HertzBeatClient hb;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

  @Test void 真实采集桥接通过认证并在响应断言失败后DOWN再恢复() throws Exception {
    long seed = 80_000_000L + System.currentTimeMillis() % 1_000_000;
    jdbc.execute("ALTER TABLE ops_asset ALTER COLUMN id RESTART WITH " + seed);
    jdbc.execute("ALTER TABLE ops_inspection_plan ALTER COLUMN id RESTART WITH " + seed);
    AtomicBoolean healthy = new AtomicBoolean(true);
    AtomicInteger requests = new AtomicInteger();
    HttpServer target = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
    target.createContext("/health", exchange -> {
      requests.incrementAndGet();
      byte[] body = (healthy.get() ? "READY" : "BROKEN").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    target.start();
    Long monitorId = null, ruleId = null;
    try {
      Asset asset = assets.save(new Asset("protocol-bridge-" + seed, "独立验收", "测试", "HTTP",
          "http://localhost:" + target.getAddress().getPort() + "/health", "UNKNOWN"));
      profiles.save(asset.getId(), new InspectionProfileService.Update(true,
          Map.of("timeoutMillis", "1000", "expectedStatus", "200", "responseContains", "READY"), Map.of()));
      InspectionPlan plan = plans.save(InspectionPlan.monitoring("protocol-bridge-" + seed, seed, "独立验收", 10, 1, "WARNING"));
      bindings.saveAndFlush(InspectionPlanBinding.managed(plan.getId(), asset.getId(), plan.getDesiredVersion()));
      var result = sync.syncPlan(plan.getId());
      var binding = bindings.findByActiveOwnerKey(asset.getId()).orElseThrow();
      monitorId = binding.getHertzbeatMonitorId() == null ? assets.findById(asset.getId()).orElseThrow().getHertzbeatMonitorId() : binding.getHertzbeatMonitorId(); ruleId = binding.getHertzbeatAlertRuleId();
      assertThat(result.status()).as(binding.getSyncError()).isEqualTo("SYNCED");
      assertThat(hb.getMonitorConfig(monitorId).params()).anySatisfy(p -> {
        assertThat(p.get("field")).isEqualTo("timeout"); assertThat(String.valueOf(p.get("paramValue"))).isEqualTo("45000");
      });
      awaitStatus(monitorId, "UP", requests);
      assertThat(profiles.view(asset.getId()).checks()).allMatch(ProbeCheck::passed);
      assertThat(hb.monitorCredentialsMatch(asset, monitorId)).isTrue();
      corruptPassword(monitorId);
      assertThat(hb.monitorCredentialsMatch(asset, monitorId)).isFalse();
      assertThat(sync.syncPlan(plan.getId()).status()).isEqualTo("SYNCED");
      assertThat(hb.monitorCredentialsMatch(asset, monitorId)).isTrue();
      assertThat(assets.findById(asset.getId()).orElseThrow().getHertzbeatMonitorId()).isEqualTo(monitorId);
      awaitStatus(monitorId, "UP", requests);
      healthy.set(false);
      awaitStatus(monitorId, "DOWN", requests);
      assertThat(profiles.view(asset.getId()).checks()).anyMatch(c -> !c.passed());
      awaitAlert(asset.getId(), "firing");
      healthy.set(true);
      awaitStatus(monitorId, "UP", requests);
      awaitAlert(asset.getId(), "resolved");
      assertThat(requests.get()).isGreaterThanOrEqualTo(3);
      System.out.println("真实HertzBeat桥接：UP → HTTP200但断言失败DOWN → 恢复UP，且告警触发后恢复；Monitor=" + monitorId);
    } finally {
      if (ruleId != null) hb.deleteAlertRule(ruleId);
      if (monitorId != null) hb.deleteMonitor(monitorId);
      target.stop(0);
    }
  }
  private void corruptPassword(long monitorId) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create("http://localhost:1157/api/account/auth/form"))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
            "{\"identifier\":\"admin\",\"credential\":\"hertzbeat\"}")).build(), HttpResponse.BodyHandlers.ofString());
    String token = json.readTree(login.body()).path("data").path("token").asText();
    HttpResponse<String> detail = client.send(HttpRequest.newBuilder(URI.create("http://localhost:1157/api/monitor/" + monitorId))
        .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    var body = json.readTree(detail.body()).path("data");
    for (var parameter : body.path("params")) {
      if ("password".equals(parameter.path("field").asText()))
        ((com.fasterxml.jackson.databind.node.ObjectNode) parameter).put("paramValue", "external-drift-test");
    }
    HttpResponse<String> update = client.send(HttpRequest.newBuilder(URI.create("http://localhost:1157/api/monitor"))
        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(json.readTree(update.body()).path("code").asInt(-1)).isZero();
    client.close();
  }
  private void awaitAlert(long assetId, String state) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
    do {
      if (hb.alerts(state).stream().anyMatch(alert -> java.util.Objects.equals(alert.assetId(), assetId))) return;
      Thread.sleep(1000);
    } while (System.nanoTime() < deadline);
    fail("HertzBeat未在期限内产生告警状态 " + state);
  }
  private void awaitStatus(long monitorId, String expected, AtomicInteger requests) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(50);
    do {
      var status = hb.monitorStatus(monitorId);
      if (expected.equals(status.status()) && requests.get() > 0 && status.collectedAt() != null) return;
      Thread.sleep(1000);
    } while (System.nanoTime() < deadline);
    fail("HertzBeat未在期限内达到 " + expected);
  }
}
