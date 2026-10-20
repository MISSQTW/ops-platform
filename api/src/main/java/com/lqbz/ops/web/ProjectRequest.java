package com.lqbz.ops.web;

import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectRequest(
    @NotBlank(message = "项目名称不能为空") @Size(max = 80, message = "项目名称不能超过80个字符") String name,
    @Size(max = 40, message = "项目类型不能超过40个字符") String projectType,
    @NotBlank(message = "负责人不能为空") @Size(max = 40, message = "负责人不能超过40个字符") String owner,
    @Size(max = 40, message = "联系方式不能超过40个字符") String contactPhone,
    LocalDate maintenanceExpiresAt,
    @Size(max = 240, message = "项目描述不能超过240个字符") String description,
    String status) {
}
