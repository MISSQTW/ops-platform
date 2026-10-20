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
        new MonitoringPolicyMigrationService(assets, plans, bindings).dryRun();

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
    when(plans.findById(10L)).thenReturn(java.util.Optional.of(plan));
    when(assets.findById(20L)).thenReturn(java.util.Optional.of(asset));
    when(assets.countByHertzbeatMonitorId(123L)).thenReturn(2L);

    InspectionPlanPolicyService service = new InspectionPlanPolicyService(projects, assets, plans, bindings);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claimExisting(
        List.of(new InspectionPlanPolicyService.MigrationClaim(10L, 20L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Monitor 不能唯一证明服务归属，禁止认领");
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
    when(plans.findById(30L)).thenReturn(java.util.Optional.of(plan));
    when(assets.findById(40L)).thenReturn(java.util.Optional.of(asset));

    InspectionPlanPolicyService service = new InspectionPlanPolicyService(projects, assets, plans, bindings);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claimExisting(
        List.of(new InspectionPlanPolicyService.MigrationClaim(30L, 40L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("只能认领到启用中的巡检计划");
  }
}
