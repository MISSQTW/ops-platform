package com.lqbz.ops.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.MonitoringSyncRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MonitoringIntegrationControllerTest {
  @Test
  void 单服务旧同步入口必须拒绝配置写入() {
    AssetMonitoringSyncService syncService = mock(AssetMonitoringSyncService.class);
    assertThatThrownBy(() -> controller(syncService).syncAsset(1L))
        .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
            assertThat(exception.getStatusCode().value()).isEqualTo(410));
    verifyNoInteractions(syncService);
  }

  @Test
  void 批量服务旧同步入口必须拒绝配置写入() {
    AssetMonitoringSyncService syncService = mock(AssetMonitoringSyncService.class);
    assertThatThrownBy(() -> controller(syncService).syncAllAssets())
        .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
            assertThat(exception.getStatusCode().value()).isEqualTo(410));
    verifyNoInteractions(syncService);
  }

  private MonitoringIntegrationController controller(AssetMonitoringSyncService syncService) {
    return new MonitoringIntegrationController(mock(HertzBeatClient.class), syncService,
        mock(MonitoringSyncCoordinator.class), mock(MonitoringSyncRunRepository.class),
        mock(AssetRepository.class), mock(MonitoringMetricsService.class));
  }
}
