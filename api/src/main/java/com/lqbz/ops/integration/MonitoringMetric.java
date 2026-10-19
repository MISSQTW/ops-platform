package com.lqbz.ops.integration;

import java.time.LocalDateTime;

public record MonitoringMetric(String name, Number value, Boolean booleanValue, String unit, LocalDateTime collectedAt) {
  public static MonitoringMetric duration(String name, double value, String sourceUnit, LocalDateTime collectedAt) {
    long milliseconds = switch (sourceUnit) {
      case "ms" -> Math.round(value);
      case "s" -> Math.round(value * 1000);
      default -> throw new IllegalArgumentException("未知指标单位：" + sourceUnit);
    };
    return new MonitoringMetric(name, milliseconds, null, "ms", collectedAt);
  }

  public static MonitoringMetric availability(boolean available, LocalDateTime collectedAt) {
    return new MonitoringMetric("availability", null, available, null, collectedAt);
  }

  public static MonitoringMetric integer(String name, long value, LocalDateTime collectedAt) {
    return new MonitoringMetric(name, value, null, null, collectedAt);
  }
}
