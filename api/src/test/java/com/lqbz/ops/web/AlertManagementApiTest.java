package com.lqbz.ops.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqbz.ops.domain.Alert;
import com.lqbz.ops.domain.AlertRepository;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertManagementApiTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AlertRepository alerts;

  @Test
  void 应支持确认备注关闭并查询完整处置历史() throws Exception {
    Alert alert = alerts.save(Alert.external("HERTZBEAT", "manage-1", 1001L, "CRITICAL",
        "订单平台", "订单接口", "接口不可用", LocalDateTime.now().minusMinutes(10)));

    mockMvc.perform(post("/api/alerts/{id}/acknowledge", alert.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("note", "已通知值班人员"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.handlingStatus").value("ACKNOWLEDGED"));
    mockMvc.perform(post("/api/alerts/{id}/notes", alert.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("note", "  数据库连接已恢复  "))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.handlingStatus").value("ACKNOWLEDGED"))
        .andExpect(jsonPath("$.handlingNote").value("已通知值班人员"));
    alert = alerts.findById(alert.getId()).orElseThrow();
    alert.resolve(LocalDateTime.now());
    alerts.save(alert);
    mockMvc.perform(post("/api/alerts/{id}/close", alert.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("note", "验证恢复，关闭告警"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.handlingStatus").value("CLOSED"));

    mockMvc.perform(get("/api/alerts/{id}/events", alert.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(3)))
        .andExpect(jsonPath("$[0].action").value("ACKNOWLEDGED"))
        .andExpect(jsonPath("$[1].action").value("NOTE_ADDED"))
        .andExpect(jsonPath("$[1].note").value("数据库连接已恢复"))
        .andExpect(jsonPath("$[2].action").value("CLOSED"));
  }

  @Test
  void 未恢复告警关闭应返回冲突() throws Exception {
    Alert alert = alerts.save(Alert.external("HERTZBEAT", "manage-2", 1002L, "WARNING",
        "订单平台", "支付接口", "响应变慢", LocalDateTime.now()));

    mockMvc.perform(post("/api/alerts/{id}/close", alert.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("note", "尝试关闭"))))
        .andExpect(status().isConflict());
  }

  @Test
  void 已关闭告警不得再次确认或关闭() throws Exception {
    Alert alert = Alert.external("HERTZBEAT", "manage-closed", 1004L, "WARNING",
        "订单平台", "支付接口", "响应变慢", LocalDateTime.now());
    alert.resolve(LocalDateTime.now());
    alert.close("平台管理员", "已恢复", LocalDateTime.now());
    alert = alerts.save(alert);

    mockMvc.perform(post("/api/alerts/{id}/acknowledge", alert.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of())))
        .andExpect(status().isConflict());
    mockMvc.perform(post("/api/alerts/{id}/close", alert.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of())))
        .andExpect(status().isConflict());
  }

  @Test
  void 空处置备注应返回参数错误() throws Exception {
    Alert alert = alerts.save(Alert.external("HERTZBEAT", "manage-note", 1005L, "INFO",
        "订单平台", "订单接口", "需要备注", LocalDateTime.now()));

    mockMvc.perform(post("/api/alerts/{id}/notes", alert.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("note", "   "))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 应按外部生命周期和平台处置状态筛选() throws Exception {
    Alert resolved = Alert.external("HERTZBEAT", "filter-1", 1003L, "INFO",
        "客户平台", "客户接口", "已恢复", LocalDateTime.now().minusHours(1));
    resolved.resolve(LocalDateTime.now());
    alerts.save(resolved);

    mockMvc.perform(get("/api/alerts")
            .queryParam("externalLifecycle", "RESOLVED")
            .queryParam("handlingStatus", "OPEN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].externalAlertId").value(org.hamcrest.Matchers.hasItem("filter-1")));
  }
}
