package com.lqbz.ops.web;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@lombok.EqualsAndHashCode()
@lombok.ToString()
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
public final class AssetRequest {

    @com.fasterxml.jackson.annotation.JsonCreator()
    public AssetRequest(@com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("projectName") String projectName, @com.fasterxml.jackson.annotation.JsonProperty("environmentName") String environmentName, @com.fasterxml.jackson.annotation.JsonProperty("type") String type, @com.fasterxml.jackson.annotation.JsonProperty("endpoint") String endpoint, @com.fasterxml.jackson.annotation.JsonProperty("status") String status) {
        this.name = name;
        this.projectName = projectName;
        this.environmentName = environmentName;
        this.type = type;
        this.endpoint = endpoint;
        this.status = status;
    }

    @NotBlank(message = "服务名称不能为空")
    @Size(max = 100, message = "服务名称不能超过100个字符")
    private final String name;

    public String name() {
        return this.name;
    }

    @NotBlank(message = "所属项目不能为空")
    private final String projectName;

    public String projectName() {
        return this.projectName;
    }

    private final String environmentName;

    public String environmentName() {
        return this.environmentName;
    }

    @NotBlank(message = "服务类型不能为空")
    private final String type;

    public String type() {
        return this.type;
    }

    @NotBlank(message = "连接地址不能为空")
    @Size(max = 240, message = "连接地址不能超过240个字符")
    private final String endpoint;

    public String endpoint() {
        return this.endpoint;
    }

    private final String status;

    public String status() {
        return this.status;
    }
}
