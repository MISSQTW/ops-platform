package com.lqbz.ops.web;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InspectionCenterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 应返回巡检计划及下次执行时间() throws Exception {
        mockMvc.perform(get("/api/inspection-plans")).andExpect(status().isOk()).andExpect(jsonPath("$[0].name").exists()).andExpect(jsonPath("$[0].scheduleText").exists()).andExpect(jsonPath("$[0].nextRunAt").exists());
    }

    @Test
    void 应返回某次巡检的逐项检查结果() throws Exception {
        mockMvc.perform(get("/api/inspections/2/items")).andExpect(status().isOk()).andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(greaterThanOrEqualTo(4)))).andExpect(jsonPath("$[*].status", org.hamcrest.Matchers.hasItem("FAILED"))).andExpect(jsonPath("$[*].message", org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("连接"))));
    }

    @Test
    void 应支持单服务地址测试() throws Exception {
        MvcResult assets = mockMvc.perform(get("/api/assets")).andExpect(status().isOk()).andReturn();
        long assetId = objectMapper.readTree(assets.getResponse().getContentAsString()).get(0).get("id").asLong();
        mockMvc.perform(post("/api/assets/{id}/test-address", assetId)).andExpect(status().isOk()).andExpect(jsonPath("$.passed").isBoolean()).andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.durationMillis").isNumber());
    }
}
