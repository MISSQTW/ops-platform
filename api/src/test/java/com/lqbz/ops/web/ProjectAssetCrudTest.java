package com.lqbz.ops.web;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectAssetCrudTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.lqbz.ops.domain.InspectionPlanBindingRepository bindings;

    @Test
    void 新建项目后应支持修改和删除() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "订单履约平台", "owner", "赵琪", "description", "订单履约测试项目")))).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("RUNNING")).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(put("/api/projects/{id}", id).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "订单履约中心", "owner", "赵琪", "description", "更新后的描述", "status", "MAINTENANCE")))).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("订单履约中心"));
        mockMvc.perform(delete("/api/projects/{id}", id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/projects")).andExpect(jsonPath("$[*].name", org.hamcrest.Matchers.not(hasItem("订单履约中心"))));
    }

    @Test
    void 项目档案应保存类型联系方式和维保日期() throws Exception {
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "档案测试系统", "projectType", "政务应用", "owner", "王工", "contactPhone", "13800000000", "maintenanceExpiresAt", "2027-12-31", "description", "验证项目档案字段")))).andExpect(status().isCreated()).andExpect(jsonPath("$.projectType").value("政务应用")).andExpect(jsonPath("$.contactPhone").value("13800000000")).andExpect(jsonPath("$.maintenanceExpiresAt").value("2027-12-31"));
    }

    @Test
    void 重复项目名称应返回冲突() throws Exception {
        String body = objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "唯一名称系统", "owner", "王工", "description", "第一次创建"));
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("项目名称已存在"));
    }

    @Test
    void 新建资产未传环境时应默认为生产() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "order-prod-api", "projectName", "统一支付平台", "type", "HTTP", "endpoint", "https://order.example/health")))).andExpect(status().isCreated()).andExpect(jsonPath("$.environmentName").value("生产")).andExpect(jsonPath("$.status").value("UNKNOWN")).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(put("/api/assets/{id}", id).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "order-prod-api", "projectName", "统一支付平台", "environmentName", "测试", "type", "HTTP", "endpoint", "https://order-test.example/health", "status", "HEALTHY")))).andExpect(status().isOk()).andExpect(jsonPath("$.environmentName").value("测试"));
        mockMvc.perform(delete("/api/assets/{id}", id)).andExpect(status().isNoContent());
    }

    @Test
    void 项目存在关联服务时禁止直接删除() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "禁止删除系统", "owner", "测试负责人", "description", "有关联服务")))).andExpect(status().isCreated()).andReturn();
        long projectId = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "禁止删除接口", "projectName", "禁止删除系统", "type", "HTTP", "endpoint", "https://example.com/health")))).andExpect(status().isCreated());
        mockMvc.perform(delete("/api/projects/{id}", projectId)).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("项目存在关联服务或巡检计划，请先归档关联数据"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void 服务仍有未清理计划绑定时禁止直接删除(boolean disabledReleased) throws Exception {
        MvcResult project = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "绑定删除保护系统" + disabledReleased, "owner", "测试负责人", "description", "绑定删除保护")))).andExpect(status().isCreated()).andReturn();
        long projectId = objectMapper.readTree(project.getResponse().getContentAsString()).path("id").asLong();
        MvcResult asset = mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "绑定删除保护服务", "projectName", "绑定删除保护系统" + disabledReleased, "type", "HTTP", "endpoint", "https://example.com/health")))).andExpect(status().isCreated()).andReturn();
        long assetId = objectMapper.readTree(asset.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(post("/api/inspection-plans").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "绑定删除保护计划", "projectId", projectId, "projectName", "绑定删除保护系统" + disabledReleased, "intervalSeconds", 60, "failureThreshold", 2, "alertLevel", "CRITICAL", "mode", "MONITORING", "assetIds", com.lqbz.ops.compat.Java8.list(assetId))))).andExpect(status().isCreated());
        if (disabledReleased) {
            lombok.val binding = bindings.findByActiveOwnerKey(assetId).orElseThrow(java.util.NoSuchElementException::new);
            binding.markSynchronized(111L, 222L, false, java.time.LocalDateTime.now());
            binding.releaseDisabledOwnership();
            bindings.saveAndFlush(binding);
        }
        mockMvc.perform(delete("/api/assets/{id}", assetId)).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("服务已加入巡检计划，请先从计划中移除并完成监控清理"));
    }

    @Test
    void 项目有关联数据时禁止修改名称() throws Exception {
        MvcResult project = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "不可改名系统", "owner", "测试负责人", "description", "改名保护")))).andExpect(status().isCreated()).andReturn();
        long projectId = objectMapper.readTree(project.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "关联服务", "projectName", "不可改名系统", "type", "HTTP", "endpoint", "https://example.com/health")))).andExpect(status().isCreated());
        mockMvc.perform(put("/api/projects/{id}", projectId).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "改名后的系统", "owner", "测试负责人", "description", "尝试改名")))).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("项目存在关联服务或巡检计划，不能修改项目名称"));
    }

    @Test
    void 参数错误应返回统一错误结构() throws Exception {
        mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "", "projectName", "统一支付平台", "type", "HTTP", "endpoint", "")))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists()).andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void 操作后应生成审计记录() throws Exception {
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(com.lqbz.ops.compat.Java8.map("name", "审计测试项目", "owner", "审计员", "description", "验证操作审计")))).andExpect(status().isCreated());
        mockMvc.perform(get("/api/audits")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))).andExpect(jsonPath("$[*].action", hasItem("CREATE_PROJECT")));
    }
}
