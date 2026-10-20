package com.lqbz.ops.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest(properties = "spring.flyway.enabled=false")
class InspectionPolicyModelTest {
  @Autowired private ProjectRepository projects;
  @Autowired private AssetRepository assets;
  @Autowired private InspectionPlanRepository plans;
  @Autowired private InspectionPlanBindingRepository bindings;
  @Autowired private MonitoringConfigTaskRepository tasks;

  @Test
  void 项目名称应由数据库保证唯一() {
    projects.saveAndFlush(new Project("订单系统", "业务系统", "张三", "13800000000", null, "订单业务", "RUNNING"));

    assertThatThrownBy(() -> projects.saveAndFlush(
        new Project("订单系统", "业务系统", "李四", "13900000000", null, "重复项目", "RUNNING")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void 一个服务只能存在一个未释放的巡检计划绑定() {
    Asset asset = assets.save(new Asset("订单接口", "订单系统", "生产", "HTTP", "https://order.example/health", "UNKNOWN"));
    InspectionPlan first = plans.save(InspectionPlan.monitoring("接口巡检", 1L, "订单系统", 60, 2, "CRITICAL"));
    InspectionPlan second = plans.save(InspectionPlan.monitoring("备用巡检", 1L, "订单系统", 300, 2, "WARNING"));
    bindings.saveAndFlush(InspectionPlanBinding.managed(first.getId(), asset.getId(), 1));

    assertThatThrownBy(() -> bindings.saveAndFlush(InspectionPlanBinding.managed(second.getId(), asset.getId(), 1)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void 释放绑定后服务应允许被其他计划重新绑定() {
    Asset asset = assets.save(new Asset("库存接口", "库存系统", "生产", "HTTP", "https://stock.example/health", "UNKNOWN"));
    InspectionPlan first = plans.save(InspectionPlan.monitoring("旧巡检", 1L, "库存系统", 60, 2, "CRITICAL"));
    InspectionPlan second = plans.save(InspectionPlan.monitoring("新巡检", 1L, "库存系统", 300, 2, "WARNING"));
    InspectionPlanBinding oldBinding = bindings.saveAndFlush(
        InspectionPlanBinding.managed(first.getId(), asset.getId(), 1));

    oldBinding.releaseOwnership();
    bindings.saveAndFlush(oldBinding);
    InspectionPlanBinding newBinding = bindings.saveAndFlush(
        InspectionPlanBinding.managed(second.getId(), asset.getId(), 1));

    assertThat(oldBinding.getOwnershipStatus()).isEqualTo("RELEASED");
    assertThat(oldBinding.getActiveOwnerKey()).isNull();
    assertThat(newBinding.getActiveOwnerKey()).isEqualTo(asset.getId());
  }

  @Test
  void 同步任务应能识别是否已经被新配置版本取代() {
    MonitoringConfigTask task = tasks.save(new MonitoringConfigTask(10L, 20L, 3L, "UPSERT", LocalDateTime.now()));

    assertThat(task.isObsolete(3L)).isFalse();
    assertThat(task.isObsolete(4L)).isTrue();
  }
}
