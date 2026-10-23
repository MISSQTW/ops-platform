package com.lqbz.ops.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AlertLifecycleTest {

    @Test
    void 旧单状态告警应映射为外部生命周期和平台处置双状态() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 10, 19, 10, 0);
        Alert firing = new Alert("CRITICAL", "订单平台", "订单接口", "接口不可用", "OPEN", occurredAt);
        Alert resolved = new Alert("INFO", "订单平台", "订单接口", "接口已恢复", "RESOLVED", occurredAt);
        assertThat(firing.getExternalLifecycle()).isEqualTo("FIRING");
        assertThat(firing.getHandlingStatus()).isEqualTo("OPEN");
        assertThat(firing.getFirstOccurredAt()).isEqualTo(occurredAt);
        assertThat(firing.getLastOccurredAt()).isEqualTo(occurredAt);
        assertThat(resolved.getExternalLifecycle()).isEqualTo("RESOLVED");
        assertThat(resolved.getHandlingStatus()).isEqualTo("OPEN");
        assertThat(resolved.getResolvedAt()).isEqualTo(occurredAt);
    }

    @Test
    void 外部恢复不能覆盖平台人工处置状态() {
        Alert alert = Alert.external("HERTZBEAT", "alert-1", 10L, "CRITICAL", "订单平台", "订单接口", "接口不可用", LocalDateTime.of(2026, 10, 19, 10, 0));
        alert.acknowledge();
        alert.resolve(LocalDateTime.of(2026, 10, 19, 10, 5));
        assertThat(alert.getExternalLifecycle()).isEqualTo("RESOLVED");
        assertThat(alert.getHandlingStatus()).isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void 同一外部告警重新触发应开启新周期并重置为待处理() {
        Alert alert = Alert.external("HERTZBEAT", "alert-1", 10L, "CRITICAL", "订单平台", "订单接口", "接口不可用", LocalDateTime.of(2026, 10, 19, 10, 0));
        alert.acknowledge();
        alert.resolve(LocalDateTime.of(2026, 10, 19, 10, 5));
        alert.fire("接口再次不可用", LocalDateTime.of(2026, 10, 19, 10, 10));
        assertThat(alert.getExternalLifecycle()).isEqualTo("FIRING");
        assertThat(alert.getHandlingStatus()).isEqualTo("OPEN");
        assertThat(alert.getResolvedAt()).isNull();
        assertThat(alert.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 10, 19, 10, 10));
    }

    @Test
    void 只有外部已恢复告警才能关闭() {
        Alert alert = Alert.external("HERTZBEAT", "alert-1", 10L, "CRITICAL", "订单平台", "订单接口", "接口不可用", LocalDateTime.of(2026, 10, 19, 10, 0));
        assertThatThrownBy(() -> alert.close("平台管理员", "确认恢复", LocalDateTime.now())).isInstanceOf(IllegalStateException.class).hasMessage("外部告警恢复后才能关闭");
        alert.resolve(LocalDateTime.of(2026, 10, 19, 10, 5));
        alert.close("平台管理员", "确认恢复", LocalDateTime.of(2026, 10, 19, 10, 6));
        assertThat(alert.getHandlingStatus()).isEqualTo("CLOSED");
        assertThat(alert.getHandledBy()).isEqualTo("平台管理员");
        assertThat(alert.getHandlingNote()).isEqualTo("确认恢复");
        assertThatThrownBy(() -> alert.acknowledge("平台管理员", null, LocalDateTime.now())).isInstanceOf(IllegalStateException.class).hasMessage("只有待处理告警可以确认");
        assertThatThrownBy(() -> alert.close("平台管理员", null, LocalDateTime.now())).isInstanceOf(IllegalStateException.class).hasMessage("告警已经关闭");
    }

    @Test
    void 重复firing同步不得误判为新周期() {
        Alert alert = Alert.external("HERTZBEAT", "alert-1", 10L, "CRITICAL", "订单平台", "订单接口", "接口不可用", LocalDateTime.of(2026, 10, 19, 10, 0));
        assertThat(alert.fire("接口仍不可用", LocalDateTime.of(2026, 10, 19, 10, 0))).isFalse();
        assertThat(alert.getHandlingStatus()).isEqualTo("OPEN");
    }
}
