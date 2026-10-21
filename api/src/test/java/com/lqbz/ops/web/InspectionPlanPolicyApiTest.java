package com.lqbz.ops.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqbz.ops.domain.InspectionPlanBinding;
import com.lqbz.ops.domain.InspectionPlanBindingRepository;
import java.util.List;
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
class InspectionPlanPolicyApiTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private InspectionPlanBindingRepository bindings;

  @Test
  void 未知类型即使提供端口也不能加入启用计划() throws Exception {
    long projectId = createProject("类型准入系统");
    var response = mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(Map.of("name", "未知类型服务", "projectName", "类型准入系统",
            "type", "UNKNOWN", "endpoint", "localhost:1234"))))
        .andExpect(status().isCreated()).andReturn();
    long assetId = objectMapper.readTree(response.getResponse().getContentAsString()).path("id").asLong();
    mockMvc.perform(post("/api/inspection-plans").contentType(MediaType.APPLICATION_JSON)
        .content(monitoringPlanBody("拒绝未知类型", projectId, "类型准入系统", List.of(assetId))))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("服务类型没有有效监控契约：UNKNOWN"));
  }

  @Test
  void 应创建绑定服务的监控计划() throws Exception {
    long projectId = createProject("策略测试系统");
    long assetId = createAsset("策略测试接口", "策略测试系统");

    MvcResult result = mockMvc.perform(post("/api/inspection-plans")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "name", "核心接口监控",
                "projectId", projectId,
                "projectName", "策略测试系统",
                "intervalSeconds", 300,
                "failureThreshold", 2,
                "alertLevel", "CRITICAL",
                "mode", "MONITORING",
                "assetIds", List.of(assetId)))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.intervalSeconds").value(300))
        .andExpect(jsonPath("$.failureThreshold").value(2))
        .andExpect(jsonPath("$.assetIds[0]").value(assetId))
        .andReturn();

    long planId = objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    mockMvc.perform(get("/api/inspection-plans/{id}", planId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assetIds[0]").value(assetId));
  }

  @Test
  void 不允许选择其他项目的服务() throws Exception {
    long projectId = createProject("计划所属系统");
    createProject("其他系统");
    long assetId = createAsset("其他系统接口", "其他系统");

    mockMvc.perform(post("/api/inspection-plans")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "name", "非法跨项目计划",
                "projectId", projectId,
                "projectName", "计划所属系统",
                "intervalSeconds", 60,
                "failureThreshold", 2,
                "alertLevel", "WARNING",
                "mode", "MONITORING",
                "assetIds", List.of(assetId)))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("巡检计划只能选择所属项目下的服务"));
  }

  @Test
  void 更新计划应递增版本并标记移除服务待释放() throws Exception {
    long projectId = createProject("更新策略系统");
    long retainedAssetId = createAsset("保留接口", "更新策略系统");
    long removedAssetId = createAsset("移除接口", "更新策略系统");
    long planId = createMonitoringPlan("更新前计划", projectId, "更新策略系统", List.of(retainedAssetId, removedAssetId));

    mockMvc.perform(put("/api/monitoring-plans/{id}", planId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "name", "更新后计划", "projectId", projectId, "projectName", "更新策略系统",
                "intervalSeconds", 600, "failureThreshold", 3, "alertLevel", "WARNING",
                "mode", "MONITORING", "assetIds", List.of(retainedAssetId)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.desiredVersion").value(2))
        .andExpect(jsonPath("$.assetIds.length()").value(1))
        .andExpect(jsonPath("$.assetIds[0]").value(retainedAssetId));
  }

  @Test
  void 删除计划应进入删除中且保留计划记录() throws Exception {
    long projectId = createProject("删除策略系统");
    long assetId = createAsset("删除策略接口", "删除策略系统");
    long planId = createMonitoringPlan("待删除计划", projectId, "删除策略系统", List.of(assetId));

    mockMvc.perform(delete("/api/monitoring-plans/{id}", planId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lifecycleStatus").value("DELETING"))
        .andExpect(jsonPath("$.desiredVersion").value(2));

    mockMvc.perform(get("/api/inspection-plans/{id}", planId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lifecycleStatus").value("DELETED"));
  }

  @Test
  void 迁移报告固定路由不应被计划详情路由吞掉() throws Exception {
    mockMvc.perform(get("/api/inspection-plans/migration-report"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
  }

  @Test
  void 已释放服务重新加入同一计划时应创建新的有效绑定() throws Exception {
    long projectId = createProject("重新绑定系统");
    long retainedAssetId = createAsset("常驻接口", "重新绑定系统");
    long reboundAssetId = createAsset("重绑接口", "重新绑定系统");
    long planId = createMonitoringPlan("重绑计划", projectId, "重新绑定系统", List.of(retainedAssetId, reboundAssetId));

    updateMonitoringPlan(planId, projectId, "重新绑定系统", List.of(retainedAssetId));

    mockMvc.perform(put("/api/monitoring-plans/{id}", planId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(monitoringPlanBody("重绑计划", projectId, "重新绑定系统", List.of(retainedAssetId, reboundAssetId))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assetIds.length()").value(2))
        .andExpect(jsonPath("$.assetIds[0]").value(retainedAssetId))
        .andExpect(jsonPath("$.assetIds[1]").value(reboundAssetId));

    org.assertj.core.api.Assertions.assertThat(bindings.findByPlanIdOrderById(planId))
        .filteredOn(binding -> reboundAssetId == binding.getAssetId())
        .extracting(InspectionPlanBinding::getOwnershipStatus)
        .containsExactly("RELEASED", "OWNED");
  }

  @Test
  void 旧接口不能修改或物理删除结构化计划() throws Exception {
    long projectId = createProject("旧接口保护系统");
    long assetId = createAsset("旧接口保护服务", "旧接口保护系统");
    long planId = createMonitoringPlan("受保护计划", projectId, "旧接口保护系统", List.of(assetId));

    mockMvc.perform(put("/api/inspection-plans/{id}", planId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "name", "绕过修改", "projectName", "旧接口保护系统", "scopeText", "全部服务",
                "scheduleText", "每小时", "nextRunAt", "2026-10-21T10:00:00"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("结构化巡检计划请使用 /api/monitoring-plans/{id}"));

    mockMvc.perform(delete("/api/inspection-plans/{id}", planId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("结构化巡检计划请使用 /api/monitoring-plans/{id}"));
    mockMvc.perform(get("/api/inspection-plans/{id}", planId)).andExpect(status().isOk());
  }

  @Test
  void 待释放服务重新加入原计划时应恢复原绑定() throws Exception {
    long projectId = createProject("撤销释放系统");
    long retainedAssetId = createAsset("保留服务", "撤销释放系统");
    long restoredAssetId = createAsset("恢复服务", "撤销释放系统");
    long planId = createMonitoringPlan("撤销释放计划", projectId, "撤销释放系统", List.of(retainedAssetId, restoredAssetId));

    updateMonitoringPlan(planId, projectId, "撤销释放系统", List.of(retainedAssetId));
    mockMvc.perform(put("/api/monitoring-plans/{id}", planId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(monitoringPlanBody("撤销释放计划", projectId, "撤销释放系统",
                List.of(retainedAssetId, restoredAssetId))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assetIds.length()").value(2));

    org.assertj.core.api.Assertions.assertThat(bindings.findByPlanIdOrderById(planId))
        .filteredOn(binding -> restoredAssetId == binding.getAssetId())
        .extracting(InspectionPlanBinding::getOwnershipStatus)
        .containsExactly("RELEASED", "OWNED");
  }

  @Test
  void 停用和重新启用计划应同步切换绑定期望状态() throws Exception {
    long projectId = createProject("启停策略系统");
    long assetId = createAsset("启停策略服务", "启停策略系统");
    long planId = createMonitoringPlan("启停策略计划", projectId, "启停策略系统", List.of(assetId));

    mockMvc.perform(put("/api/monitoring-plans/{id}", planId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(monitoringPlanBody("启停策略计划", projectId, "启停策略系统", List.of(assetId), "DISABLED")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISABLED"));
    InspectionPlanBinding disabled = bindings.findByActiveOwnerKey(assetId).orElseThrow();
    org.assertj.core.api.Assertions.assertThat(disabled.getMonitorDesiredState()).isEqualTo("DISABLED");
    org.assertj.core.api.Assertions.assertThat(disabled.getRuleDesiredState()).isEqualTo("DISABLED");

    mockMvc.perform(put("/api/monitoring-plans/{id}", planId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(monitoringPlanBody("启停策略计划", projectId, "启停策略系统", List.of(assetId), "ENABLED")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ENABLED"));
    InspectionPlanBinding enabled = bindings.findByActiveOwnerKey(assetId).orElseThrow();
    org.assertj.core.api.Assertions.assertThat(enabled.getMonitorDesiredState()).isEqualTo("ENABLED");
    org.assertj.core.api.Assertions.assertThat(enabled.getRuleDesiredState()).isEqualTo("ENABLED");
  }

  @Test
  void 删除中的计划不能重新配置或取得服务() throws Exception {
    long projectId = createProject("删除中保护系统");
    long assetId = createAsset("原服务", "删除中保护系统");
    long newAssetId = createAsset("新增服务", "删除中保护系统");
    long planId = createMonitoringPlan("删除中计划", projectId, "删除中保护系统", List.of(assetId));
    mockMvc.perform(delete("/api/monitoring-plans/{id}", planId)).andExpect(status().isOk());

    mockMvc.perform(put("/api/monitoring-plans/{id}", planId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(monitoringPlanBody("删除中计划", projectId, "删除中保护系统", List.of(assetId, newAssetId))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("删除中的巡检计划不能修改"));
    org.assertj.core.api.Assertions.assertThat(bindings.findByActiveOwnerKey(newAssetId)).isEmpty();
  }

  @Test
  void 结构化计划只接受启用或停用状态() throws Exception {
    long projectId = createProject("状态校验系统");
    long assetId = createAsset("状态校验服务", "状态校验系统");

    mockMvc.perform(post("/api/inspection-plans")
            .contentType(MediaType.APPLICATION_JSON)
            .content(monitoringPlanBody("非法状态计划", projectId, "状态校验系统", List.of(assetId), "PAUSED")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("巡检计划状态只允许 ENABLED 或 DISABLED"));
  }

  private long createProject(String name) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/projects")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("name", name, "owner", "测试负责人", "description", "测试"))))
        .andExpect(status().isCreated()).andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
  }

  private long createAsset(String name, String projectName) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/assets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "name", name, "projectName", projectName, "type", "HTTP", "endpoint", "https://example.com/health"))))
        .andExpect(status().isCreated()).andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
  }

  private long createMonitoringPlan(String name, long projectId, String projectName, List<Long> assetIds)
      throws Exception {
    MvcResult result = mockMvc.perform(post("/api/inspection-plans")
            .contentType(MediaType.APPLICATION_JSON)
            .content(monitoringPlanBody(name, projectId, projectName, assetIds)))
        .andExpect(status().isCreated()).andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
  }

  private void updateMonitoringPlan(long planId, long projectId, String projectName, List<Long> assetIds)
      throws Exception {
    mockMvc.perform(put("/api/monitoring-plans/{id}", planId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(monitoringPlanBody("重绑计划", projectId, projectName, assetIds)))
        .andExpect(status().isOk());
  }

  private String monitoringPlanBody(String name, long projectId, String projectName, List<Long> assetIds)
      throws Exception {
    return monitoringPlanBody(name, projectId, projectName, assetIds, "ENABLED");
  }

  private String monitoringPlanBody(String name, long projectId, String projectName, List<Long> assetIds,
      String status) throws Exception {
    return objectMapper.writeValueAsString(Map.of(
        "name", name, "projectId", projectId, "projectName", projectName,
        "intervalSeconds", 300, "failureThreshold", 2, "alertLevel", "CRITICAL",
        "mode", "MONITORING", "status", status, "assetIds", assetIds));
  }
}
