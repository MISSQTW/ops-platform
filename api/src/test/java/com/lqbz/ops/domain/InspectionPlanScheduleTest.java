package com.lqbz.ops.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class InspectionPlanScheduleTest {
  @Test
  void 修改结构化频率后展示必须跟随当前配置() {
    InspectionPlan plan = InspectionPlan.monitoring("接口巡检", 1L, "订单系统", 60, 2, "CRITICAL");
    plan.reconfigure("接口巡检", 300, 2, "CRITICAL", "ENABLED");

    assertThat(plan.getScheduleText()).isEqualTo("每 300 秒");
    assertThat(plan.getIntervalSeconds()).isEqualTo(300);
  }

  @Test
  void 旧计划应保留原有调度文本() {
    InspectionPlan plan = new InspectionPlan("旧巡检", "订单系统", "全部服务", "每天 09:00",
        "ENABLED", LocalDateTime.now());

    assertThat(plan.getScheduleText()).isEqualTo("每天 09:00");
  }
}
