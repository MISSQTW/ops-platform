package com.lqbz.ops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.lqbz.ops.domain.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class InspectionExecutionIdentityTest {

    @Test
    void 同名服务分别按自己的检查结果更新状态() {
        Fixture fixture = new Fixture();
        Asset good = asset(1L, "good"), bad = asset(2L, "bad");
        fixture.configure(com.lqbz.ops.compat.Java8.list(good, bad), com.lqbz.ops.compat.Java8.list(InspectionPlanBinding.managed(1L, 1L, 1), InspectionPlanBinding.managed(1L, 2L, 1)));
        InspectionExecutionService service = fixture.service(asset -> new InspectionExecutionService.AddressTestResult(asset.getEndpoint().equals("good"), "受控结果", 1));
        try {
            service.execute(1L);
            assertThat(good.getStatus()).isEqualTo("HEALTHY");
            assertThat(bad.getStatus()).isEqualTo("CRITICAL");
        } finally {
            service.closeExecutors();
        }
    }

    @Test
    void 待释放绑定不会执行检查或写回服务状态() {
        Fixture fixture = new Fixture();
        Asset active = asset(1L, "active"), pending = asset(2L, "pending");
        InspectionPlanBinding pendingBinding = InspectionPlanBinding.managed(1L, 2L, 1);
        pendingBinding.requestRelease(2);
        fixture.configure(com.lqbz.ops.compat.Java8.list(active, pending), com.lqbz.ops.compat.Java8.list(InspectionPlanBinding.managed(1L, 1L, 1), pendingBinding));
        Queue<Long> checked = new ConcurrentLinkedQueue<>();
        InspectionExecutionService service = fixture.service(asset -> {
            checked.add(asset.getId());
            return new InspectionExecutionService.AddressTestResult(true, "受控结果", 1);
        });
        try {
            Inspection inspection = service.execute(1L);
            assertThat(checked).containsExactly(1L);
            assertThat(inspection.getTotalChecks()).isEqualTo(1);
            assertThat(active.getStatus()).isEqualTo("HEALTHY");
            assertThat(pending.getStatus()).isEqualTo("UNKNOWN");
            verify(fixture.assets, never()).save(pending);
        } finally {
            service.closeExecutors();
        }
    }

    @Test
    void 真实计划执行入口在慢服务未释放前已启动快服务() throws Exception {
        Fixture fixture = new Fixture();
        Asset slow = asset(1L, "slow"), fast = asset(2L, "fast");
        fixture.configure(com.lqbz.ops.compat.Java8.list(slow, fast), com.lqbz.ops.compat.Java8.list(InspectionPlanBinding.managed(1L, 1L, 1), InspectionPlanBinding.managed(1L, 2L, 1)));
        CountDownLatch slowEntered = new CountDownLatch(1), fastEntered = new CountDownLatch(1), releaseSlow = new CountDownLatch(1);
        InspectionExecutionService service = fixture.service(asset -> {
            try {
                if (asset == slow) {
                    slowEntered.countDown();
                    if (!releaseSlow.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("慢服务未按测试约定释放");
                } else {
                    if (!slowEntered.await(2, TimeUnit.SECONDS))
                        throw new AssertionError("慢服务未进入检查");
                    fastEntered.countDown();
                }
                return new InspectionExecutionService.AddressTestResult(true, "受控结果", 1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("检查线程意外中断", exception);
            }
        });
        try (com.lqbz.ops.testutil.Java8TestSupport.ClosingExecutor caller = com.lqbz.ops.testutil.Java8TestSupport.executor()) {
            Future<Inspection> execution = caller.submit(() -> service.execute(1L));
            try {
                assertThat(slowEntered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(fastEntered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(releaseSlow.getCount()).isEqualTo(1);
                assertThat(execution.isDone()).isFalse();
            } finally {
                releaseSlow.countDown();
            }
            Inspection inspection = execution.get(3, TimeUnit.SECONDS);
            assertThat(inspection.getStatus()).isEqualTo("PASSED");
            assertThat(inspection.getTotalChecks()).isEqualTo(2);
            assertThat(slow.getStatus()).isEqualTo("HEALTHY");
            assertThat(fast.getStatus()).isEqualTo("HEALTHY");
        } finally {
            releaseSlow.countDown();
            service.closeExecutors();
        }
    }

    private Asset asset(long id, String endpoint) {
        Asset asset = new Asset("同名服务", "测试", "测试", "REDIS", endpoint, "UNKNOWN");
        ReflectionTestUtils.setField(asset, "id", id);
        return asset;
    }

    // 仅替代持久化和网络边界，计划选择、并发执行与结果归属均调用真实服务逻辑。
    private static final class Fixture {

        final AssetRepository assets = mock(AssetRepository.class);

        final InspectionPlanRepository plans = mock(InspectionPlanRepository.class);

        final InspectionRepository inspections = mock(InspectionRepository.class);

        final InspectionItemRepository items = mock(InspectionItemRepository.class);

        final InspectionPlanBindingRepository bindings = mock(InspectionPlanBindingRepository.class);

        void configure(List<Asset> projectAssets, List<InspectionPlanBinding> planBindings) {
            InspectionPlan plan = InspectionPlan.monitoring("身份隔离", 1L, "测试", 60, 1, "WARNING");
            ReflectionTestUtils.setField(plan, "id", 1L);
            when(plans.findById(1L)).thenReturn(Optional.of(plan));
            when(assets.findByProjectNameOrderById("测试")).thenReturn(projectAssets);
            for (Asset asset : projectAssets) when(assets.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(bindings.findByPlanIdOrderById(1L)).thenReturn(planBindings);
            when(inspections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        }

        InspectionExecutionService service(Function<Asset, InspectionExecutionService.AddressTestResult> network) {
            return new InspectionExecutionService(plans, assets, inspections, items, bindings) {

                @Override
                public AddressTestResult testAddress(Asset asset) {
                    return network.apply(asset);
                }
            };
        }
    }
}
