package com.lqbz.ops.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssetRequest(
    @NotBlank(message = "资产名称不能为空") @Size(max = 100, message = "资产名称不能超过100个字符") String name,
    @NotBlank(message = "所属项目不能为空") String projectName,
    String environmentName,
    @NotBlank(message = "资产类型不能为空") String type,
    @NotBlank(message = "连接地址不能为空") @Size(max = 240, message = "连接地址不能超过240个字符") String endpoint,
    String status) {
}
