package com.lqbz.ops.web;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
class InspectionCenterTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void 应返回巡检计划及下次执行时间() throws Exception {
    mockMvc.perform(get("/api/inspection-plans"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").exists())
        .andExpect(jsonPath("$[0].scheduleText").exists())
        .andExpect(jsonPath("$[0].nextRunAt").exists());
  }

  @Test
  void 应返回某次巡检的逐项检查结果() throws Exception {
    mockMvc.perform(get("/api/inspections/2/items"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(greaterThanOrEqualTo(4))))
        .andExpect(jsonPath("$[*].status", org.hamcrest.Matchers.hasItem("FAILED")))
        .andExpect(jsonPath("$[*].message", org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("连接"))));
  }

  @Test
  void 巡检计划应支持新增修改和删除() throws Exception {
    MvcResult created = mockMvc.perform(post("/api/inspection-plans")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "name", "礼事随记生产巡检",
                "projectName", "礼事随记",
                "scopeText", "后端健康接口、PostgreSQL、H5 页面",
                "scheduleText", "每 5 分钟",
                "nextRunAt", "2026-10-19T11:30:00"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ENABLED"))
        .andReturn();

    long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
    mockMvc.perform(put("/api/inspection-plans/{id}", id)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "name", "礼事随记每日巡检",
                "projectName", "礼事随记",
                "scopeText", "全部生产资产",
                "scheduleText", "每天 09:00",
                "status", "DISABLED",
                "nextRunAt", "2026-10-20T09:00:00"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("礼事随记每日巡检"))
        .andExpect(jsonPath("$.status").value("DISABLED"));

    mockMvc.perform(delete("/api/inspection-plans/{id}", id)).andExpect(status().isNoContent());
    mockMvc.perform(get("/api/inspection-plans"))
        .andExpect(jsonPath("$[*].name", not(hasItem("礼事随记每日巡检"))));
    mockMvc.perform(get("/api/audits"))
        .andExpect(jsonPath("$[*].action", hasItem("CREATE_INSPECTION_PLAN")))
        .andExpect(jsonPath("$[*].action", hasItem("UPDATE_INSPECTION_PLAN")))
        .andExpect(jsonPath("$[*].action", hasItem("DELETE_INSPECTION_PLAN")));
  }

  @Test
  void 无服务计划手动执行应提示配置而不是生成空巡检记录() throws Exception {
    MvcResult created = mockMvc.perform(post("/api/inspection-plans")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "name", "无资产项目巡检",
                "projectName", "无资产项目",
                "scopeText", "项目全部资产",
                "scheduleText", "每小时整点",
                "nextRunAt", "2026-10-19T12:00:00"))))
        .andExpect(status().isCreated())
        .andReturn();

    long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
    mockMvc.perform(post("/api/inspection-plans/{id}/execute", id))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("计划所属项目没有可执行服务，请先添加服务"));

    mockMvc.perform(get("/api/inspections"))
        .andExpect(jsonPath("$[*].name", not(hasItem("无资产项目巡检"))));
    mockMvc.perform(get("/api/audits"))
        .andExpect(jsonPath("$[?(@.action == 'EXECUTE_INSPECTION_PLAN')].resourceName", not(hasItem("无资产项目巡检"))));
  }

  @Test
  void 有服务计划手动执行应生成逐项结果和审计记录() throws Exception {
    String projectName = "手动执行验证系统";
    mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(Map.of("name", projectName, "owner", "负责人", "description", "执行测试"))))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(Map.of("name", "手动执行接口", "projectName", projectName,
            "type", "HTTP", "endpoint", "http://127.0.0.1:1/health"))))
        .andExpect(status().isCreated());
    MvcResult created = mockMvc.perform(post("/api/inspection-plans").contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(Map.of("name", "手动执行验证计划", "projectName", projectName,
            "scopeText", "全部服务", "scheduleText", "每 1 分钟", "nextRunAt", "2099-01-01T12:00:00"))))
        .andExpect(status().isCreated()).andReturn();
    long id = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();

    MvcResult result = mockMvc.perform(post("/api/inspection-plans/{id}/execute", id))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("手动执行验证计划"))
        .andExpect(jsonPath("$.totalChecks").value(1)).andExpect(jsonPath("$.status").value("FAILED")).andReturn();
    long inspectionId = objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    mockMvc.perform(get("/api/inspections/{id}/items", inspectionId))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].status").value("FAILED"));
    mockMvc.perform(get("/api/audits"))
        .andExpect(jsonPath("$[?(@.action == 'EXECUTE_INSPECTION_PLAN')].resourceName", hasItem("手动执行验证计划")));
  }

  @Test
  void 应支持单服务地址测试() throws Exception {
    MvcResult assets = mockMvc.perform(get("/api/assets"))
        .andExpect(status().isOk())
        .andReturn();
    long assetId = objectMapper.readTree(assets.getResponse().getContentAsString()).get(0).get("id").asLong();

    mockMvc.perform(post("/api/assets/{id}/test-address", assetId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passed").isBoolean())
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.durationMillis").isNumber());
  }
}
