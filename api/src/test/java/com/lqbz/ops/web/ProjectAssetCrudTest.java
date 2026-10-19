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
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void 新建项目后应支持修改和删除() throws Exception {
    MvcResult created = mockMvc.perform(post("/api/projects")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "name", "订单履约平台", "owner", "赵琪", "description", "订单履约测试项目"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andReturn();

    long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
    mockMvc.perform(put("/api/projects/{id}", id)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "name", "订单履约中心", "owner", "赵琪", "description", "更新后的描述", "status", "MAINTENANCE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("订单履约中心"));

    mockMvc.perform(delete("/api/projects/{id}", id)).andExpect(status().isNoContent());
    mockMvc.perform(get("/api/projects")).andExpect(jsonPath("$[*].name", org.hamcrest.Matchers.not(hasItem("订单履约中心"))));
  }

  @Test
  void 新建资产未传环境时应默认为生产() throws Exception {
    MvcResult created = mockMvc.perform(post("/api/assets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "name", "order-prod-api", "projectName", "统一支付平台", "type", "HTTP", "endpoint", "https://order.example/health"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.environmentName").value("生产"))
        .andExpect(jsonPath("$.status").value("UNKNOWN"))
        .andReturn();

    long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
    mockMvc.perform(put("/api/assets/{id}", id)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "name", "order-prod-api", "projectName", "统一支付平台", "environmentName", "测试",
                "type", "HTTP", "endpoint", "https://order-test.example/health", "status", "HEALTHY"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.environmentName").value("测试"));

    mockMvc.perform(delete("/api/assets/{id}", id)).andExpect(status().isNoContent());
  }

  @Test
  void 参数错误应返回统一错误结构() throws Exception {
    mockMvc.perform(post("/api/assets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("name", "", "projectName", "统一支付平台", "type", "HTTP", "endpoint", ""))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.errors").isArray());
  }

  @Test
  void 操作后应生成审计记录() throws Exception {
    mockMvc.perform(post("/api/projects")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("name", "审计测试项目", "owner", "审计员", "description", "验证操作审计"))))
        .andExpect(status().isCreated());

    mockMvc.perform(get("/api/audits"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(1))))
        .andExpect(jsonPath("$[*].action", hasItem("CREATE_PROJECT")));
  }
}
