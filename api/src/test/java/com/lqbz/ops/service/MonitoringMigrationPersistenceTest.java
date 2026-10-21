package com.lqbz.ops.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.InspectionPlan;
import com.lqbz.ops.domain.InspectionPlanBindingRepository;
import com.lqbz.ops.domain.InspectionPlanRepository;
import com.lqbz.ops.domain.MonitoringMigrationProposalRepository;
import com.lqbz.ops.domain.Project;
import com.lqbz.ops.domain.ProjectRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MonitoringMigrationPersistenceTest {
  @org.springframework.boot.test.mock.mockito.MockBean private MonitoringMigrationTopologyLock topologyLock;
  @Autowired private MonitoringMigrationConfirmationService migrations;
  @Autowired private MonitoringMigrationProposalRepository proposals;
  @Autowired private ProjectRepository projects;
  @Autowired private AssetRepository assets;
  @Autowired private InspectionPlanRepository plans;
  @Autowired private InspectionPlanBindingRepository bindings;
  @Autowired private EntityManager entityManager;

  @Test
  void 取消待确认迁移应释放服务且不创建外部清理任务() {
    Project project = projects.save(new Project("取消迁移系统", "负责人", "迁移测试", "RUNNING"));
    Asset asset = assets.save(new Asset("取消迁移接口", project.getName(), "生产", "HTTP",
        "https://example.com", "UNKNOWN"));
    plans.save(new InspectionPlan("旧取消巡检", project.getName(), "全部服务", "每 1 分钟",
        "ENABLED", LocalDateTime.now()));
    var proposal = migrations.prepare(asset.getId(), project.getId(), null);

    migrations.cancel(proposal.getId());
    migrations.cancel(proposal.getId());
    entityManager.flush();
    entityManager.clear();

    assertThat(proposals.findById(proposal.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
    assertThat(bindings.findByActiveOwnerKey(asset.getId())).isEmpty();
    assertThat(plans.findById(proposal.getPreparedPlanId()).orElseThrow().getLifecycleStatus()).isEqualTo("DELETED");
    assertThat(assets.findById(asset.getId()).orElseThrow().getHertzbeatMonitorId()).isNull();
    assertThat(migrations.prepare(asset.getId(), project.getId(), null).getId()).isNotEqualTo(proposal.getId());
  }

  @Test
  void 提案落库后可确认且重复确认不重复创建计划() {
    Project project = projects.save(new Project("持久迁移系统", "负责人", "迁移测试", "RUNNING"));
    Asset asset = assets.save(new Asset("迁移接口", project.getName(), "生产", "HTTP",
        "https://example.com", "UNKNOWN"));
    InspectionPlan legacy = plans.save(new InspectionPlan("旧迁移巡检", project.getName(), "全部服务",
        "每 1 分钟", "ENABLED", LocalDateTime.now()));
    var pending = migrations.prepare(asset.getId(), project.getId(), null);
    Long proposalId = pending.getId();
    Long legacyId = legacy.getId();
    Long assetId = asset.getId();
    long before = plans.count();
    entityManager.flush();
    entityManager.clear();

    assertThat(proposals.findById(proposalId).orElseThrow().getStatus()).isEqualTo("PENDING_CONFIRMATION");
    assertThat(bindings.findByActiveOwnerKey(assetId)).get().satisfies(binding -> {
      assertThat(binding.getOwnershipStatus()).isEqualTo("MIGRATION_PENDING");
      assertThat(plans.findById(binding.getPlanId()).orElseThrow().getStatus()).isEqualTo("PENDING_CONFIRMATION");
    });
    assertThat(plans.findById(legacyId).orElseThrow().getStatus()).isEqualTo("ENABLED");
    var result = migrations.confirm(proposalId);
    entityManager.flush();
    entityManager.clear();

    assertThat(migrations.confirm(proposalId).id()).isEqualTo(result.id());
    assertThat(plans.count()).isEqualTo(before);
    assertThat(bindings.findByActiveOwnerKey(assetId)).get().satisfies(binding -> {
      assertThat(binding.getMonitorActualState()).isEqualTo("UNKNOWN");
      assertThat(binding.getRuleActualState()).isEqualTo("UNKNOWN");
    });
    assertThat(plans.findById(legacyId).orElseThrow().getStatus()).isEqualTo("ENABLED");
  }
}
