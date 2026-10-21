package com.lqbz.ops.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lqbz.ops.domain.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;

class MonitoringMigrationAdmissionTest {
  @ParameterizedTest
  @CsvSource({"UNKNOWN,localhost:1234", "HTTP,not-an-address"})
  void 迁移认领必须拒绝无效监控契约(String type, String endpoint) {
    AssetRepository assets = mock(AssetRepository.class);
    InspectionPlanRepository plans = mock(InspectionPlanRepository.class);
    InspectionPlanBindingRepository bindings = mock(InspectionPlanBindingRepository.class);
    Asset asset = new Asset("迁移接口", "迁移系统", "生产", type, endpoint, "UNKNOWN");
    asset.markSynced(123L, "已同步");
    InspectionPlan plan = InspectionPlan.monitoring("迁移巡检", 1L, "迁移系统", 60, 2, "CRITICAL");
    when(plans.findByIdForUpdate(10L)).thenReturn(Optional.of(plan));
    when(assets.findById(20L)).thenReturn(Optional.of(asset));
    when(assets.countByHertzbeatMonitorId(123L)).thenReturn(1L);
    InspectionPlanPolicyService service = new InspectionPlanPolicyService(mock(ProjectRepository.class),
        assets, plans, bindings, mock(MonitoringConfigTaskRepository.class), mock(ApplicationEventPublisher.class),
        mock(MonitoringMigrationTopologyLock.class));

    assertThatThrownBy(() -> service.claimExisting(
        List.of(new InspectionPlanPolicyService.MigrationClaim(10L, 20L))))
        .isInstanceOf(IllegalArgumentException.class);
    verify(bindings, never()).save(any());
  }
}
