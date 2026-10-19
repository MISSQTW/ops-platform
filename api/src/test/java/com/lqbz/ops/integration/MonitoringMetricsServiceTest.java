package com.lqbz.ops.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.MonitoringMetricSnapshot;
import com.lqbz.ops.domain.MonitoringMetricSnapshotRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MonitoringMetricsServiceTest {
  @Test
  void HertzBeat失败时应返回最后有效指标() throws Exception {
    HertzBeatClient client = mock(HertzBeatClient.class);
    MonitoringMetricSnapshotRepository snapshots = mock(MonitoringMetricSnapshotRepository.class);
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://example.com", "UNKNOWN");
    asset.markSynced(321L, "同步成功");
    MonitoringMetricSnapshot snapshot = new MonitoringMetricSnapshot(1L);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    snapshot.update(mapper.writeValueAsString(List.of(MonitoringMetric.duration("responseTime", 25, "ms", LocalDateTime.now()))),
        "[]", LocalDateTime.now(), LocalDateTime.now());
    when(client.metricTarget(asset)).thenThrow(new IllegalStateException("连接失败"));
    when(snapshots.findByAssetId(asset.getId())).thenReturn(Optional.of(snapshot));

    MonitoringMetricsService.Result result = new MonitoringMetricsService(client, snapshots, mapper).load(asset);

    assertThat(result.cached()).isTrue();
    assertThat(result.latest()).extracting(metric -> metric.value().longValue()).containsExactly(25L);
    assertThat(result.stale()).isFalse();
    assertThat(result.collectedAt()).isNotNull();
  }

  @Test
  void 缓存指标超过阈值时应独立标记陈旧() throws Exception {
    HertzBeatClient client = mock(HertzBeatClient.class);
    MonitoringMetricSnapshotRepository snapshots = mock(MonitoringMetricSnapshotRepository.class);
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://example.com", "UNKNOWN");
    asset.markSynced(321L, "同步成功");
    MonitoringMetricSnapshot snapshot = new MonitoringMetricSnapshot(1L);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    LocalDateTime collectedAt = LocalDateTime.now().minusMinutes(10);
    snapshot.update("[]", "[]", collectedAt, LocalDateTime.now().minusMinutes(9));
    when(client.metricTarget(asset)).thenThrow(new IllegalStateException("连接失败"));
    when(snapshots.findByAssetId(asset.getId())).thenReturn(Optional.of(snapshot));

    MonitoringMetricsService.Result result = new MonitoringMetricsService(client, snapshots, mapper).load(asset);

    assertThat(result.stale()).isTrue();
    assertThat(result.collectedAt()).isEqualTo(collectedAt);
  }
}
