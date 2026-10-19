package com.lqbz.ops.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AlertLifecycleTest {
  @Test
  void 外部恢复不能覆盖平台人工处置状态() {
    Alert alert = Alert.external("HERTZBEAT", "alert-1", 10L, "CRITICAL", "订单平台", "订单接口",
        "接口不可用", LocalDateTime.of(2026, 10, 19, 10, 0));
    alert.acknowledge();

    alert.resolve(LocalDateTime.of(2026, 10, 19, 10, 5));

    assertThat(alert.getExternalLifecycle()).isEqualTo("RESOLVED");
    assertThat(alert.getHandlingStatus()).isEqualTo("ACKNOWLEDGED");
  }

  @Test
  void 同一外部告警重新触发应恢复为活动但保留处置状态() {
    Alert alert = Alert.external("HERTZBEAT", "alert-1", 10L, "CRITICAL", "订单平台", "订单接口",
        "接口不可用", LocalDateTime.of(2026, 10, 19, 10, 0));
    alert.acknowledge();
    alert.resolve(LocalDateTime.of(2026, 10, 19, 10, 5));

    alert.fire("接口再次不可用", LocalDateTime.of(2026, 10, 19, 10, 10));

    assertThat(alert.getExternalLifecycle()).isEqualTo("FIRING");
    assertThat(alert.getHandlingStatus()).isEqualTo("ACKNOWLEDGED");
    assertThat(alert.getResolvedAt()).isNull();
  }
}
