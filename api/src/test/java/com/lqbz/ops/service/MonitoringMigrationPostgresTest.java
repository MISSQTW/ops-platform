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

@SpringBootTest(properties = "ops.inspection.scheduler-initial-delay-ms=3600000")
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
  @SpyBean private MonitoringMigrationTopologyLock ownershipLock;
  @Autowired private com.lqbz.ops.web.ResourceController resources;
  @Autowired private javax.sql.DataSource dataSource;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
  @MockBean private HertzBeatClient hertzBeatClient;

  @BeforeEach
  void failExternalSync() throws Exception {
    proposals.deleteAll();
    tasks.deleteAll();
    bindings.deleteAll();
    plans.deleteAll();
    assets.deleteAll();
    projects.deleteAll();
    when(hertzBeatClient.syncMonitor(any(), any(Integer.class), any(Boolean.class)))
        .thenThrow(new IllegalStateException("故障注入：外部暂不可用"));
  }

  @AfterEach
  void clearSpy() { reset(policies, reports, ownershipLock); }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void 服务删除与新绑定准入必须按共同事务锁串行化(boolean deletingFirst) throws Exception {
    String projectName = "PG删除准入" + System.nanoTime();
    Project project = projects.save(new Project(projectName, "负责人", "删除准入竞争", "RUNNING"));
    Asset asset = assets.save(new Asset("删除竞争服务", projectName, "生产", "HTTP", "http://127.0.0.1:1/", "UNKNOWN"));
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch finishFirst = new CountDownLatch(1);
    var firstLock = new java.util.concurrent.atomic.AtomicBoolean(true);
    MonitoringMigrationTopologyLock lockTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(ownershipLock);
    doAnswer(invocation -> {
      Object result = invocation.callRealMethod();
      if (firstLock.compareAndSet(true, false)) {
        locked.countDown();
        if (!finishFirst.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放准入锁");
      }
      return result;
    }).when(lockTarget).acquireServices(java.util.List.of(asset.getId()));
    java.util.concurrent.Callable<Boolean> deleting = () -> { resources.deleteAsset(asset.getId()); return true; };
    java.util.concurrent.Callable<Boolean> creating = () -> {
      policies.create(new com.lqbz.ops.web.InspectionPlanRequest("删除竞争计划", project.getId(), projectName,
          null, null, "ENABLED", null, 60, 2, "CRITICAL", java.util.List.of(asset.getId()), "MONITORING"));
      return true;
    };
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(deletingFirst ? deleting : creating);
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
      var second = executor.submit(deletingFirst ? creating : deleting);
      try {
        boolean blocked = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!blocked && System.nanoTime() < deadline) {
          blocked = Boolean.TRUE.equals(jdbc.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() "
                  + "AND wait_event_type='Lock' AND wait_event='advisory')", Boolean.class));
          if (!blocked) Thread.sleep(20);
        }
        assertThat(blocked).isTrue();
        assertThat(second.isDone()).isFalse();
      } finally { finishFirst.countDown(); }
      assertThat(first.get(30, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class)
          .hasCauseInstanceOf(deletingFirst ? IllegalArgumentException.class : IllegalStateException.class);
      assertThat(assets.existsById(asset.getId())).isEqualTo(!deletingFirst);
      assertThat(bindings.findByActiveOwnerKey(asset.getId()).isPresent()).isEqualTo(!deletingFirst);
    } finally { finishFirst.countDown(); }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void 新接管提交领先时旧任务必须等待并拒绝外部误操作(boolean deleting) throws Exception {
    DisabledFixture fixture = deleting ? releaseFixture() : disabledFixture();
    CountDownLatch inserted = new CountDownLatch(1);
    CountDownLatch commitOwner = new CountDownLatch(1);
    var ownerPid = new java.util.concurrent.atomic.AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var claiming = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager)
          .execute(status -> {
            ownerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            var plan = policies.create(new com.lqbz.ops.web.InspectionPlanRequest("预留抢先接管", fixture.plan().getProjectId(),
                fixture.plan().getProjectName(), null, null, "ENABLED", null, 60, 2, "CRITICAL",
                java.util.List.of(fixture.asset().getId()), "MONITORING"));
            inserted.countDown();
            try {
              if (!commitOwner.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放接管事务");
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(exception);
            }
            return plan;
          }));
      assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
      var correcting = executor.submit(() -> { sync.processTask(fixture.task().getId()); return true; });
      try {
        boolean blocked = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!blocked && System.nanoTime() < deadline) {
          blocked = Boolean.TRUE.equals(jdbc.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>? "
                  + "AND wait_event_type='Lock' AND wait_event='advisory')", Boolean.class, ownerPid.get()));
          if (!blocked) Thread.sleep(20);
        }
        assertThat(blocked).isTrue();
      } finally { commitOwner.countDown(); }
      var owner = claiming.get(30, TimeUnit.SECONDS);
      assertThat(correcting.get(30, TimeUnit.SECONDS)).isTrue();
      assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId())).get()
          .extracting(InspectionPlanBinding::getPlanId).isEqualTo(owner.id());
      assertThat(tasks.findById(fixture.task().getId()).orElseThrow().getStatus()).isEqualTo(deleting ? "SUCCEEDED" : "OBSOLETE");
      assertThat(bindings.findByPlanIdOrderById(fixture.plan().getId()).getFirst().getOwnershipStatus())
          .isEqualTo(deleting ? "RELEASED" : "DISABLED_RELEASED");
      org.mockito.Mockito.verify(hertzBeatClient, org.mockito.Mockito.never())
          .syncMonitor(any(), any(Integer.class), org.mockito.ArgumentMatchers.eq(false));
      org.mockito.Mockito.verify(hertzBeatClient, org.mockito.Mockito.never()).deleteMonitor(501L);
    } finally { commitOwner.countDown(); }
  }

  @Test
  void 新接管计划先提交时旧停用任务不得调用外部资源() {
    DisabledFixture fixture = disabledFixture();
    var replacement = policies.create(new com.lqbz.ops.web.InspectionPlanRequest("抢先接管", fixture.plan().getProjectId(),
        fixture.plan().getProjectName(), null, null, "ENABLED", null, 60, 2, "CRITICAL",
        java.util.List.of(fixture.asset().getId()), "MONITORING"));
    org.mockito.Mockito.clearInvocations(hertzBeatClient);

    sync.processTask(fixture.task().getId());

    assertThat(tasks.findById(fixture.task().getId()).orElseThrow().getStatus()).isEqualTo("OBSOLETE");
    assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId())).get()
        .extracting(InspectionPlanBinding::getPlanId).isEqualTo(replacement.id());
    org.mockito.Mockito.verifyNoInteractions(hertzBeatClient);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void 停用校准预留期间新接管事务必须等待并在释放后成功(boolean externalSuccess) throws Exception {
    DisabledFixture fixture = disabledFixture();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);
    var writerPid = new java.util.concurrent.atomic.AtomicInteger();
    var enabled = new java.util.concurrent.atomic.AtomicBoolean(true);
    when(hertzBeatClient.metricTarget(any())).thenReturn(new HertzBeatClient.MetricTarget("website", "127.0.0.1:1"));
    when(hertzBeatClient.monitorParametersMatch(any(), any())).thenReturn(true);
    when(hertzBeatClient.getMonitorConfig(501L)).thenAnswer(invocation -> new HertzBeatClient.MonitorConfig(
        501L, fixture.asset().getName(), "website", "127.0.0.1:1", 60, "fixed", enabled.get(),
        java.util.Map.of("assetId", String.valueOf(fixture.asset().getId()), "project", fixture.asset().getProjectName(),
            "environment", "生产"), java.util.List.of()));
    when(hertzBeatClient.getAlertRule(601L)).thenReturn(new HertzBeatClient.AlertRule(601L,
        "ops-plan-" + fixture.plan().getId() + "-asset-" + fixture.asset().getId(), "realtime_metric",
        "equals(__app__,\"website\") && equals(__instance__,\"501\") && equals(__available__,\"down\")", 60, 2, false,
        "hertzbeat", java.util.Map.of("managedBy", "ops-platform", "planId", String.valueOf(fixture.plan().getId()),
            "assetId", String.valueOf(fixture.asset().getId()), "severity", "critical"), java.util.Map.of()));
    doAnswer(invocation -> {
      entered.countDown();
      if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放校准事务");
      if (!externalSuccess) throw new IllegalStateException("校准故障注入");
      enabled.set(false);
      return new HertzBeatClient.SyncResult(501L, "校准完成");
    }).when(hertzBeatClient).syncMonitor(any(), any(Integer.class), org.mockito.ArgumentMatchers.eq(false));

    try (var executor = Executors.newFixedThreadPool(2)) {
      var correcting = executor.submit(() -> { sync.processTask(fixture.task().getId()); return true; });
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      var claiming = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager)
          .execute(status -> {
            writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            writerStarted.countDown();
            return policies.create(new com.lqbz.ops.web.InspectionPlanRequest("等待接管", fixture.plan().getProjectId(),
                fixture.plan().getProjectName(), null, null, "ENABLED", null, 60, 2, "CRITICAL",
                java.util.List.of(fixture.asset().getId()), "MONITORING"));
          }));
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
      } finally { release.countDown(); }
      assertThat(correcting.get(30, TimeUnit.SECONDS)).isTrue();
      var owner = claiming.get(30, TimeUnit.SECONDS);
      assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId())).get()
          .extracting(InspectionPlanBinding::getPlanId).isEqualTo(owner.id());
      assertThat(bindings.findByPlanIdOrderById(fixture.plan().getId()).getFirst().getOwnershipStatus()).isEqualTo("DISABLED_RELEASED");
      assertThat(tasks.findById(fixture.task().getId()).orElseThrow().getStatus()).isEqualTo(externalSuccess ? "SUCCEEDED" : "RETRY");
    } finally { release.countDown(); }
  }

  private DisabledFixture disabledFixture() {
    String projectName = "PG停用校准" + System.nanoTime();
    Project project = projects.save(new Project(projectName, "负责人", "独立事务验证", "RUNNING"));
    Asset asset = new Asset("PG停用接口", projectName, "生产", "HTTP", "http://127.0.0.1:1/health", "UNKNOWN");
    asset.markSynced(501L, "测试映射");
    assets.save(asset);
    InspectionPlan plan = InspectionPlan.monitoring("PG停用计划", project.getId(), projectName, 60, 2, "CRITICAL");
    plan.configureStatus("DISABLED");
    plans.save(plan);
    InspectionPlanBinding binding = InspectionPlanBinding.managed(plan.getId(), asset.getId(), 1L);
    binding.alignWithPlanStatus("DISABLED", 1L);
    binding.markSynchronized(501L, 601L, false, LocalDateTime.now());
    binding.releaseDisabledOwnership();
    bindings.save(binding);
    MonitoringConfigTask task = tasks.save(new MonitoringConfigTask(plan.getId(), asset.getId(), 1L, "UPSERT", LocalDateTime.now()));
    return new DisabledFixture(asset, plan, task);
  }

  private DisabledFixture releaseFixture() {
    DisabledFixture fixture = disabledFixture();
    return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
      InspectionPlan plan = plans.findByIdForUpdate(fixture.plan().getId()).orElseThrow();
      plan.markDeleting();
      InspectionPlanBinding binding = bindings.findCurrentForUpdate(plan.getId(), fixture.asset().getId()).orElseThrow();
      binding.requestRelease(plan.getDesiredVersion());
      MonitoringConfigTask task = tasks.saveAndFlush(new MonitoringConfigTask(plan.getId(), fixture.asset().getId(),
          plan.getDesiredVersion(), "DELETE", LocalDateTime.now()));
      return new DisabledFixture(fixture.asset(), plan, task);
    });
  }

  @Test
  void 删除清理预留期间接管必须等待清理提交() throws Exception {
    DisabledFixture fixture = releaseFixture();
    CountDownLatch deleting = new CountDownLatch(1);
    CountDownLatch finishDelete = new CountDownLatch(1);
    CountDownLatch claimingStarted = new CountDownLatch(1);
    doAnswer(invocation -> {
      deleting.countDown();
      if (!finishDelete.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放删除");
      return null;
    }).when(hertzBeatClient).deleteMonitor(501L);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var cleanup = executor.submit(() -> { sync.processTask(fixture.task().getId()); return true; });
      assertThat(deleting.await(10, TimeUnit.SECONDS)).isTrue();
      var claiming = executor.submit(() -> {
        claimingStarted.countDown();
        return policies.create(new com.lqbz.ops.web.InspectionPlanRequest("清理后接管", fixture.plan().getProjectId(),
            fixture.plan().getProjectName(), null, null, "ENABLED", null, 60, 2, "CRITICAL",
            java.util.List.of(fixture.asset().getId()), "MONITORING"));
      });
      try {
        assertThat(claimingStarted.await(10, TimeUnit.SECONDS)).isTrue();
        boolean blocked = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!blocked && System.nanoTime() < deadline) {
          blocked = Boolean.TRUE.equals(jdbc.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() "
                  + "AND wait_event_type='Lock' AND wait_event='advisory')", Boolean.class));
          if (!blocked) Thread.sleep(20);
        }
        assertThat(blocked).isTrue();
        assertThat(claiming.isDone()).isFalse();
      } finally { finishDelete.countDown(); }
      assertThat(cleanup.get(30, TimeUnit.SECONDS)).isTrue();
      var owner = claiming.get(30, TimeUnit.SECONDS);
      assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId())).get()
          .extracting(InspectionPlanBinding::getPlanId).isEqualTo(owner.id());
      assertThat(plans.findById(fixture.plan().getId()).orElseThrow().getLifecycleStatus()).isEqualTo("DELETED");
      assertThat(assets.findById(fixture.asset().getId()).orElseThrow().getHertzbeatMonitorId()).isNull();
      org.mockito.Mockito.verify(hertzBeatClient).deleteMonitor(501L);
    } finally { finishDelete.countDown(); }
  }

  private record DisabledFixture(Asset asset, InspectionPlan plan, MonitoringConfigTask task) {}

  @Test
  void 两个停用计划并发清理共享Monitor时最后引用必须完成实际删除() throws Exception {
    DisabledFixture first = releaseFixture();
    DisabledFixture second = new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
      InspectionPlan plan = InspectionPlan.monitoring("PG共享清理", first.plan().getProjectId(),
          first.plan().getProjectName(), 60, 2, "CRITICAL");
      plan.configureStatus("DISABLED");
      plans.save(plan);
      InspectionPlanBinding binding = InspectionPlanBinding.managed(plan.getId(), first.asset().getId(), 1L);
      binding.markSynchronized(501L, 602L, false, LocalDateTime.now());
      binding.releaseDisabledOwnership();
      plan.markDeleting();
      binding.requestRelease(plan.getDesiredVersion());
      bindings.saveAndFlush(binding);
      MonitoringConfigTask task = tasks.saveAndFlush(new MonitoringConfigTask(plan.getId(), first.asset().getId(),
          plan.getDesiredVersion(), "DELETE", LocalDateTime.now()));
      return new DisabledFixture(first.asset(), plan, task);
    });
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstCleanup = executor.submit(() -> { start.await(); sync.processTask(first.task().getId()); return true; });
      var secondCleanup = executor.submit(() -> { start.await(); sync.processTask(second.task().getId()); return true; });
      start.countDown();
      assertThat(firstCleanup.get(30, TimeUnit.SECONDS)).isTrue();
      assertThat(secondCleanup.get(30, TimeUnit.SECONDS)).isTrue();
    }
    org.mockito.Mockito.verify(hertzBeatClient).deleteMonitor(501L);
    assertThat(assets.findById(first.asset().getId()).orElseThrow().getHertzbeatMonitorId()).isNull();
    assertThat(plans.findById(first.plan().getId()).orElseThrow().getLifecycleStatus()).isEqualTo("DELETED");
    assertThat(plans.findById(second.plan().getId()).orElseThrow().getLifecycleStatus()).isEqualTo("DELETED");
  }

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
