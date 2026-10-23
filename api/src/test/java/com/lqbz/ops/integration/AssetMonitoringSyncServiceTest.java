package com.lqbz.ops.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssetMonitoringSyncServiceTest {

    private final AssetRepository assets = mock(AssetRepository.class);

    private final HertzBeatClient hertzBeatClient = mock(HertzBeatClient.class);

    private final AssetMonitoringSyncService service = new AssetMonitoringSyncService(assets, hertzBeatClient);

    @Test
    void 同步成功应保存监控ID和同步状态() throws Exception {
        Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");
        when(assets.findById(1L)).thenReturn(Optional.of(asset));
        when(hertzBeatClient.sync(asset)).thenReturn(new HertzBeatClient.SyncResult(321L, "同步成功"));
        Asset result = service.sync(1L);
        assertThat(result.getHertzbeatMonitorId()).isEqualTo(321L);
        assertThat(result.getSyncStatus()).isEqualTo("SYNCED");
        assertThat(result.getSyncMessage()).isEqualTo("同步成功");
        assertThat(result.getLastSyncedAt()).isNotNull();
    }

    @Test
    void 同步失败应保留资产并记录失败原因() throws Exception {
        Asset asset = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");
        when(assets.findById(1L)).thenReturn(Optional.of(asset));
        when(hertzBeatClient.sync(asset)).thenThrow(new IllegalStateException("HertzBeat 创建监控失败"));
        Asset result = service.sync(1L);
        assertThat(result).isSameAs(asset);
        assertThat(result.getSyncStatus()).isEqualTo("FAILED");
        assertThat(result.getSyncMessage()).contains("创建监控失败");
    }

    @Test
    void 一键同步应处理全部资产且单个失败不影响其他资产() throws Exception {
        Asset success = new Asset("订单接口", "订单平台", "生产", "HTTP", "https://order.example.com/health", "UNKNOWN");
        Asset failed = new Asset("订单缓存", "订单平台", "生产", "REDIS", "cache.internal:6379", "UNKNOWN");
        when(assets.findAll()).thenReturn(com.lqbz.ops.compat.Java8.list(success, failed));
        when(hertzBeatClient.sync(success)).thenReturn(new HertzBeatClient.SyncResult(321L, "同步成功"));
        when(hertzBeatClient.sync(failed)).thenThrow(new IllegalStateException("连接失败"));
        AssetMonitoringSyncService.SyncAllResult result = service.syncAll();
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(success.getSyncStatus()).isEqualTo("SYNCED");
        assertThat(failed.getSyncStatus()).isEqualTo("FAILED");
    }
}
