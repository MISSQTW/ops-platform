package com.lqbz.ops.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lqbz.ops.domain.AlertRepository;
import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MonitoringProjectionServiceTest {
  private final AssetRepository assets = mock(AssetRepository.class);
  private final AlertRepository alerts = mock(AlertRepository.class);
  private final HertzBeatClient client = mock(HertzBeatClient.class);
  private final MonitoringProjectionService service = new MonitoringProjectionService(assets, alerts, client);

  @Test
  void 状态同步成功应更新资产并清除陈旧标记() throws Exception {
    Asset asset = asset();
    asset.markSynced(321L, "同步成功");
    when(assets.findByMonitoringMode("HERTZBEAT")).thenReturn(List.of(asset));
    LocalDateTime collectedAt = LocalDateTime.of(2026, 10, 19, 10, 0);
    when(client.monitorStatus(321L)).thenReturn(new HertzBeatClient.MonitorStatus("UP", collectedAt, false));

    MonitoringProjectionService.ProjectionResult result = service.sync(LocalDateTime.of(2026, 10, 19, 10, 1));

    assertThat(result.assetsUpdated()).isEqualTo(1);
    assertThat(asset.getStatus()).isEqualTo("UP");
    assertThat(asset.isMonitoringStale()).isFalse();
  }

  @Test
  void 告警查询失败时不得根据缺席恢复已有告警() throws Exception {
    Asset asset = asset();
    asset.markSynced(321L, "同步成功");
    when(assets.findByMonitoringMode("HERTZBEAT")).thenReturn(List.of(asset));
    when(client.monitorStatus(321L)).thenReturn(new HertzBeatClient.MonitorStatus("UP", LocalDateTime.now(), false));
    when(client.alerts("firing")).thenThrow(new IllegalStateException("告警查询失败"));

    MonitoringProjectionService.ProjectionResult result = service.sync(LocalDateTime.now());

    assertThat(result.alertsUpdated()).isZero();
    assertThat(result.errors()).anyMatch(message -> message.contains("告警查询失败"));
  }

  @Test
  void 外部监控不存在时应将映射标记为失效() throws Exception {
    Asset asset = asset();
    asset.markSynced(321L, "同步成功");
    when(assets.findByMonitoringMode("HERTZBEAT")).thenReturn(List.of(asset));
    when(client.monitorStatus(321L)).thenThrow(new HertzBeatClient.MonitorNotFoundException(321L));
    when(client.alerts("firing")).thenReturn(List.of());
    when(client.alerts("resolved")).thenReturn(List.of());

    service.sync(LocalDateTime.now());

    assertThat(asset.getMappingStatus()).isEqualTo("INVALIDATED");
    assertThat(asset.getStatus()).isEqualTo("UNKNOWN");
  }

  @Test
  void 明确resolved数据应更新原告警而不是创建重复记录() throws Exception {
    Asset asset = asset();
    asset.markSynced(321L, "同步成功");
    com.lqbz.ops.domain.Alert existing = com.lqbz.ops.domain.Alert.external("HERTZBEAT", "alert-1", 321L,
        "CRITICAL", "订单平台", "订单接口", "接口不可用", LocalDateTime.now().minusMinutes(5));
    when(assets.findByMonitoringMode("HERTZBEAT")).thenReturn(List.of(asset));
    when(client.monitorStatus(321L)).thenReturn(new HertzBeatClient.MonitorStatus("UP", LocalDateTime.now(), false));
    when(client.alerts("firing")).thenReturn(List.of());
    when(client.alerts("resolved")).thenReturn(List.of(new HertzBeatClient.ExternalAlert("alert-1", 321L,
        "CRITICAL", "接口已恢复", LocalDateTime.now().minusMinutes(5), LocalDateTime.now())));
    when(alerts.findByExternalSourceAndExternalAlertId("HERTZBEAT", "alert-1")).thenReturn(Optional.of(existing));

    MonitoringProjectionService.ProjectionResult result = service.sync(LocalDateTime.now());

    assertThat(result.alertsUpdated()).isEqualTo(1);
    assertThat(existing.getExternalLifecycle()).isEqualTo("RESOLVED");
  }

  private Asset asset() {
    return new Asset("订单接口", "订单平台", "生产", "HTTP", "https://example.com/health", "UNKNOWN");
  }

  @Test
  void 受管资产标签应关联真实告警且拒绝冲突监控ID() throws Exception {
    Asset asset = asset();
    org.springframework.test.util.ReflectionTestUtils.setField(asset, "id", 77L);
    asset.markSynced(321L, "同步成功");
    when(assets.findByMonitoringMode("HERTZBEAT")).thenReturn(List.of(asset));
    when(client.monitorStatus(321L)).thenReturn(new HertzBeatClient.MonitorStatus("UP", LocalDateTime.now(), false));
    when(client.alerts("firing")).thenReturn(List.of(
        new HertzBeatClient.ExternalAlert("native-1", 0, "critical", "标签关联告警", LocalDateTime.now(), null, 77L),
        new HertzBeatClient.ExternalAlert("conflict", 999, "critical", "冲突标签告警", LocalDateTime.now(), null, 77L)));
    when(client.alerts("resolved")).thenReturn(List.of());

    MonitoringProjectionService.ProjectionResult result = service.sync(LocalDateTime.now());

    assertThat(result.alertsUpdated()).isEqualTo(1);
    org.mockito.ArgumentCaptor<com.lqbz.ops.domain.Alert> saved = org.mockito.ArgumentCaptor.forClass(com.lqbz.ops.domain.Alert.class);
    org.mockito.Mockito.verify(alerts).save(saved.capture());
    assertThat(saved.getValue().getExternalMonitorId()).isEqualTo(321L);
    assertThat(saved.getValue().getExternalAlertId()).isEqualTo("native-1");
  }
}
