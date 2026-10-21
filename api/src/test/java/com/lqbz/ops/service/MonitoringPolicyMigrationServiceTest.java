package com.lqbz.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.InspectionPlan;
import com.lqbz.ops.domain.InspectionPlanRepository;
import com.lqbz.ops.domain.InspectionPlanBindingRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonitoringPolicyMigrationServiceTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "0,true,false,60,UNIQUE_MONITOR_WITHOUT_PLAN",
      "1,false,false,60,LEGACY_PLAN_WITHOUT_MONITOR",
      "2,true,false,60,MULTIPLE_LEGACY_PLANS",
      "1,true,false,120,FREQUENCY_CONFLICT",
      "1,true,true,60,MONITOR_MISSING",
      "1,true,false,60,LEGACY_MONITOR_MATCHED"
  })
  void 报告应区分旧计划与外部监控的迁移场景(int legacyCount, boolean hasMonitor,
      boolean missing, int externalInterval, String expectedReason) throws Exception {
    AssetRepository assets = mock(AssetRepository.class);
    InspectionPlanRepository plans = mock(InspectionPlanRepository.class);
    InspectionPlanBindingRepository bindings = mock(InspectionPlanBindingRepository.class);
    var client = mock(com.lqbz.ops.integration.HertzBeatClient.class);
    Asset asset = new Asset("订单接口", "订单系统", "生产", "HTTP", "https://example.com", "UNKNOWN");
    if (hasMonitor) asset.markSynced(123L, "已同步");
    when(assets.findAll()).thenReturn(List.of(asset));
    when(plans.findAll()).thenReturn(java.util.stream.IntStream.range(0, legacyCount)
        .mapToObj(index -> new InspectionPlan("旧计划" + index, "订单系统", "全部服务", "每 1 分钟",
            "ENABLED", LocalDateTime.now())).toList());
    when(bindings.findByActiveOwnerKey(null)).thenReturn(java.util.Optional.empty());
    if (missing) {
      when(client.getMonitorConfig(123L)).thenThrow(
          new com.lqbz.ops.integration.HertzBeatClient.MonitorNotFoundException(123L));
    } else {
      when(client.getMonitorConfig(123L)).thenReturn(new com.lqbz.ops.integration.HertzBeatClient.MonitorConfig(
          123L, "订单接口", "website", "example.com:443", externalInterval, "fixed", true,
          java.util.Map.of(), List.of()));
    }

    var report = new MonitoringPolicyMigrationService(assets, plans, bindings, client).dryRun();

    assertThat(report.items()).singleElement().satisfies(item -> {
      assertThat(item.reason()).isEqualTo(expectedReason);
      assertThat(item.status()).isEqualTo("MIGRATION_REQUIRED");
      assertThat(item.legacyPlanIds()).hasSize(legacyCount);
      if ("FREQUENCY_CONFLICT".equals(expectedReason)) {
        assertThat(item.legacyIntervalSeconds()).isEqualTo(60);
        assertThat(item.monitorIntervalSeconds()).isEqualTo(120);
      }
    });
    if (hasMonitor && legacyCount <= 1) org.mockito.Mockito.verify(client).getMonitorConfig(123L);
    org.mockito.Mockito.verifyNoMoreInteractions(client);
  }

  @Test
  void 已有Monitor但没有唯一计划时只应报告待迁移() {
    AssetRepository assets = mock(AssetRepository.class);
    InspectionPlanRepository plans = mock(InspectionPlanRepository.class);
    InspectionPlanBindingRepository bindings = mock(InspectionPlanBindingRepository.class);
    Asset asset = new Asset("订单接口", "订单系统", "生产", "HTTP", "https://example.com/health", "UNKNOWN");
    asset.markSynced(123L, "已同步");
    when(assets.findAll()).thenReturn(List.of(asset));
    when(plans.findAll()).thenReturn(List.of(new InspectionPlan(
        "旧计划", "其他系统", "全部服务", "每 5 分钟", "ENABLED", LocalDateTime.now())));
    when(bindings.findByActiveOwnerKey(null)).thenReturn(java.util.Optional.empty());

    MonitoringPolicyMigrationService.MigrationReport report =
        new MonitoringPolicyMigrationService(assets, plans, bindings,
            mock(com.lqbz.ops.integration.HertzBeatClient.class)).dryRun();

    assertThat(report.items()).singleElement().satisfies(item -> {
      assertThat(item.serviceName()).isEqualTo("订单接口");
      assertThat(item.status()).isEqualTo("MIGRATION_REQUIRED");
      assertThat(item.action()).contains("保留现有 Monitor");
    });
  }

  @Test
  void 重复Monitor不能在确认批次中被认领() {
    AssetRepository assets = mock(AssetRepository.class);
    InspectionPlanRepository plans = mock(InspectionPlanRepository.class);
    InspectionPlanBindingRepository bindings = mock(InspectionPlanBindingRepository.class);
    com.lqbz.ops.domain.ProjectRepository projects = mock(com.lqbz.ops.domain.ProjectRepository.class);
    Asset asset = new Asset("订单接口", "订单系统", "生产", "HTTP", "https://example.com/health", "UNKNOWN");
    asset.markSynced(123L, "已同步");
    InspectionPlan plan = InspectionPlan.monitoring("订单巡检", 1L, "订单系统", 60, 2, "CRITICAL");
    when(plans.findByIdForUpdate(10L)).thenReturn(java.util.Optional.of(plan));
    when(assets.findById(20L)).thenReturn(java.util.Optional.of(asset));
    when(assets.countByHertzbeatMonitorId(123L)).thenReturn(2L);

    InspectionPlanPolicyService service = new InspectionPlanPolicyService(projects, assets, plans, bindings,
        mock(com.lqbz.ops.domain.MonitoringConfigTaskRepository.class),
        mock(org.springframework.context.ApplicationEventPublisher.class), mock(MonitoringMigrationTopologyLock.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claimExisting(
        List.of(new InspectionPlanPolicyService.MigrationClaim(10L, 20L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("迁移认领入口已停用，请使用迁移提案确认");
  }

  @Test
  void 停用计划不能接收迁移认领() {
    AssetRepository assets = mock(AssetRepository.class);
    InspectionPlanRepository plans = mock(InspectionPlanRepository.class);
    InspectionPlanBindingRepository bindings = mock(InspectionPlanBindingRepository.class);
    com.lqbz.ops.domain.ProjectRepository projects = mock(com.lqbz.ops.domain.ProjectRepository.class);
    Asset asset = new Asset("支付接口", "支付系统", "生产", "HTTP", "https://example.com/health", "UNKNOWN");
    asset.markSynced(456L, "已同步");
    InspectionPlan plan = InspectionPlan.monitoring("停用巡检", 1L, "支付系统", 60, 2, "CRITICAL");
    plan.configureStatus("DISABLED");
    when(plans.findByIdForUpdate(30L)).thenReturn(java.util.Optional.of(plan));
    when(assets.findById(40L)).thenReturn(java.util.Optional.of(asset));

    InspectionPlanPolicyService service = new InspectionPlanPolicyService(projects, assets, plans, bindings,
        mock(com.lqbz.ops.domain.MonitoringConfigTaskRepository.class),
        mock(org.springframework.context.ApplicationEventPublisher.class), mock(MonitoringMigrationTopologyLock.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claimExisting(
        List.of(new InspectionPlanPolicyService.MigrationClaim(30L, 40L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("迁移认领入口已停用，请使用迁移提案确认");
  }
}
