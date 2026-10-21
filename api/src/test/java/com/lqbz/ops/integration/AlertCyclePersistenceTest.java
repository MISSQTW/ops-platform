package com.lqbz.ops.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lqbz.ops.domain.AlertHandlingEvent;
import com.lqbz.ops.domain.AlertHandlingEventRepository;
import com.lqbz.ops.domain.AlertRepository;
import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"ops.inspection.scheduler-initial-delay-ms=3600000",
    "ops.hertzbeat.config-sync-initial-delay-ms=3600000", "ops.hertzbeat.sync-interval-ms=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertCyclePersistenceTest {
  @Autowired private MonitoringProjectionService projection;
  @Autowired private AssetRepository assets;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertHandlingEventRepository events;
  @Autowired private MockMvc mvc;
  @MockBean private HertzBeatClient client;

  @Test
  void 已关闭同外部ID重开后重复同步必须保留完整上一轮历史且只重开一次() throws Exception {
    LocalDateTime firstOccurred = LocalDateTime.now().minusMinutes(10);
    Asset asset = new Asset("周期持久化接口", "周期持久化系统", "测试", "HTTP", "http://127.0.0.1:1/", "UNKNOWN");
    asset.markSynced(9320999L, "测试Monitor");
    assets.saveAndFlush(asset);
    when(client.monitorStatus(9320999L)).thenReturn(new HertzBeatClient.MonitorStatus("DOWN", firstOccurred, false));
    when(client.alerts("resolved")).thenReturn(List.of());
    when(client.alerts("firing")).thenReturn(List.of(new HertzBeatClient.ExternalAlert("closed-cycle-persistent",
        9320999L, "CRITICAL", "首次故障", firstOccurred, null)));
    projection.sync(firstOccurred);
    long alertId = alerts.findByExternalSourceAndExternalAlertId("HERTZBEAT", "closed-cycle-persistent").orElseThrow().getId();
    mvc.perform(post("/api/alerts/{id}/acknowledge", alertId).contentType(MediaType.APPLICATION_JSON)
        .content("{\"note\":\"首轮确认\"}")).andExpect(status().isOk());
    when(client.alerts("firing")).thenReturn(List.of());
    when(client.alerts("resolved")).thenReturn(List.of(new HertzBeatClient.ExternalAlert("closed-cycle-persistent",
        9320999L, "CRITICAL", "首次恢复", firstOccurred, LocalDateTime.now().minusMinutes(5))));
    projection.sync(LocalDateTime.now().minusMinutes(5));
    mvc.perform(post("/api/alerts/{id}/close", alertId).contentType(MediaType.APPLICATION_JSON)
        .content("{\"note\":\"首轮关闭\"}")).andExpect(status().isOk());
    assertThat(alerts.findById(alertId).orElseThrow().getHandlingStatus()).isEqualTo("CLOSED");
    var previousEvents = events.findByAlertIdOrderByOccurredAtAscIdAsc(alertId);
    assertThat(previousEvents).extracting(AlertHandlingEvent::getAction).containsExactly("ACKNOWLEDGED", "CLOSED");

    LocalDateTime nextOccurred = LocalDateTime.now().plusSeconds(1);
    when(client.alerts("resolved")).thenReturn(List.of());
    when(client.alerts("firing")).thenReturn(List.of(new HertzBeatClient.ExternalAlert("closed-cycle-persistent",
        9320999L, "CRITICAL", "第二轮故障", nextOccurred, null)));
    projection.sync(nextOccurred);
    projection.sync(nextOccurred.plusSeconds(1));

    var reopened = alerts.findById(alertId).orElseThrow();
    assertThat(reopened.getHandlingStatus()).isEqualTo("OPEN");
    assertThat(reopened.getExternalLifecycle()).isEqualTo("FIRING");
    assertThat(alerts.findByExternalSourceAndExternalAlertId("HERTZBEAT", "closed-cycle-persistent").orElseThrow().getId())
        .isEqualTo(alertId);
    var currentEvents = events.findByAlertIdOrderByOccurredAtAscIdAsc(alertId);
    assertThat(currentEvents).hasSize(3);
    assertThat(currentEvents.subList(0, 2)).extracting(AlertHandlingEvent::getId)
        .containsExactlyElementsOf(previousEvents.stream().map(AlertHandlingEvent::getId).toList());
    assertThat(currentEvents.subList(0, 2)).extracting(AlertHandlingEvent::getNote).containsExactly("首轮确认", "首轮关闭");
    assertThat(currentEvents.getLast().getAction()).isEqualTo("REOPENED");
    assertThat(currentEvents.getLast().getFromStatus()).isEqualTo("CLOSED");
    assertThat(currentEvents.getLast().getToStatus()).isEqualTo("OPEN");
  }
}
