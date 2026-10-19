package com.lqbz.ops.web;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerTest {
  @Autowired
  private MockMvc mockMvc;

  @Test
  void 应返回仪表盘汇总数据() throws Exception {
    mockMvc.perform(get("/api/dashboard/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projectCount", greaterThanOrEqualTo(1)))
        .andExpect(jsonPath("$.assetCount", greaterThanOrEqualTo(1)));
  }

  @Test
  void 应返回项目列表() throws Exception {
    mockMvc.perform(get("/api/projects"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("统一支付平台"));
  }
}
