package com.lqbz.ops.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lqbz.ops.domain.Asset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonitoringIntegrationControllerTest {
  @Test
  void 资产同步接口应返回最新同步状态() {
    HertzBeatClient client = mock(HertzBeatClient.class);
    AssetMonitoringSyncService syncService = mock(AssetMonitoringSyncService.class);
    Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");
    asset.markSynced(321L, "同步成功");
    when(syncService.sync(1L)).thenReturn(asset);

    MonitoringIntegrationController controller = new MonitoringIntegrationController(client, syncService);

    assertThat(controller.syncAsset(1L).getHertzbeatMonitorId()).isEqualTo(321L);
  }

  @Test
  void 一键同步接口应返回汇总结果() {
    HertzBeatClient client = mock(HertzBeatClient.class);
    AssetMonitoringSyncService syncService = mock(AssetMonitoringSyncService.class);
    when(syncService.syncAll()).thenReturn(new AssetMonitoringSyncService.SyncAllResult(3, 2, 1, List.of()));

    MonitoringIntegrationController controller = new MonitoringIntegrationController(client, syncService);

    assertThat(controller.syncAllAssets().succeeded()).isEqualTo(2);
    assertThat(controller.syncAllAssets().failed()).isEqualTo(1);
  }
}
