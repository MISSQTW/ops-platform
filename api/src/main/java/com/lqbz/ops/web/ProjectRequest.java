package com.lqbz.ops.web;

import java.time.LocalDate;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@lombok.EqualsAndHashCode()
@lombok.ToString()
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
public final class ProjectRequest {

    @com.fasterxml.jackson.annotation.JsonCreator()
    public ProjectRequest(@com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("projectType") String projectType, @com.fasterxml.jackson.annotation.JsonProperty("owner") String owner, @com.fasterxml.jackson.annotation.JsonProperty("contactPhone") String contactPhone, @com.fasterxml.jackson.annotation.JsonProperty("maintenanceExpiresAt") LocalDate maintenanceExpiresAt, @com.fasterxml.jackson.annotation.JsonProperty("description") String description, @com.fasterxml.jackson.annotation.JsonProperty("status") String status) {
        this.name = name;
        this.projectType = projectType;
        this.owner = owner;
        this.contactPhone = contactPhone;
        this.maintenanceExpiresAt = maintenanceExpiresAt;
        this.description = description;
        this.status = status;
    }

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 80, message = "项目名称不能超过80个字符")
    private final String name;

    public String name() {
        return this.name;
    }

    @Size(max = 40, message = "项目类型不能超过40个字符")
    private final String projectType;

    public String projectType() {
        return this.projectType;
    }

    @NotBlank(message = "负责人不能为空")
    @Size(max = 40, message = "负责人不能超过40个字符")
    private final String owner;

    public String owner() {
        return this.owner;
    }

    @Size(max = 40, message = "联系方式不能超过40个字符")
    private final String contactPhone;

    public String contactPhone() {
        return this.contactPhone;
    }

    private final LocalDate maintenanceExpiresAt;

    public LocalDate maintenanceExpiresAt() {
        return this.maintenanceExpiresAt;
    }

    @Size(max = 240, message = "项目描述不能超过240个字符")
    private final String description;

    public String description() {
        return this.description;
    }

    private final String status;

    public String status() {
        return this.status;
    }
}
