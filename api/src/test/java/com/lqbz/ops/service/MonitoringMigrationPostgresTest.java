package com.lqbz.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.lqbz.ops.domain.*;
import com.lqbz.ops.integration.HertzBeatClient;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "OPS_MIGRATION_TEST_DB_URL",
    matches = "jdbc:postgresql://[^/]+/ops_platform_migration_test_[a-zA-Z0-9_]+")
class MonitoringMigrationPostgresTest {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> System.getenv("OPS_MIGRATION_TEST_DB_URL"));
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add("spring.datasource.username", () -> "ops");
    properties.add("spring.datasource.password", () -> "ops_password");
  }

  @Autowired private MonitoringMigrationConfirmationService migrations;
  @Autowired private MonitoringMigrationProposalRepository proposals;
  @Autowired private ProjectRepository projects;
  @Autowired private AssetRepository assets;
  @Autowired private InspectionPlanRepository plans;
  @Autowired private InspectionPlanBindingRepository bindings;
  @Autowired private MonitoringConfigTaskRepository tasks;
  @Autowired private InspectionPlanMonitoringSyncService sync;
  @Autowired private InspectionExecutionService execution;
  @SpyBean private InspectionPlanPolicyService policies;
  @SpyBean private MonitoringPolicyMigrationService reports;
  @Autowired private javax.sql.DataSource dataSource;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
  @MockBean private HertzBeatClient hertzBeatClient;

  @BeforeEach
  void failExternalSync() throws Exception {
    when(hertzBeatClient.syncMonitor(any(), any(Integer.class), any(Boolean.class)))
        .thenThrow(new IllegalStateException("故障注入：外部暂不可用"));
  }

  @AfterEach
  void clearSpy() { reset(policies, reports); }

  @Test
  void 确认快照检查后并发修改资产必须等待确认提交() throws Exception {
    var proposal = prepare();
    CountDownLatch checked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);
    var writerPid = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(invocation -> {
      Object result = invocation.callRealMethod();
      checked.countDown();
      if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放确认事务");
      return result;
    }).when(reports).inspect(proposal.getAssetId());

    try (var executor = Executors.newFixedThreadPool(2)) {
      var confirmation = executor.submit(() -> migrations.confirm(proposal.getId()));
      assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
      var writer = executor.submit(() -> {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
          statement.setQueryTimeout(10);
          try (var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            result.next();
            writerPid.set(result.getInt(1));
          }
          writerStarted.countDown();
          return statement.executeUpdate("UPDATE ops_asset SET name='并发变更接口' WHERE id=" + proposal.getAssetId());
        }
      });
      try {
        assertThat(writerStarted.await(10, TimeUnit.SECONDS)).isTrue();
        boolean blocked = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!blocked && System.nanoTime() < deadline) {
          blocked = Boolean.TRUE.equals(jdbc.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",
              Boolean.class, writerPid.get()));
          if (!blocked) Thread.sleep(20);
        }
        assertThat(blocked).isTrue();
      } finally {
        release.countDown();
      }
      assertThat(confirmation.get(30, TimeUnit.SECONDS).id()).isEqualTo(proposal.getPreparedPlanId());
      assertThat(writer.get(30, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      release.countDown();
    }
  }

  @Test
  void 两个独立事务并发确认只激活同一计划且失败保留补偿任务() throws Exception {
    var proposal = prepare();
    Long planId = proposal.getPreparedPlanId();
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> { start.await(); return migrations.confirm(proposal.getId()).id(); });
      var second = executor.submit(() -> { start.await(); return migrations.confirm(proposal.getId()).id(); });
      start.countDown();
      assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(planId);
      assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(planId);
    }

    assertThat(proposals.findById(proposal.getId()).orElseThrow().getStatus()).isEqualTo("CONFIRMED");
    assertThat(plans.findById(planId).orElseThrow().getStatus()).isEqualTo("ENABLED");
    assertThat(bindings.findByPlanIdOrderById(planId)).singleElement()
        .satisfies(binding -> assertThat(binding.getOwnershipStatus()).isEqualTo("OWNED"));
    assertThat(tasks.findByPlanIdOrderByCreatedAt(planId)).singleElement()
        .satisfies(task -> assertThat(task.getStatus()).isEqualTo("RETRY"));
  }

  @Test
  void Monitor成功规则失败后重试应复用Monitor且双资源就绪才退出旧调度() throws Exception {
    var proposal = prepare();
    Asset asset = assets.findById(proposal.getAssetId()).orElseThrow();
    InspectionPlan legacy = plans.findAll().stream()
        .filter(plan -> !plan.isMonitoringPolicy() && plan.getProjectName().equals(asset.getProjectName()))
        .findFirst().orElseThrow();
    org.mockito.Mockito.doReturn(new HertzBeatClient.SyncResult(501L, "Monitor成功"))
        .when(hertzBeatClient).syncMonitor(any(), any(Integer.class), any(Boolean.class));
    when(hertzBeatClient.getMonitorConfig(501L)).thenReturn(new HertzBeatClient.MonitorConfig(
        501L, asset.getName(), "website", "127.0.0.1:1", 60, "fixed", true,
        java.util.Map.of("assetId", String.valueOf(asset.getId()), "project", asset.getProjectName(),
            "environment", asset.getEnvironmentName()), java.util.List.of()));
    when(hertzBeatClient.metricTarget(any())).thenReturn(new HertzBeatClient.MetricTarget("website", "127.0.0.1:1"));
    when(hertzBeatClient.monitorParametersMatch(any(), any())).thenReturn(true);
    when(hertzBeatClient.upsertAlertRule(any())).thenThrow(new IllegalStateException("故障注入：规则失败"));

    var plan = migrations.confirm(proposal.getId());
    assertThat(bindings.findByPlanIdOrderById(plan.id())).singleElement().satisfies(binding -> {
      assertThat(binding.getMonitorActualState()).isEqualTo("ENABLED");
      assertThat(binding.getRuleActualState()).isEqualTo("FAILED");
      assertThat(binding.getHertzbeatMonitorId()).isEqualTo(501L);
    });
    assertThatThrownBy(() -> execution.execute(plan.id())).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("原有巡检计划");
    assertThat(execution.execute(legacy.getId()).getTotalChecks()).isEqualTo(1);
    org.mockito.Mockito.doAnswer(invocation -> {
      HertzBeatClient.AlertRuleSpec spec = invocation.getArgument(0);
      return new HertzBeatClient.AlertRule(601L, spec.name(), "realtime_metric", spec.expression(), spec.period(),
          spec.times(), true, "hertzbeat", java.util.Map.of("managedBy", "ops-platform",
              "planId", String.valueOf(spec.planId()), "assetId", String.valueOf(spec.assetId()),
              "severity", spec.level().toLowerCase()), java.util.Map.of("summary", spec.name()));
    }).when(hertzBeatClient).upsertAlertRule(any());

    sync.syncPlan(plan.id());

    assertThat(plans.findById(plan.id()).orElseThrow().getSyncStatus()).isEqualTo("SYNCED");
    org.mockito.Mockito.verify(hertzBeatClient, org.mockito.Mockito.times(1))
        .syncMonitor(any(), any(Integer.class), any(Boolean.class));
    assertThatThrownBy(() -> execution.execute(legacy.getId())).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HertzBeat");
  }

  @Test
  void 确认提交前失败应回滚计划绑定任务和提案() {
    var proposal = prepare();
    Long planId = proposal.getPreparedPlanId();
    doAnswer(invocation -> {
      invocation.callRealMethod();
      throw new IllegalStateException("故障注入：提交前失败");
    }).when(policies).confirmMigration(planId, proposal.getAssetId());

    assertThatThrownBy(() -> migrations.confirm(proposal.getId())).isInstanceOf(IllegalStateException.class);

    assertThat(proposals.findById(proposal.getId()).orElseThrow().getStatus()).isEqualTo("PENDING_CONFIRMATION");
    assertThat(plans.findById(planId).orElseThrow().getStatus()).isEqualTo("PENDING_CONFIRMATION");
    assertThat(bindings.findByPlanIdOrderById(planId)).singleElement()
        .satisfies(binding -> assertThat(binding.getOwnershipStatus()).isEqualTo("MIGRATION_PENDING"));
    assertThat(tasks.findByPlanIdOrderByCreatedAt(planId)).isEmpty();
  }

  private MonitoringMigrationProposal prepare() {
    String projectName = "PG迁移系统" + System.nanoTime();
    Project project = projects.save(new Project(projectName, "负责人", "独立事务验证", "RUNNING"));
    Asset asset = assets.save(new Asset("PG迁移接口", projectName, "生产", "HTTP", "http://127.0.0.1:1/health", "UNKNOWN"));
    plans.save(new InspectionPlan("旧PG巡检", projectName, "全部服务", "每 1 分钟", "ENABLED", LocalDateTime.now()));
    var proposal = migrations.prepare(asset.getId(), project.getId(), null);
    assertThatThrownBy(() -> execution.execute(proposal.getPreparedPlanId())).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("待确认");
    return proposal;
  }
}
