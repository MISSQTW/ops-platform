package com.lqbz.ops.web;

import java.time.LocalDateTime;
import java.util.List;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@lombok.EqualsAndHashCode()
@lombok.ToString()
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
public final class InspectionPlanRequest {

    @com.fasterxml.jackson.annotation.JsonCreator()
    public InspectionPlanRequest(@com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("projectId") Long projectId, @com.fasterxml.jackson.annotation.JsonProperty("projectName") String projectName, @com.fasterxml.jackson.annotation.JsonProperty("scopeText") String scopeText, @com.fasterxml.jackson.annotation.JsonProperty("scheduleText") String scheduleText, @com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("nextRunAt") LocalDateTime nextRunAt, @com.fasterxml.jackson.annotation.JsonProperty("intervalSeconds") Integer intervalSeconds, @com.fasterxml.jackson.annotation.JsonProperty("failureThreshold") Integer failureThreshold, @com.fasterxml.jackson.annotation.JsonProperty("alertLevel") String alertLevel, @com.fasterxml.jackson.annotation.JsonProperty("assetIds") List<Long> assetIds, @com.fasterxml.jackson.annotation.JsonProperty("mode") String mode) {
        this.name = name;
        this.projectId = projectId;
        this.projectName = projectName;
        this.scopeText = scopeText;
        this.scheduleText = scheduleText;
        this.status = status;
        this.nextRunAt = nextRunAt;
        this.intervalSeconds = intervalSeconds;
        this.failureThreshold = failureThreshold;
        this.alertLevel = alertLevel;
        this.assetIds = assetIds;
        this.mode = mode;
    }

    @NotBlank(message = "计划名称不能为空")
    @Size(max = 100, message = "计划名称不能超过100个字符")
    private final String name;

    public String name() {
        return this.name;
    }

    private final Long projectId;

    public Long projectId() {
        return this.projectId;
    }

    @NotBlank(message = "所属项目不能为空")
    private final String projectName;

    public String projectName() {
        return this.projectName;
    }

    @Size(max = 240, message = "巡检范围不能超过240个字符")
    private final String scopeText;

    public String scopeText() {
        return this.scopeText;
    }

    @Size(max = 80, message = "执行周期不能超过80个字符")
    private final String scheduleText;

    public String scheduleText() {
        return this.scheduleText;
    }

    private final String status;

    public String status() {
        return this.status;
    }

    private final LocalDateTime nextRunAt;

    public LocalDateTime nextRunAt() {
        return this.nextRunAt;
    }

    @Min(value = 10, message = "巡检频率不能低于10秒")
    @Max(value = 86400, message = "巡检频率不能超过24小时")
    private final Integer intervalSeconds;

    public Integer intervalSeconds() {
        return this.intervalSeconds;
    }

    @Min(value = 1, message = "连续失败次数不能低于1")
    @Max(value = 10, message = "连续失败次数不能超过10")
    private final Integer failureThreshold;

    public Integer failureThreshold() {
        return this.failureThreshold;
    }

    private final String alertLevel;

    public String alertLevel() {
        return this.alertLevel;
    }

    private final List<Long> assetIds;

    public List<Long> assetIds() {
        return this.assetIds;
    }

    private final String mode;

    public String mode() {
        return this.mode;
    }

    public boolean monitoringPolicy() {
        return "MONITORING".equals(mode);
    }
}
