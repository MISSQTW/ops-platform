package com.lqbz.ops.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AssetMonitoringStateTest {

    @Test
    void 同步后应切换为HertzBeat权威模式() {
        Asset asset = asset();
        asset.markSynced(321L, "同步成功");
        assertThat(asset.getMonitoringMode()).isEqualTo("HERTZBEAT");
        assertThat(asset.getMappingStatus()).isEqualTo("MANAGED");
        assertThat(asset.isMonitoringManaged()).isTrue();
    }

    @Test
    void 映射失效后本地巡检仍不能覆盖主状态() {
        Asset asset = asset();
        asset.markSynced(321L, "同步成功");
        asset.applyMonitoringStatus("UP", LocalDateTime.now(), LocalDateTime.now());
        asset.invalidateMonitoring("HertzBeat 监控不存在");
        asset.applyInspectionStatus("CRITICAL");
        assertThat(asset.getStatus()).isEqualTo("UNKNOWN");
        assertThat(asset.getMappingStatus()).isEqualTo("INVALIDATED");
    }

    @Test
    void 显式解绑为本地模式后巡检可以更新状态() {
        Asset asset = asset();
        asset.markSynced(321L, "同步成功");
        asset.unbindMonitoring();
        asset.applyInspectionStatus("HEALTHY");
        assertThat(asset.getStatus()).isEqualTo("HEALTHY");
        assertThat(asset.getMonitoringMode()).isEqualTo("LOCAL");
        assertThat(asset.getMappingStatus()).isEqualTo("UNBOUND");
    }

    @Test
    void 编辑HertzBeat管理资产时不能覆盖运行状态() {
        Asset asset = asset();
        asset.markSynced(321L, "同步成功");
        asset.applyMonitoringStatus("UP", LocalDateTime.now(), LocalDateTime.now());
        asset.update("新名称", "订单平台", "生产", "HTTP", "https://example.com/new", "CRITICAL");
        assertThat(asset.getStatus()).isEqualTo("UP");
        assertThat(asset.getName()).isEqualTo("新名称");
    }

    @Test
    void 同步失败应保留最后有效状态并在超时后标记陈旧() {
        Asset asset = asset();
        LocalDateTime successfulAt = LocalDateTime.of(2026, 10, 19, 10, 0);
        asset.markSynced(321L, "同步成功");
        asset.applyMonitoringStatus("UP", successfulAt, successfulAt);
        asset.markMonitoringSyncFailed(LocalDateTime.of(2026, 10, 19, 10, 4), "连接超时");
        assertThat(asset.getStatus()).isEqualTo("UP");
        assertThat(asset.isMonitoringStale()).isTrue();
        assertThat(asset.getLastSuccessfulSyncAt()).isEqualTo(successfulAt);
    }

    private Asset asset() {
        return new Asset("订单接口", "订单平台", "生产", "HTTP", "https://example.com/health", "UNKNOWN");
    }
}
