package com.lqbz.ops.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MonitoringMetricTest {
  @Test
  void 秒应规范化为整数毫秒() {
    MonitoringMetric metric = MonitoringMetric.duration("responseTime", 0.125, "s", LocalDateTime.now());

    assertThat(metric.value()).isEqualTo(125L);
    assertThat(metric.unit()).isEqualTo("ms");
  }

  @Test
  void 未知耗时单位应拒绝入库() {
    assertThatThrownBy(() -> MonitoringMetric.duration("responseTime", 12, "ticks", LocalDateTime.now()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知指标单位");
  }

  @Test
  void 可用性使用布尔值且没有单位() {
    MonitoringMetric metric = MonitoringMetric.availability(true, LocalDateTime.now());

    assertThat(metric.booleanValue()).isTrue();
    assertThat(metric.unit()).isNull();
  }
}
