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
        "{\"code\":0,\"data\":{\"time\":1792384800000,\"fields\":[{\"name\":\"responseTime\",\"unit\":\"ms\"}],\"valueRows\":[{\"values\":[{\"origin\":\"86\",\"time\":1792384800000}]}]}}"));
    server.createContext("/api/monitor/order.example.com:443/metric/website.summary.responseTime", exchange -> respond(exchange, 200,
        "{\"code\":0,\"data\":{\"field\":{\"name\":\"responseTime\",\"unit\":\"ms\"},\"values\":{\"default\":[{\"origin\":\"82\",\"time\":1792381200000},{\"origin\":\"86\",\"time\":1792384800000}]}}}"));
    server.createContext("/api/monitor/999", exchange -> respond(exchange, 404, ""));
    server.start();
    client = new HertzBeatClient(objectMapper, "http://localhost:" + server.getAddress().getPort(), "admin", "hertzbeat");
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
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
