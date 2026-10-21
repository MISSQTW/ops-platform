package com.lqbz.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqbz.ops.domain.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MonitoringMigrationConfirmationTest {
  private final AssetRepository assets = mock(AssetRepository.class);
  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final MonitoringMigrationProposalRepository proposals = mock(MonitoringMigrationProposalRepository.class);
  private final MonitoringPolicyMigrationService reports = mock(MonitoringPolicyMigrationService.class);
  private final InspectionPlanPolicyService policies = mock(InspectionPlanPolicyService.class);
  private final MonitoringMigrationConfirmationService service = new MonitoringMigrationConfirmationService(
      assets, projects, proposals, reports, policies, new ObjectMapper(), mock(MonitoringMigrationTopologyLock.class));

  @Test
  void 准备迁移保存提案并创建待确认计划() {
    fixture("LEGACY_PLAN_WITHOUT_MONITOR", 60, null);
    when(proposals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var proposal = service.prepare(1L, 2L, null);

    assertThat(proposal.getIntervalSeconds()).isEqualTo(60);
    assertThat(proposal.getStatus()).isEqualTo("PENDING_CONFIRMATION");
    verify(policies).prepareMigration(2L, 1L, "订单接口迁移巡检", 60);
    verify(policies, never()).create(any());
  }

  @Test
  void 冲突频率没有明确选择时不得创建提案() {
    fixture("FREQUENCY_CONFLICT", 60, 120);

    assertThatThrownBy(() -> service.prepare(1L, 2L, null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("明确选择");
    verify(proposals, never()).save(any());
    verifyNoInteractions(policies);
  }

  @Test
  void 确认时现场变化不得创建计划() {
    fixture("LEGACY_PLAN_WITHOUT_MONITOR", 60, null);
    when(proposals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var proposal = service.prepare(1L, 2L, null);
    clearInvocations(policies);
    when(proposals.findByIdForUpdate(3L)).thenReturn(Optional.of(proposal));
    fixture("LEGACY_PLAN_WITHOUT_MONITOR", 300, null);

    assertThatThrownBy(() -> service.confirm(3L)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("现场已变化");
    verifyNoInteractions(policies);
  }

  private void fixture(String reason, Integer legacyInterval, Integer externalInterval) {
    var prepared = new InspectionPlanPolicyService.InspectionPlanView(4L, "待确认计划", 2L, "订单系统",
        "", "每 60 秒", "PENDING_CONFIRMATION", null, 60, 2, "CRITICAL", "ACTIVE", "NOT_SYNCED",
        1L, null, null, null, null, List.of(1L));
    when(policies.prepareMigration(any(), any(), any(), anyInt())).thenReturn(prepared);
    Asset asset = new Asset("订单接口", "订单系统", "生产", "HTTP", "https://example.com", "UNKNOWN");
    Project project = new Project("订单系统", "负责人", "测试", "RUNNING");
    when(assets.findById(1L)).thenReturn(Optional.of(asset));
    when(projects.findById(2L)).thenReturn(Optional.of(project));
    when(reports.inspect(1L)).thenReturn(
        new MonitoringPolicyMigrationService.MigrationItem(1L, "订单接口", "MIGRATION_REQUIRED", "待确认",
            reason, "订单系统", null, List.of(10L), legacyInterval, externalInterval));
  }
}
