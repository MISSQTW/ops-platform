package com.lqbz.ops.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.InspectionPlanBindingRepository;
import com.lqbz.ops.domain.InspectionPlanRepository;
import com.lqbz.ops.domain.Project;
import com.lqbz.ops.domain.ProjectRepository;
import com.lqbz.ops.integration.HertzBeatClient;
import com.lqbz.ops.web.InspectionPlanRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
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

  @Test
  void 已有Monitor标识的服务可以直接加入普通巡检计划() {
    String projectName = "普通准入" + System.nanoTime();
    Project project = projects.save(new Project(projectName, "负责人", "普通计划准入", "RUNNING"));
    Asset candidate = new Asset("已有监控服务", projectName, "生产", "HTTP", "http://127.0.0.1:2/", "UNKNOWN");
    long monitorId = project.getId() + 100000L;
    candidate.markSynced(monitorId, "已有Monitor映射");
    assets.save(candidate);

    var created = policies.create(request(project, List.of(candidate.getId())));

    assertThat(created.assetIds()).containsExactly(candidate.getId());
    assertThat(bindings.findByActiveOwnerKey(candidate.getId())).isPresent();
    assertThat(assets.findById(candidate.getId()).orElseThrow().getHertzbeatMonitorId())
        .isEqualTo(monitorId);
  }

  private InspectionPlanRequest request(Project project, List<Long> assetIds) {
    return new InspectionPlanRequest("准入计划", project.getId(), project.getName(), null, null,
        "ENABLED", null, 300, 2, "CRITICAL", assetIds, "MONITORING");
  }
}
