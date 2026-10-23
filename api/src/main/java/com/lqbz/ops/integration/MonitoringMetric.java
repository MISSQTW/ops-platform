package com.lqbz.ops.integration;

import java.time.LocalDateTime;

@lombok.EqualsAndHashCode()
@lombok.ToString()
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
public final class MonitoringMetric {

    @com.fasterxml.jackson.annotation.JsonCreator()
    public MonitoringMetric(@com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("value") Number value, @com.fasterxml.jackson.annotation.JsonProperty("booleanValue") Boolean booleanValue, @com.fasterxml.jackson.annotation.JsonProperty("unit") String unit, @com.fasterxml.jackson.annotation.JsonProperty("collectedAt") LocalDateTime collectedAt) {
        this.name = name;
        this.value = value;
        this.booleanValue = booleanValue;
        this.unit = unit;
        this.collectedAt = collectedAt;
    }

    private final String name;

    public String name() {
        return this.name;
    }

    private final Number value;

    public Number value() {
        return this.value;
    }

    private final Boolean booleanValue;

    public Boolean booleanValue() {
        return this.booleanValue;
    }

    private final String unit;

    public String unit() {
        return this.unit;
    }

    private final LocalDateTime collectedAt;

    public LocalDateTime collectedAt() {
        return this.collectedAt;
    }

    public static MonitoringMetric duration(String name, double value, String sourceUnit, LocalDateTime collectedAt) {
        long milliseconds = com.lqbz.ops.compat.Java8.eval(() -> {
            switch(sourceUnit) {
                case "ms":
                    return Math.round(value);
                case "s":
                    return Math.round(value * 1000);
                default:
                    throw new IllegalArgumentException("未知指标单位：" + sourceUnit);
            }
        });
        return new MonitoringMetric(name, milliseconds, null, "ms", collectedAt);
    }

    public static MonitoringMetric availability(boolean available, LocalDateTime collectedAt) {
        return new MonitoringMetric("availability", null, available, null, collectedAt);
    }

    public static MonitoringMetric integer(String name, long value, LocalDateTime collectedAt) {
        return new MonitoringMetric(name, value, null, null, collectedAt);
    }
}
