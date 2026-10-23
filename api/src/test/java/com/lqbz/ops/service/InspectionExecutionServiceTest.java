package com.lqbz.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.Inspection;
import com.lqbz.ops.domain.InspectionItemRepository;
import com.lqbz.ops.domain.InspectionPlan;
import com.lqbz.ops.domain.InspectionPlanBinding;
import com.lqbz.ops.domain.InspectionPlanBindingRepository;
import com.lqbz.ops.domain.InspectionPlanRepository;
import com.lqbz.ops.domain.InspectionRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

class InspectionExecutionServiceTest {

    private final InspectionExecutionService service = new InspectionExecutionService(null, null, null, null, null);

    @Test
    void 应支持任意分钟和小时间隔() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 19, 9, 0);
        assertThat(service.calculateNextRun("每 30 秒", now)).isEqualTo(now.plusSeconds(30));
        assertThat(service.calculateNextRun("每 7 分钟", now)).isEqualTo(now.plusMinutes(7));
        assertThat(service.calculateNextRun("每 3 小时", now)).isEqualTo(now.plusHours(3));
    }

    @Test
    void 新监控计划应立即进入本地兜底调度() {
        InspectionPlan plan = InspectionPlan.monitoring("核心服务巡检", 1L, "支付系统", 60, 3, "CRITICAL");
        assertThat(plan.getNextRunAt()).isNotNull();
        assertThat(plan.getNextRunAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    void 本地兜底扫描周期不得超过最小巡检频率() throws Exception {
        Scheduled scheduled = InspectionExecutionService.class.getMethod("executeDuePlans").getAnnotation(Scheduled.class);
        assertThat(scheduled.fixedDelayString()).isEqualTo("${ops.inspection.scheduler-delay-ms:10000}");
    }

    @Test
    void 应支持每天定时和Cron表达式() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 19, 9, 5);
        assertThat(service.calculateNextRun("每天 10:30", now)).isEqualTo(LocalDateTime.of(2026, 10, 19, 10, 30));
        assertThat(service.calculateNextRun("Cron: 0 */10 * * * *", now)).isEqualTo(LocalDateTime.of(2026, 10, 19, 9, 10));
    }

    @Test
    void 监控和规则均同步后周期执行应跳过本地检查() {
        InspectionPlanRepository plans = mock(InspectionPlanRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        InspectionRepository inspections = mock(InspectionRepository.class);
        InspectionItemRepository inspectionItems = mock(InspectionItemRepository.class);
        InspectionPlanBindingRepository bindings = mock(InspectionPlanBindingRepository.class);
        InspectionExecutionService execution = new InspectionExecutionService(plans, assets, inspections, inspectionItems, bindings);
        InspectionPlan plan = monitoringPlan(11L);
        Asset asset = asset(21L, "UNKNOWN");
        InspectionPlanBinding binding = InspectionPlanBinding.managed(11L, 21L, plan.getDesiredVersion());
        binding.markSynchronized(31L, 41L, true, LocalDateTime.now());
        when(plans.findByStatusAndNextRunAtLessThanEqualOrderByNextRunAt(any(), any())).thenReturn(com.lqbz.ops.compat.Java8.list(plan));
        when(assets.findByProjectNameOrderById("支付系统")).thenReturn(com.lqbz.ops.compat.Java8.list(asset));
        when(bindings.findByPlanIdOrderById(11L)).thenReturn(com.lqbz.ops.compat.Java8.list(binding));
        execution.executeDuePlans();
        verify(inspections, never()).save(any());
        verify(inspectionItems, never()).save(any());
        assertThat(asset.getStatus()).isEqualTo("UNKNOWN");
        assertThat(plan.getNextRunAt()).isNotNull();
    }

    @Test
    void 空项目手动执行应明确提示没有服务() {
        InspectionPlanRepository plans = mock(InspectionPlanRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        InspectionPlan plan = new InspectionPlan("空计划", "空系统", "全部", "每 1 分钟", "ENABLED", LocalDateTime.now());
        when(plans.findById(1L)).thenReturn(java.util.Optional.of(plan));
        when(assets.findByProjectNameOrderById("空系统")).thenReturn(com.lqbz.ops.compat.Java8.list());
        InspectionExecutionService execution = new InspectionExecutionService(plans, assets, mock(InspectionRepository.class), mock(InspectionItemRepository.class), mock(InspectionPlanBindingRepository.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> execution.execute(1L)).isInstanceOf(IllegalStateException.class).hasMessage("计划所属项目没有可执行服务，请先添加服务");
    }

    @Test
    void 同步失败服务应继续本地周期检查避免监控空窗() {
        InspectionPlanRepository plans = mock(InspectionPlanRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        InspectionRepository inspections = mock(InspectionRepository.class);
        InspectionItemRepository inspectionItems = mock(InspectionItemRepository.class);
        InspectionPlanBindingRepository bindings = mock(InspectionPlanBindingRepository.class);
        InspectionExecutionService execution = new InspectionExecutionService(plans, assets, inspections, inspectionItems, bindings);
        InspectionPlan plan = monitoringPlan(12L);
        Asset asset = asset(22L, "HEALTHY");
        InspectionPlanBinding binding = InspectionPlanBinding.managed(12L, 22L, plan.getDesiredVersion());
        binding.markMonitorSynchronized(32L, true, LocalDateTime.now());
        binding.markRuleFailed("规则同步失败", LocalDateTime.now());
        when(plans.findByStatusAndNextRunAtLessThanEqualOrderByNextRunAt(any(), any())).thenReturn(com.lqbz.ops.compat.Java8.list(plan));
        when(assets.findByProjectNameOrderById("支付系统")).thenReturn(com.lqbz.ops.compat.Java8.list(asset));
        when(bindings.findByPlanIdOrderById(12L)).thenReturn(com.lqbz.ops.compat.Java8.list(binding));
        when(inspections.save(any())).thenAnswer(invocation -> {
            Inspection inspection = invocation.getArgument(0);
            ReflectionTestUtils.setField(inspection, "id", 52L);
            return inspection;
        });
        execution.executeDuePlans();
        verify(inspections).save(any());
        verify(inspectionItems).save(any());
        assertThat(asset.getStatus()).isEqualTo("CRITICAL");
    }

    @Test
    void 计划版本变化后应恢复本地周期检查直到新配置同步完成() {
        InspectionPlanRepository plans = mock(InspectionPlanRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        InspectionRepository inspections = mock(InspectionRepository.class);
        InspectionItemRepository inspectionItems = mock(InspectionItemRepository.class);
        InspectionPlanBindingRepository bindings = mock(InspectionPlanBindingRepository.class);
        InspectionExecutionService execution = new InspectionExecutionService(plans, assets, inspections, inspectionItems, bindings);
        InspectionPlan plan = monitoringPlan(13L);
        InspectionPlanBinding binding = InspectionPlanBinding.managed(13L, 24L, plan.getDesiredVersion());
        binding.markSynchronized(34L, 44L, true, LocalDateTime.now());
        plan.reconfigure(plan.getName(), 300, 3, "CRITICAL", "ENABLED");
        Asset asset = asset(24L, "HEALTHY");
        when(plans.findByStatusAndNextRunAtLessThanEqualOrderByNextRunAt(any(), any())).thenReturn(com.lqbz.ops.compat.Java8.list(plan));
        when(assets.findByProjectNameOrderById("支付系统")).thenReturn(com.lqbz.ops.compat.Java8.list(asset));
        when(bindings.findByPlanIdOrderById(13L)).thenReturn(com.lqbz.ops.compat.Java8.list(binding));
        when(inspections.save(any())).thenAnswer(invocation -> {
            Inspection inspection = invocation.getArgument(0);
            ReflectionTestUtils.setField(inspection, "id", 53L);
            return inspection;
        });
        execution.executeDuePlans();
        verify(inspections).save(any());
        verify(inspectionItems).save(any());
    }

    @Test
    void 地址测试不得覆盖HertzBeat权威状态() {
        AssetRepository assets = mock(AssetRepository.class);
        Asset asset = asset(23L, "HEALTHY");
        asset.markSynced(33L, "已同步");
        when(assets.findById(23L)).thenReturn(java.util.Optional.of(asset));
        InspectionExecutionService execution = new InspectionExecutionService(null, assets, null, null, null);
        InspectionExecutionService.AddressTestResult result = execution.testAddress(23L);
        assertThat(result.passed()).isFalse();
        assertThat(asset.getStatus()).isEqualTo("HEALTHY");
    }

    private InspectionPlan monitoringPlan(Long id) {
        InspectionPlan plan = InspectionPlan.monitoring("核心服务巡检", 1L, "支付系统", 60, 3, "CRITICAL");
        ReflectionTestUtils.setField(plan, "id", id);
        return plan;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { true, false })
    void 已同步计划手动执行应生成记录且不改变定时周期和权威状态(boolean passed) {
        InspectionPlanRepository plans = mock(InspectionPlanRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        InspectionRepository inspections = mock(InspectionRepository.class);
        InspectionItemRepository inspectionItems = mock(InspectionItemRepository.class);
        InspectionPlanBindingRepository bindings = mock(InspectionPlanBindingRepository.class);
        InspectionExecutionService execution = org.mockito.Mockito.spy(new InspectionExecutionService(plans, assets, inspections, inspectionItems, bindings));
        InspectionPlan plan = monitoringPlan(15L);
        Asset managedAsset = asset(25L, "HEALTHY");
        managedAsset.markSynced(35L, "已同步");
        Asset unrelatedAsset = asset(26L, "HEALTHY");
        org.mockito.Mockito.doReturn(new InspectionExecutionService.AddressTestResult(passed, "即时检查结果", 1L)).when(execution).testAddress(managedAsset);
        InspectionPlanBinding binding = InspectionPlanBinding.managed(15L, 25L, plan.getDesiredVersion());
        binding.markSynchronized(35L, 45L, true, LocalDateTime.now());
        LocalDateTime nextRun = plan.getNextRunAt();
        when(plans.findById(15L)).thenReturn(java.util.Optional.of(plan));
        when(assets.findByProjectNameOrderById("支付系统")).thenReturn(com.lqbz.ops.compat.Java8.list(managedAsset, unrelatedAsset));
        when(bindings.findByPlanIdOrderById(15L)).thenReturn(com.lqbz.ops.compat.Java8.list(binding));
        when(inspections.save(any())).thenAnswer(invocation -> {
            Inspection inspection = invocation.getArgument(0);
            ReflectionTestUtils.setField(inspection, "id", 55L);
            return inspection;
        });
        Inspection inspection = execution.execute(15L);
        assertThat(inspection.getTotalChecks()).isEqualTo(1);
        assertThat(inspection.getStatus()).isEqualTo(passed ? "PASSED" : "FAILED");
        verify(execution).testAddress(managedAsset);
        verify(execution, never()).testAddress(unrelatedAsset);
        verify(inspectionItems).save(any());
        assertThat(managedAsset.getStatus()).isEqualTo("HEALTHY");
        assertThat(unrelatedAsset.getStatus()).isEqualTo("HEALTHY");
        assertThat(plan.getNextRunAt()).isEqualTo(nextRun);
    }

    @Test
    void 停用计划不能立即执行() {
        InspectionPlanRepository plans = mock(InspectionPlanRepository.class);
        InspectionPlan plan = monitoringPlan(16L);
        plan.reconfigure(plan.getName(), 60, 3, "CRITICAL", "DISABLED");
        when(plans.findById(16L)).thenReturn(java.util.Optional.of(plan));
        InspectionExecutionService execution = new InspectionExecutionService(plans, null, null, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> execution.execute(16L)).isInstanceOf(IllegalStateException.class).hasMessage("请先启用巡检计划");
    }

    @Test
    void 已删除计划不能立即执行() {
        InspectionPlanRepository plans = mock(InspectionPlanRepository.class);
        InspectionPlan plan = monitoringPlan(17L);
        ReflectionTestUtils.setField(plan, "lifecycleStatus", "DELETED");
        when(plans.findById(17L)).thenReturn(java.util.Optional.of(plan));
        InspectionExecutionService execution = new InspectionExecutionService(plans, null, null, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> execution.execute(17L)).isInstanceOf(java.util.NoSuchElementException.class).hasMessage("巡检计划不存在");
    }

    private Asset asset(Long id, String status) {
        Asset asset = new Asset("订单服务", "支付系统", "生产", "SERVER", "127.0.0.1:1", status);
        ReflectionTestUtils.setField(asset, "id", id);
        return asset;
    }
}
