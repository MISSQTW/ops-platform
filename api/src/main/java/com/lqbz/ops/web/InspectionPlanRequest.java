package com.lqbz.ops.web;

import java.time.LocalDateTime;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InspectionPlanRequest(
    @NotBlank(message = "计划名称不能为空") @Size(max = 100, message = "计划名称不能超过100个字符") String name,
    @NotBlank(message = "所属项目不能为空") String projectName,
    @NotBlank(message = "巡检范围不能为空") @Size(max = 240, message = "巡检范围不能超过240个字符") String scopeText,
    @NotBlank(message = "执行周期不能为空") @Size(max = 80, message = "执行周期不能超过80个字符") String scheduleText,
    String status,
    @NotNull(message = "下次执行时间不能为空") LocalDateTime nextRunAt) {
}
