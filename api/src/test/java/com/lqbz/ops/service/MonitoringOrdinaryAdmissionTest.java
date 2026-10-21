package com.lqbz.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.InspectionPlan;
import com.lqbz.ops.domain.InspectionPlanBindingRepository;
import com.lqbz.ops.domain.InspectionPlanRepository;
import com.lqbz.ops.domain.Project;
import com.lqbz.ops.domain.ProjectRepository;
import com.lqbz.ops.integration.HertzBeatClient;
import com.lqbz.ops.web.InspectionPlanRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@ActiveProfiles("test")
class MonitoringOrdinaryAdmissionTest {
  @Autowired private ProjectRepository projects;
  @Autowired private AssetRepository assets;
  @Autowired private InspectionPlanRepository plans;
  @Autowired private InspectionPlanBindingRepository bindings;
  @Autowired private InspectionPlanPolicyService policies;
  @MockBean private HertzBeatClient client;

  @ParameterizedTest
  @CsvSource({"false,0,true,false", "true,0,true,false", "false,1,false,false", "true,1,false,false",
      "false,2,true,false", "true,2,true,false", "false,1,true,false", "true,1,true,false",
      "false,0,true,true", "true,0,true,true"})
  void 存量迁移场景不得绕过确认直接新增绑定(boolean updating, int legacyCount,
      boolean hasMonitor, boolean duplicate) {
    String projectName = "普通准入" + System.nanoTime();
    Project project = projects.save(new Project(projectName, "负责人", "迁移准入回归", "RUNNING"));
    Asset base = assets.save(new Asset("原有服务", projectName, "生产", "HTTP", "http://127.0.0.1:1/", "UNKNOWN"));
    var existing = policies.create(request(project, List.of(base.getId())));
    Asset candidate = new Asset("待迁移服务", projectName, "生产", "HTTP", "http://127.0.0.1:2/", "UNKNOWN");
    long monitorId = project.getId() + 100000L;
    if (hasMonitor) candidate.markSynced(monitorId, "历史Monitor映射");
    assets.save(candidate);
    if (duplicate) {
      Asset other = new Asset("重复映射服务", projectName, "生产", "HTTP", "http://127.0.0.1:3/", "UNKNOWN");
      other.markSynced(monitorId, "重复历史映射");
      assets.save(other);
    }
    for (int index = 0; index < legacyCount; index++) {
      plans.save(new InspectionPlan("旧计划" + index, projectName, "全部服务", "每 1 分钟", "ENABLED",
          LocalDateTime.now().plusDays(1)));
    }

    assertThatThrownBy(() -> {
      if (updating) policies.update(existing.id(), request(project, List.of(base.getId(), candidate.getId())));
      else policies.create(request(project, List.of(candidate.getId())));
    }).isInstanceOf(IllegalStateException.class).hasMessageContaining("迁移");
    assertThat(bindings.findByActiveOwnerKey(candidate.getId())).isEmpty();
    assertThat(assets.findById(candidate.getId()).orElseThrow().getHertzbeatMonitorId())
        .isEqualTo(hasMonitor ? monitorId : null);
  }

  private InspectionPlanRequest request(Project project, List<Long> assetIds) {
    return new InspectionPlanRequest("准入计划", project.getId(), project.getName(), null, null,
        "ENABLED", null, 300, 2, "CRITICAL", assetIds, "MONITORING");
  }
}
