package com.lqbz.ops.web;

import java.time.LocalDateTime;
import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InspectionPlanRequest(
    @NotBlank(message = "计划名称不能为空") @Size(max = 100, message = "计划名称不能超过100个字符") String name,
    Long projectId,
    @NotBlank(message = "所属项目不能为空") String projectName,
    @Size(max = 240, message = "巡检范围不能超过240个字符") String scopeText,
    @Size(max = 80, message = "执行周期不能超过80个字符") String scheduleText,
    String status,
    LocalDateTime nextRunAt,
    @Min(value = 10, message = "巡检频率不能低于10秒") @Max(value = 86400, message = "巡检频率不能超过24小时") Integer intervalSeconds,
    @Min(value = 1, message = "连续失败次数不能低于1") @Max(value = 10, message = "连续失败次数不能超过10") Integer failureThreshold,
    String alertLevel,
    List<Long> assetIds,
    String mode) {

  public boolean monitoringPolicy() {
    return "MONITORING".equals(mode);
  }
}
