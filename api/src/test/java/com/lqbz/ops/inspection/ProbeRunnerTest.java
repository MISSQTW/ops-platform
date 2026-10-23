package com.lqbz.ops.inspection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.lqbz.ops.domain.Asset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ProbeRunnerTest {

    @Test
    void 同服务互斥但慢服务不阻塞另一服务() throws Exception {
        InspectionProfileService profiles = mock(InspectionProfileService.class);
        Asset slow = new Asset("慢服务", "测试", "测试", "HTTP", "http://slow", "UNKNOWN");
        Asset fast = new Asset("快服务", "测试", "测试", "HTTP", "http://fast", "UNKNOWN");
        lombok.val settings = new ProbeSettings(com.lqbz.ops.compat.Java8.map(), com.lqbz.ops.compat.Java8.map());
        when(profiles.snapshot(1, false)).thenReturn(new InspectionProfileService.Snapshot(slow, 1, settings, null, null, null));
        when(profiles.snapshot(2, false)).thenReturn(new InspectionProfileService.Snapshot(fast, 1, settings, null, null, null));
        when(profiles.finish(any(), anyList(), eq(false), eq(false))).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ProtocolProbe probe = new ProtocolProbe() {

            public boolean supports(String type) {
                return true;
            }

            public List<ProbeCheck> check(Asset asset, ProbeSettings config) throws Exception {
                if (asset == slow) {
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                }
                return com.lqbz.ops.compat.Java8.list(new ProbeCheck("HTTP", "断言", "目标", true, "通过", 1));
            }
        };
        ProbeRunner runner = new ProbeRunner(profiles, new ProbeRegistry(com.lqbz.ops.compat.Java8.list(probe)));
        try (com.lqbz.ops.testutil.Java8TestSupport.ClosingExecutor client = com.lqbz.ops.testutil.Java8TestSupport.executor()) {
            Future<List<ProbeCheck>> first = client.submit(() -> runner.run(1, false, false));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(runner.run(1, false, false)).allMatch(c -> !c.passed());
                assertThat(runner.run(2, false, false)).allMatch(ProbeCheck::passed);
            } finally {
                release.countDown();
            }
            assertThat(first.get(3, TimeUnit.SECONDS)).allMatch(ProbeCheck::passed);
            verify(profiles, times(2)).finish(any(), anyList(), eq(false), eq(false));
        } finally {
            runner.close();
        }
    }

    @Test
    void 旧配置提交被拒绝时不能返回通过() {
        InspectionProfileService profiles = mock(InspectionProfileService.class);
        Asset asset = new Asset("测试", "测试", "测试", "HTTP", "http://test", "UNKNOWN");
        when(profiles.snapshot(1, true)).thenReturn(new InspectionProfileService.Snapshot(asset, 1, new ProbeSettings(com.lqbz.ops.compat.Java8.map(), com.lqbz.ops.compat.Java8.map()), 1L, 1L, 1L));
        when(profiles.finish(any(), anyList(), eq(true), eq(true))).thenReturn(false);
        ProtocolProbe probe = new ProtocolProbe() {

            public boolean supports(String type) {
                return true;
            }

            public List<ProbeCheck> check(Asset a, ProbeSettings s) {
                return com.lqbz.ops.compat.Java8.list(new ProbeCheck("HTTP", "检查", "目标", true, "通过", 1));
            }
        };
        ProbeRunner runner = new ProbeRunner(profiles, new ProbeRegistry(com.lqbz.ops.compat.Java8.list(probe)));
        try {
            assertThat(runner.run(1, true, true)).allMatch(c -> !c.passed());
        } finally {
            runner.close();
        }
    }

    @Test
    void 认证版本与执行快照不一致时拒绝且不触发协议检查() {
        InspectionProfileService profiles = mock(InspectionProfileService.class);
        Asset asset = new Asset("版本竞争", "测试", "测试", "HTTP", "http://test", "UNKNOWN");
        lombok.val snapshot = new InspectionProfileService.Snapshot(asset, 8, new ProbeSettings(com.lqbz.ops.compat.Java8.map(), com.lqbz.ops.compat.Java8.map()), 1L, 1L, 1L);
        when(profiles.snapshot(1, true)).thenReturn(snapshot);
        lombok.val probeCalls = new java.util.concurrent.atomic.AtomicInteger();
        ProtocolProbe probe = new ProtocolProbe() {

            public boolean supports(String type) {
                return true;
            }

            public List<ProbeCheck> check(Asset target, ProbeSettings settings) {
                probeCalls.incrementAndGet();
                return com.lqbz.ops.compat.Java8.list(new ProbeCheck("HTTP", "检查", "目标", true, "通过", 1));
            }
        };
        ProbeRunner runner = new ProbeRunner(profiles, new ProbeRegistry(com.lqbz.ops.compat.Java8.list(probe)));
        try {
            List<ProbeCheck> checks = runner.run(1, true, false, 7L);
            assertThat(checks).isNotEmpty().allMatch(check -> !check.passed());
            assertThat(probeCalls.get()).isZero();
            verify(profiles, never()).finish(any(), anyList(), anyBoolean(), anyBoolean());
        } finally {
            runner.close();
        }
    }

    @Test
    void 总预算超时后拒绝旧结果且真实任务退出前保留互斥() throws Exception {
        InspectionProfileService profiles = mock(InspectionProfileService.class);
        Asset asset = new Asset("预算测试", "测试", "测试", "HTTP", "http://test", "UNKNOWN");
        when(profiles.snapshot(1, false)).thenReturn(new InspectionProfileService.Snapshot(asset, 1, new ProbeSettings(com.lqbz.ops.compat.Java8.map(), com.lqbz.ops.compat.Java8.map()), null, null, null));
        CountDownLatch release = new CountDownLatch(1), exited = new CountDownLatch(1);
        ProtocolProbe probe = new ProtocolProbe() {

            public boolean supports(String type) {
                return true;
            }

            public List<ProbeCheck> check(Asset a, ProbeSettings settings) {
                try {
                    // 模拟不响应中断的第三方客户端，验证超时后不会提前归还并发名额。
                    while (release.getCount() > 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                        }
                    }
                    return com.lqbz.ops.compat.Java8.list(new ProbeCheck("HTTP", "检查", "目标", true, "通过", 1));
                } finally {
                    exited.countDown();
                }
            }
        };
        ProbeRunner runner = new ProbeRunner(profiles, new ProbeRegistry(com.lqbz.ops.compat.Java8.list(probe)));
        try {
            assertThat(runner.run(1, false, false)).anyMatch(c -> c.message().contains("总预算"));
            assertThat(runner.run(1, false, false)).anyMatch(c -> c.message().contains("忙碌"));
            verify(profiles, never()).finish(any(), anyList(), anyBoolean(), anyBoolean());
        } finally {
            release.countDown();
            assertThat(exited.await(3, TimeUnit.SECONDS)).isTrue();
            runner.close();
        }
    }
}
