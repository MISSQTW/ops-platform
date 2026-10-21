package com.lqbz.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.InspectionPlanBinding;
import com.lqbz.ops.domain.InspectionPlanBindingRepository;
import com.lqbz.ops.domain.InspectionPlanRepository;
import com.lqbz.ops.domain.MonitoringConfigTask;
import com.lqbz.ops.domain.MonitoringConfigTaskRepository;
import com.lqbz.ops.domain.Project;
import com.lqbz.ops.domain.ProjectRepository;
import com.lqbz.ops.integration.HertzBeatClient;
import com.lqbz.ops.web.InspectionPlanRequest;
import java.time.LocalDateTime;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@ActiveProfiles("test")
class InspectionPlanMonitoringSyncTest {
  @Autowired private ProjectRepository projects;
  @Autowired private AssetRepository assets;
  @Autowired private InspectionPlanRepository plans;
  @Autowired private InspectionPlanBindingRepository bindings;
  @Autowired private MonitoringConfigTaskRepository tasks;
  @Autowired private InspectionPlanPolicyService policyService;
  @Autowired private InspectionPlanMonitoringSyncService syncService;
  @MockBean private HertzBeatClient hertzBeatClient;
  private final AtomicReference<Asset> lastSyncedAsset = new AtomicReference<>();
  private final AtomicReference<Integer> lastInterval = new AtomicReference<>();
  private final AtomicReference<Boolean> lastEnabled = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    tasks.deleteAll();
    bindings.deleteAll();
    plans.deleteAll();
    assets.deleteAll();
    projects.deleteAll();
    when(hertzBeatClient.syncMonitor(any(), any(Integer.class), any(Boolean.class)))
        .thenAnswer(invocation -> {
          lastSyncedAsset.set(invocation.getArgument(0));
          lastInterval.set(invocation.getArgument(1));
          lastEnabled.set(invocation.getArgument(2));
          return new HertzBeatClient.SyncResult(501L, "同步成功");
        });
    when(hertzBeatClient.monitorCredentialsMatch(any(), anyLong())).thenReturn(true);
    when(hertzBeatClient.getMonitorConfig(501L)).thenAnswer(invocation -> monitorConfig());
    when(hertzBeatClient.monitorParametersMatch(any(), any())).thenAnswer(invocation ->
        parameterClient().monitorParametersMatch(invocation.getArgument(0), invocation.getArgument(1)));
    when(hertzBeatClient.metricTarget(any())).thenAnswer(invocation -> {
      Asset asset = invocation.getArgument(0);
      URI endpoint = URI.create(asset.getEndpoint());
      return new HertzBeatClient.MetricTarget("website",
          endpoint.getHost() + ":" + (endpoint.getPort() > 0 ? endpoint.getPort() : 443));
    });
    when(hertzBeatClient.upsertAlertRule(any()))
        .thenAnswer(invocation -> {
          HertzBeatClient.AlertRuleSpec spec = invocation.getArgument(0);
          return new HertzBeatClient.AlertRule(601L, spec.name(), "realtime_metric", spec.expression(), spec.period(),
              spec.times(), spec.enable(), "hertzbeat", java.util.Map.of(
                  "managedBy", "ops-platform", "planId", String.valueOf(spec.planId()),
                  "assetId", String.valueOf(spec.assetId()), "severity", spec.level().toLowerCase()),
              java.util.Map.of("summary", spec.name()));
        });
  }

  @Test
  void 保存计划后应自动同步Monitor和告警规则() throws Exception {
    Fixture fixture = fixture("自动同步系统", "自动同步接口");

    InspectionPlanPolicyService.InspectionPlanView plan = policyService.create(request(
        "核心服务巡检", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));

    InspectionPlanBinding binding = bindings.findByPlanIdOrderById(plan.id()).getFirst();
    assertThat(binding.getHertzbeatMonitorId()).isEqualTo(501L);
    assertThat(binding.getHertzbeatAlertRuleId()).isEqualTo(601L);
    assertThat(binding.getMonitorActualState()).isEqualTo("ENABLED");
    assertThat(binding.getRuleActualState()).isEqualTo("ENABLED");
    org.mockito.Mockito.verify(hertzBeatClient).upsertAlertRule(org.mockito.ArgumentMatchers.argThat(spec ->
        spec.expression().equals("equals(__app__,\"website\") && equals(__instance__,\"501\") && equals(__available__,\"down\")")));
    assertThat(tasks.findByPlanIdOrderByCreatedAt(plan.id())).singleElement()
        .extracting(MonitoringConfigTask::getStatus).isEqualTo("SUCCEEDED");
    assertThat(plans.findById(plan.id()).orElseThrow().getSyncStatus()).isEqualTo("SYNCED");
  }

  @Test
  void 单服务失败不应回滚其他服务且计划应标记部分成功() throws Exception {
    Project project = projects.save(new Project("部分同步系统", "业务系统", null, null, null, null, "RUNNING"));
    Asset first = assets.save(new Asset("正常接口", project.getName(), "生产", "HTTP",
        "https://ok.example.com/health", "UNKNOWN"));
    Asset second = assets.save(new Asset("失败接口", project.getName(), "生产", "HTTP",
        "https://failed.example.com/health", "UNKNOWN"));
    when(hertzBeatClient.syncMonitor(any(), any(Integer.class), eq(true)))
        .thenAnswer(invocation -> {
          Asset asset = invocation.getArgument(0);
          if (asset.getId().equals(second.getId())) throw new IllegalStateException("HertzBeat 不可用");
          lastSyncedAsset.set(asset);
          lastInterval.set(invocation.getArgument(1));
          lastEnabled.set(invocation.getArgument(2));
          return new HertzBeatClient.SyncResult(502L, "同步成功");
        });
    when(hertzBeatClient.getMonitorConfig(502L)).thenAnswer(invocation -> monitorConfig(502L));

    InspectionPlanPolicyService.InspectionPlanView plan = policyService.create(request(
        "部分同步计划", project.getId(), project.getName(), List.of(first.getId(), second.getId())));

    List<InspectionPlanBinding> result = bindings.findByPlanIdOrderById(plan.id());
    assertThat(result).extracting(InspectionPlanBinding::getMonitorActualState)
        .containsExactlyInAnyOrder("ENABLED", "FAILED");
    assertThat(tasks.findByPlanIdOrderByCreatedAt(plan.id())).extracting(MonitoringConfigTask::getStatus)
        .containsExactlyInAnyOrder("SUCCEEDED", "RETRY");
    assertThat(plans.findById(plan.id()).orElseThrow().getSyncStatus()).isEqualTo("PARTIAL");
  }

  @Test
  void 手动同步应重新执行失败任务() throws Exception {
    Fixture fixture = fixture("补偿同步系统", "补偿同步接口");
    doThrow(new IllegalStateException("首次失败")).when(hertzBeatClient)
        .syncMonitor(any(), any(Integer.class), eq(true));
    InspectionPlanPolicyService.InspectionPlanView plan = policyService.create(request(
        "补偿同步计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));
    when(hertzBeatClient.syncMonitor(any(), any(Integer.class), eq(true)))
        .thenAnswer(invocation -> {
          lastSyncedAsset.set(invocation.getArgument(0));
          lastInterval.set(invocation.getArgument(1));
          lastEnabled.set(true);
          return new HertzBeatClient.SyncResult(503L, "同步成功");
        });
    when(hertzBeatClient.getMonitorConfig(503L)).thenAnswer(invocation -> monitorConfig(503L));

    syncService.syncPlan(plan.id());

    assertThat(plans.findById(plan.id()).orElseThrow().getSyncStatus()).isEqualTo("SYNCED");
    assertThat(bindings.findByPlanIdOrderById(plan.id()).getFirst().getHertzbeatMonitorId()).isEqualTo(503L);
  }

  @Test
  void 旧版本任务不得覆盖新计划配置() {
    Fixture fixture = fixture("版本同步系统", "版本同步接口");
    InspectionPlanPolicyService.InspectionPlanView plan = policyService.create(request(
        "版本一", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));
    MonitoringConfigTask obsolete = tasks.findByPlanIdOrderByCreatedAt(plan.id()).getFirst();
    obsolete.reschedule(LocalDateTime.now());
    tasks.save(obsolete);
    policyService.update(plan.id(), request(
        "版本二", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));

    syncService.processTask(obsolete.getId());

    assertThat(tasks.findById(obsolete.getId()).orElseThrow().getStatus()).isEqualTo("OBSOLETE");
  }

  @Test
  void 手动同步应读取外部实际配置后修复漂移() throws Exception {
    Fixture fixture = fixture("漂移校准系统", "漂移校准接口");
    InspectionPlanPolicyService.InspectionPlanView plan = policyService.create(request(
        "漂移校准计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));
    when(hertzBeatClient.getMonitorConfig(501L))
        .thenReturn(new HertzBeatClient.MonitorConfig(
            501L, "被人工修改", "website", "wrong.example.com:443", 60, "fixed", false,
            java.util.Map.of(), List.of()))
        .thenAnswer(invocation -> monitorConfig());
    when(hertzBeatClient.getAlertRule(601L)).thenReturn(new HertzBeatClient.AlertRule(
        601L, "被人工修改", "realtime", "availability == 1", 60, 1, false,
        "hertzbeat", java.util.Map.of(), java.util.Map.of()));

    syncService.syncPlan(plan.id());

    verify(hertzBeatClient, atLeastOnce()).getMonitorConfig(501L);
    verify(hertzBeatClient, atLeastOnce()).getAlertRule(601L);
    InspectionPlanBinding binding = bindings.findByPlanIdOrderById(plan.id()).getFirst();
    assertThat(binding.getLastVerifiedAt()).isNotNull();
    assertThat(plans.findById(plan.id()).orElseThrow().getSyncStatus()).isEqualTo("SYNCED");
  }

  @Test
  void 仅密码漂移会原位补偿而凭据未变不重置采集() throws Exception {
    Fixture fixture = fixture("凭据校准系统", "凭据校准接口");
    var plan = policyService.create(request("凭据校准计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    clearInvocations(hertzBeatClient);
    when(hertzBeatClient.monitorCredentialsMatch(any(), eq(501L))).thenReturn(false, true);
    assertThat(syncService.syncPlan(plan.id()).status()).isEqualTo("SYNCED");
    verify(hertzBeatClient).syncMonitor(any(), eq(300), eq(true));
    clearInvocations(hertzBeatClient);
    assertThat(syncService.syncPlan(plan.id()).status()).isEqualTo("SYNCED");
    verify(hertzBeatClient, never()).syncMonitor(any(), any(Integer.class), any(Boolean.class));
  }

  @Test
  void 删除计划应在外部资源删除成功后释放服务所有权() throws Exception {
    Fixture fixture = fixture("删除同步系统", "删除同步接口");
    InspectionPlanPolicyService.InspectionPlanView plan = policyService.create(request(
        "删除同步计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));

    policyService.requestDelete(plan.id());

    InspectionPlanBinding binding = bindings.findByPlanIdOrderById(plan.id()).getFirst();
    assertThat(binding.getOwnershipStatus()).isEqualTo("RELEASED");
    assertThat(binding.getActiveOwnerKey()).isNull();
    assertThat(assets.findById(fixture.asset().getId()).orElseThrow().getHertzbeatMonitorId()).isNull();
    assertThat(plans.findById(plan.id()).orElseThrow().getLifecycleStatus()).isEqualTo("DELETED");
    verify(hertzBeatClient).deleteAlertRule(601L);
    verify(hertzBeatClient).deleteMonitor(501L);
    Long deletedVersion = plans.findById(plan.id()).orElseThrow().getDesiredVersion();
    policyService.requestDelete(plan.id());
    assertThat(plans.findById(plan.id()).orElseThrow().getLifecycleStatus()).isEqualTo("DELETED");
    assertThat(plans.findById(plan.id()).orElseThrow().getDesiredVersion()).isEqualTo(deletedVersion);
  }

  @Test
  void 崩溃遗留的运行中任务应恢复并重新执行() {
    Fixture fixture = fixture("恢复任务系统", "恢复任务接口");
    InspectionPlanPolicyService.InspectionPlanView plan = policyService.create(request(
        "恢复任务计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));
    MonitoringConfigTask task = tasks.findByPlanIdOrderByCreatedAt(plan.id()).getFirst();
    task.reschedule(LocalDateTime.now().minusMinutes(3));
    task.claim(LocalDateTime.now().minusMinutes(2));
    tasks.save(task);

    syncService.reconcile();

    assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
  }

  @Test
  void Monitor重建后应更新原规则中的实例ID() throws Exception {
    Fixture fixture = fixture("监控重建系统", "监控重建接口");
    InspectionPlanPolicyService.InspectionPlanView plan = policyService.create(request(
        "监控重建计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));
    var ruleCaptor = org.mockito.ArgumentCaptor.forClass(HertzBeatClient.AlertRuleSpec.class);
    verify(hertzBeatClient).upsertAlertRule(ruleCaptor.capture());
    HertzBeatClient.AlertRuleSpec original = ruleCaptor.getValue();
    when(hertzBeatClient.getAlertRule(601L)).thenReturn(new HertzBeatClient.AlertRule(
        601L, original.name(), "realtime_metric", original.expression(), original.period(), original.times(),
        original.enable(), "hertzbeat", java.util.Map.of("managedBy", "ops-platform",
            "planId", String.valueOf(plan.id()), "assetId", String.valueOf(fixture.asset().getId()),
            "severity", "critical"), java.util.Map.of("summary", original.name())));
    when(hertzBeatClient.getMonitorConfig(501L)).thenThrow(new HertzBeatClient.MonitorNotFoundException(501L));
    when(hertzBeatClient.syncMonitor(any(), any(Integer.class), any(Boolean.class)))
        .thenAnswer(invocation -> {
          lastSyncedAsset.set(invocation.getArgument(0));
          lastInterval.set(invocation.getArgument(1));
          lastEnabled.set(invocation.getArgument(2));
          return new HertzBeatClient.SyncResult(502L, "重建成功");
        });
    when(hertzBeatClient.getMonitorConfig(502L)).thenAnswer(invocation -> monitorConfig(502L));
    clearInvocations(hertzBeatClient);

    syncService.syncPlan(plan.id());

    verify(hertzBeatClient).upsertAlertRule(argThat(spec -> Long.valueOf(601L).equals(spec.id())
        && spec.expression().equals("equals(__app__,\"website\") && equals(__instance__,\"502\") && equals(__available__,\"down\")")));
    assertThat(plans.findById(plan.id()).orElseThrow().getSyncStatus()).isEqualTo("SYNCED");
  }

  @Test
  void 外部告警规则被删除后应按受管标签重新创建() throws Exception {
    Fixture fixture = fixture("规则补偿系统", "规则补偿接口");
    InspectionPlanPolicyService.InspectionPlanView plan = policyService.create(request(
        "规则补偿计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));
    clearInvocations(hertzBeatClient);
    when(hertzBeatClient.getAlertRule(601L))
        .thenThrow(new HertzBeatClient.AlertRuleNotFoundException("601"));

    syncService.syncPlan(plan.id());

    verify(hertzBeatClient).upsertAlertRule(argThat(spec -> spec.id() == null
        && spec.planId().equals(plan.id()) && spec.assetId().equals(fixture.asset().getId())));
  }

  @Test
  void 停用成功后应释放所有权且重新启用不能抢占其他计划() {
    Fixture fixture = fixture("停用接管系统", "停用接管接口");
    InspectionPlanPolicyService.InspectionPlanView first = policyService.create(request(
        "原计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));
    policyService.update(first.id(), request("原计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));

    InspectionPlanBinding disabled = bindings.findByPlanIdOrderById(first.id()).getFirst();
    assertThat(disabled.getOwnershipStatus()).isEqualTo("DISABLED_RELEASED");
    assertThat(disabled.getActiveOwnerKey()).isNull();
    InspectionPlanPolicyService.InspectionPlanView second = policyService.create(request(
        "接管计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));

    assertThatThrownBy(() -> policyService.update(first.id(), request("原计划", fixture.project().getId(),
        fixture.project().getName(), List.of(fixture.asset().getId()), "ENABLED")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("已加入其他启用计划");
    assertThat(plans.findById(first.id()).orElseThrow().getStatus()).isEqualTo("DISABLED");
    assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId()).orElseThrow().getPlanId()).isEqualTo(second.id());
  }

  @Test
  void Monitor写后参数仍漂移必须保留失败重试() throws Exception {
    Fixture fixture = fixture("写后漂移系统", "写后漂移接口");
    var plan = policyService.create(request("写后漂移计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    HertzBeatClient.MonitorConfig stale = monitorConfig();
    when(hertzBeatClient.getMonitorConfig(501L)).thenReturn(stale);

    policyService.updateAsset(fixture.asset().getId(), fixture.asset().getName(), fixture.project().getName(),
        "生产", "HTTP", "https://example.com/ready", "UNKNOWN");

    assertThat(plans.findById(plan.id()).orElseThrow().getSyncStatus()).isEqualTo("FAILED");
    assertThat(tasks.findByPlanIdOrderByCreatedAt(plan.id()).stream()
        .filter(task -> task.getDesiredVersion().equals(2L)).toList())
        .singleElement().satisfies(task -> assertThat(task.getStatus()).isEqualTo("RETRY"));
  }

  @Test
  void 同主机HTTP路径变化必须重新下发Monitor参数() throws Exception {
    Fixture fixture = fixture("路径变更系统", "路径接口");
    var plan = policyService.create(request("路径计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    clearInvocations(hertzBeatClient);

    policyService.updateAsset(fixture.asset().getId(), fixture.asset().getName(), fixture.project().getName(),
        "生产", "HTTP", "https://example.com/ready", "UNKNOWN");

    verify(hertzBeatClient).syncMonitor(argThat(asset -> asset.getEndpoint().endsWith("/ready")), eq(300), eq(true));
    assertThat(plans.findById(plan.id()).orElseThrow().getSyncStatus()).isEqualTo("SYNCED");
  }

  @Test
  void 服务关联启用或停用计划时不得跨项目移动() {
    Fixture fixture = fixture("项目归属系统", "归属接口");
    Project other = projects.save(new Project("另一个系统", "业务系统", "负责人", null, null, null, "RUNNING"));
    var plan = policyService.create(request("归属计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> policyService.updateAsset(fixture.asset().getId(),
        "归属接口", other.getName(), "生产", "HTTP", fixture.asset().getEndpoint(), "UNKNOWN"))
        .isInstanceOf(IllegalStateException.class);
    policyService.update(plan.id(), request("归属计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> policyService.updateAsset(fixture.asset().getId(),
        "归属接口", other.getName(), "生产", "HTTP", fixture.asset().getEndpoint(), "UNKNOWN"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 修改单服务后同计划其他绑定必须跟随版本且继续同步() {
    Fixture fixture = fixture("多服务版本系统", "首个接口");
    Asset second = assets.save(new Asset("第二接口", fixture.project().getName(), "生产", "HTTP",
        "https://second.example.com/health", "UNKNOWN"));
    var plan = policyService.create(request("多服务计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId(), second.getId())));
    policyService.updateAsset(fixture.asset().getId(), "修改后的接口", fixture.project().getName(), "生产",
        "HTTP", fixture.asset().getEndpoint(), "UNKNOWN");
    Long version = plans.findById(plan.id()).orElseThrow().getDesiredVersion();
    assertThat(bindings.findByPlanIdOrderById(plan.id())).allSatisfy(binding ->
        assertThat(binding.getDesiredVersion()).isEqualTo(version));
    assertThat(tasks.findByPlanIdOrderByCreatedAt(plan.id()).stream()
        .filter(task -> task.getDesiredVersion().equals(version)).toList())
        .hasSize(2).allSatisfy(task -> assertThat(task.getStatus()).isEqualTo("SUCCEEDED"));
  }

  @Test
  void 服务资料变化应递增计划版本并生成新任务() {
    Fixture fixture = fixture("服务版本系统", "服务版本接口");
    InspectionPlanPolicyService.InspectionPlanView created = policyService.create(request(
        "服务版本计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));
    Asset asset = assets.findById(fixture.asset().getId()).orElseThrow();
    policyService.updateAsset(asset.getId(), "服务版本新接口", asset.getProjectName(), asset.getEnvironmentName(),
        asset.getType(), "https://new.example.com/health", asset.getStatus());

    long currentVersion = plans.findById(created.id()).orElseThrow().getDesiredVersion();
    assertThat(currentVersion).isEqualTo(created.desiredVersion() + 1);
    assertThat(tasks.findByPlanIdOrderByCreatedAt(created.id())).extracting(MonitoringConfigTask::getDesiredVersion)
        .contains(currentVersion);
  }

  @Test
  void 同一版本重复同步应复用唯一任务() {
    Fixture fixture = fixture("任务防重系统", "任务防重接口");
    InspectionPlanPolicyService.InspectionPlanView plan = policyService.create(request(
        "任务防重计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));

    syncService.syncPlan(plan.id());
    syncService.syncPlan(plan.id());

    assertThat(tasks.findByPlanIdOrderByCreatedAt(plan.id())).singleElement();
  }

  @Test
  void 停用后无人接管时应纠正外部监控被重新启用的漂移() throws Exception {
    Fixture fixture = fixture("停用漂移系统", "停用漂移接口");
    var plan = policyService.create(request("停用漂移计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    policyService.update(plan.id(), request("停用漂移计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    lastEnabled.set(true);
    org.mockito.Mockito.clearInvocations(hertzBeatClient);
    org.mockito.Mockito.doAnswer(invocation -> {
      assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId())).get()
          .extracting(InspectionPlanBinding::getOwnershipStatus).isEqualTo("DISABLED_RECONCILING");
      lastSyncedAsset.set(invocation.getArgument(0));
      lastInterval.set(invocation.getArgument(1));
      lastEnabled.set(false);
      return new HertzBeatClient.SyncResult(501L, "停用校准成功");
    }).when(hertzBeatClient).syncMonitor(any(), any(Integer.class), eq(false));

    syncService.syncPlan(plan.id());

    org.mockito.Mockito.verify(hertzBeatClient).syncMonitor(any(), any(Integer.class), eq(false));
    assertThat(bindings.findByPlanIdOrderById(plan.id()).getFirst().getOwnershipStatus()).isEqualTo("DISABLED_RELEASED");
    assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId())).isEmpty();
    assertThat(plans.findById(plan.id()).orElseThrow().getSyncStatus()).isEqualTo("SYNCED");
  }

  @Test
  void 停用漂移校准失败后必须释放临时预留且保留重试任务() throws Exception {
    Fixture fixture = fixture("停用漂移失败系统", "停用漂移失败接口");
    var plan = policyService.create(request("停用漂移失败计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    policyService.update(plan.id(), request("停用漂移失败计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    lastEnabled.set(true);
    doThrow(new IllegalStateException("停用校准不可用")).when(hertzBeatClient)
        .syncMonitor(any(), any(Integer.class), eq(false));

    syncService.syncPlan(plan.id());

    assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId())).isEmpty();
    assertThat(bindings.findByPlanIdOrderById(plan.id()).getFirst().getOwnershipStatus()).isEqualTo("DISABLED_RELEASED");
    assertThat(tasks.findByPlanIdOrderByCreatedAt(plan.id()).stream().filter(task -> task.getStatus().equals("RETRY"))).hasSize(1);
    var replacement = policyService.create(request("失败后接管计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId())).get()
        .extracting(InspectionPlanBinding::getPlanId).isEqualTo(replacement.id());
  }

  @Test
  void 服务被新计划接管后旧停用计划不应继续下发禁用配置() throws Exception {
    Fixture fixture = fixture("接管校准系统", "接管校准接口");
    InspectionPlanPolicyService.InspectionPlanView oldPlan = policyService.create(request(
        "旧停用计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));
    policyService.update(oldPlan.id(), request("旧停用计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    policyService.create(request("新接管计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    clearInvocations(hertzBeatClient);

    policyService.updateAsset(fixture.asset().getId(), "接管校准新接口", fixture.project().getName(), "生产",
        "HTTP", "https://changed.example.com/health", "UNKNOWN");
    syncService.reconcile();

    verify(hertzBeatClient, never()).syncMonitor(any(), any(Integer.class), eq(false));
  }

  @Test
  void 停用后无人接管删除计划必须删除Monitor并解绑服务() throws Exception {
    Fixture fixture = fixture("停用后删除系统", "停用后删除接口");
    var plan = policyService.create(request("停用后删除计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    policyService.update(plan.id(), request("停用后删除计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    clearInvocations(hertzBeatClient);

    policyService.requestDelete(plan.id());

    verify(hertzBeatClient).deleteMonitor(501L);
    verify(hertzBeatClient).deleteAlertRule(601L);
    assertThat(assets.findById(fixture.asset().getId()).orElseThrow().getHertzbeatMonitorId()).isNull();
    assertThat(plans.findById(plan.id()).orElseThrow().getLifecycleStatus()).isEqualTo("DELETED");
  }

  @Test
  void 当前owner先删除必须保留停用历史引用直到最后计划清理() throws Exception {
    Fixture fixture = fixture("当前接管删除系统", "当前接管删除接口");
    var old = policyService.create(request("停用历史计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    policyService.update(old.id(), request("停用历史计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    var current = policyService.create(request("当前启用计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    clearInvocations(hertzBeatClient);

    policyService.requestDelete(current.id());

    verify(hertzBeatClient, never()).deleteMonitor(anyLong());
    assertThat(assets.findById(fixture.asset().getId()).orElseThrow().getHertzbeatMonitorId()).isEqualTo(501L);
    assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId())).isEmpty();
    policyService.requestDelete(old.id());
    verify(hertzBeatClient).deleteMonitor(501L);
    assertThat(assets.findById(fixture.asset().getId()).orElseThrow().getHertzbeatMonitorId()).isNull();
  }

  @Test
  void 接管首次同步失败后删除不得误删停用历史引用的Monitor() throws Exception {
    Fixture fixture = fixture("首次接管失败系统", "首次接管失败接口");
    var old = policyService.create(request("失败前历史计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    policyService.update(old.id(), request("失败前历史计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    when(hertzBeatClient.getMonitorConfig(501L)).thenThrow(new IllegalStateException("临时不可用"));
    var current = policyService.create(request("首次同步失败计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    assertThat(bindings.findByPlanIdOrderById(current.id()).getFirst().getHertzbeatMonitorId()).isNull();
    org.mockito.Mockito.doAnswer(invocation -> monitorConfig()).when(hertzBeatClient).getMonitorConfig(501L);
    clearInvocations(hertzBeatClient);

    policyService.requestDelete(current.id());

    verify(hertzBeatClient, never()).deleteMonitor(anyLong());
    assertThat(assets.findById(fixture.asset().getId()).orElseThrow().getHertzbeatMonitorId()).isEqualTo(501L);
    assertThat(plans.findById(current.id()).orElseThrow().getLifecycleStatus()).isEqualTo("DELETED");
    policyService.requestDelete(old.id());
    verify(hertzBeatClient).deleteMonitor(501L);
    assertThat(assets.findById(fixture.asset().getId()).orElseThrow().getHertzbeatMonitorId()).isNull();
  }

  @Test
  void 往返接管复用旧绑定后删除另一计划必须保留共享Monitor直到最后引用释放() throws Exception {
    Fixture fixture = fixture("往返停用系统", "往返停用接口");
    var first = policyService.create(request("往返计划A", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    policyService.update(first.id(), request("往返计划A", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    var second = policyService.create(request("往返计划B", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    policyService.update(second.id(), request("往返计划B", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    policyService.update(first.id(), request("往返计划A", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "ENABLED"));
    policyService.update(first.id(), request("往返计划A", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    clearInvocations(hertzBeatClient);

    policyService.requestDelete(second.id());

    verify(hertzBeatClient, never()).deleteMonitor(anyLong());
    assertThat(assets.findById(fixture.asset().getId()).orElseThrow().getHertzbeatMonitorId()).isEqualTo(501L);
    policyService.requestDelete(first.id());
    verify(hertzBeatClient).deleteMonitor(501L);
    assertThat(assets.findById(fixture.asset().getId()).orElseThrow().getHertzbeatMonitorId()).isNull();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void 移除清理失败后同计划重新加入必须恢复已有批准绑定(boolean ruleFailure) throws Exception {
    Fixture fixture = fixture("撤销移除系统", "撤销移除接口");
    Asset retained = assets.save(new Asset("撤销保留服务", fixture.project().getName(), "生产", "HTTP",
        "https://retained.example.com/health", "UNKNOWN"));
    when(hertzBeatClient.syncMonitor(any(), any(Integer.class), any(Boolean.class))).thenAnswer(invocation -> {
      Asset asset = invocation.getArgument(0);
      lastSyncedAsset.set(asset);
      lastInterval.set(invocation.getArgument(1));
      lastEnabled.set(invocation.getArgument(2));
      return new HertzBeatClient.SyncResult(asset.getId().equals(retained.getId()) ? 502L : 501L, "独立Monitor");
    });
    when(hertzBeatClient.getMonitorConfig(502L)).thenAnswer(invocation -> monitorConfig(502L));
    var plan = policyService.create(request("撤销移除计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId(), retained.getId())));
    Long originalBindingId = bindings.findByActiveOwnerKey(fixture.asset().getId()).orElseThrow().getId();
    if (ruleFailure) doThrow(new IllegalStateException("规则删除失败")).when(hertzBeatClient).deleteAlertRule(601L);
    else doThrow(new IllegalStateException("Monitor删除失败")).when(hertzBeatClient).deleteMonitor(501L);
    policyService.update(plan.id(), request("撤销移除计划", fixture.project().getId(), fixture.project().getName(),
        List.of(retained.getId())));
    assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId())).get()
        .extracting(InspectionPlanBinding::getOwnershipStatus).isEqualTo("PENDING_RELEASE");

    policyService.update(plan.id(), request("撤销移除计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId(), retained.getId())));

    assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId())).get().satisfies(binding -> {
      assertThat(binding.getId()).isEqualTo(originalBindingId);
      assertThat(binding.getOwnershipStatus()).isEqualTo("OWNED");
      assertThat(binding.getMonitorDesiredState()).isEqualTo("ENABLED");
    });
  }

  @Test
  void 后续接管计划再次停用后旧计划删除不得删除仍被引用的Monitor() throws Exception {
    Fixture fixture = fixture("多轮停用系统", "多轮停用接口");
    var old = policyService.create(request("首轮停用计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    policyService.update(old.id(), request("首轮停用计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    var next = policyService.create(request("再次停用计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    policyService.update(next.id(), request("再次停用计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    clearInvocations(hertzBeatClient);

    policyService.requestDelete(old.id());

    verify(hertzBeatClient, never()).deleteMonitor(anyLong());
    assertThat(assets.findById(fixture.asset().getId()).orElseThrow().getHertzbeatMonitorId()).isEqualTo(501L);
    assertThat(plans.findById(old.id()).orElseThrow().getLifecycleStatus()).isEqualTo("DELETED");
  }

  @Test
  void 删除历史停用计划不得清空后来重建的Monitor映射() throws Exception {
    Fixture fixture = fixture("历史停用删除系统", "历史停用删除接口");
    var plan = policyService.create(request("历史停用删除计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    policyService.update(plan.id(), request("历史停用删除计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    Asset current = assets.findById(fixture.asset().getId()).orElseThrow();
    current.markSynced(502L, "后续计划重建的监控");
    assets.saveAndFlush(current);
    clearInvocations(hertzBeatClient);

    policyService.requestDelete(plan.id());

    verify(hertzBeatClient).deleteMonitor(501L);
    verify(hertzBeatClient, never()).deleteMonitor(502L);
    assertThat(assets.findById(current.getId()).orElseThrow().getHertzbeatMonitorId()).isEqualTo(502L);
    assertThat(plans.findById(plan.id()).orElseThrow().getLifecycleStatus()).isEqualTo("DELETED");
  }

  @Test
  void 停用后无人接管移除服务必须删除Monitor() throws Exception {
    Fixture fixture = fixture("停用后移除系统", "停用后移除接口");
    Asset retained = assets.save(new Asset("保留服务", fixture.project().getName(), "生产", "HTTP",
        "https://retained.example.com", "UNKNOWN"));
    var plan = policyService.create(request("停用后移除计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId(), retained.getId())));
    policyService.update(plan.id(), request("停用后移除计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId(), retained.getId()), "DISABLED"));
    clearInvocations(hertzBeatClient);

    policyService.update(plan.id(), request("停用后移除计划", fixture.project().getId(), fixture.project().getName(),
        List.of(retained.getId()), "DISABLED"));

    verify(hertzBeatClient).deleteMonitor(501L);
    assertThat(assets.findById(fixture.asset().getId()).orElseThrow().getHertzbeatMonitorId()).isNull();
    assertThat(bindings.findByPlanIdOrderById(plan.id()).stream()
        .filter(binding -> binding.getAssetId().equals(fixture.asset().getId())).findFirst()).get()
        .extracting(InspectionPlanBinding::getOwnershipStatus).isEqualTo("RELEASED");
  }

  @Test
  void 停用后删除Monitor失败必须保留所有权和可重试载体() throws Exception {
    Fixture fixture = fixture("停用后删除失败系统", "停用后删除失败接口");
    var plan = policyService.create(request("停用后删除失败计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId())));
    policyService.update(plan.id(), request("停用后删除失败计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    doThrow(new IllegalStateException("删除暂不可用")).when(hertzBeatClient).deleteMonitor(501L);

    policyService.requestDelete(plan.id());

    assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId())).get()
        .extracting(InspectionPlanBinding::getOwnershipStatus).isEqualTo("PENDING_RELEASE");
    assertThat(plans.findById(plan.id()).orElseThrow().getLifecycleStatus()).isEqualTo("DELETING");
    org.mockito.Mockito.doNothing().when(hertzBeatClient).deleteMonitor(501L);
    syncService.syncPlan(plan.id());
    assertThat(plans.findById(plan.id()).orElseThrow().getLifecycleStatus()).isEqualTo("DELETED");
    assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId())).isEmpty();
  }

  @Test
  void 新计划接管后删除旧计划不得删除共享Monitor() throws Exception {
    Fixture fixture = fixture("接管删除系统", "接管删除接口");
    InspectionPlanPolicyService.InspectionPlanView oldPlan = policyService.create(request(
        "待删除旧计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));
    policyService.update(oldPlan.id(), request("待删除旧计划", fixture.project().getId(), fixture.project().getName(),
        List.of(fixture.asset().getId()), "DISABLED"));
    InspectionPlanPolicyService.InspectionPlanView newPlan = policyService.create(request(
        "当前接管计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));
    clearInvocations(hertzBeatClient);

    policyService.requestDelete(oldPlan.id());

    verify(hertzBeatClient, never()).deleteMonitor(anyLong());
    assertThat(bindings.findByPlanIdOrderById(oldPlan.id()).getFirst().getOwnershipStatus()).isEqualTo("RELEASED");
    assertThat(bindings.findByActiveOwnerKey(fixture.asset().getId()).orElseThrow().getPlanId()).isEqualTo(newPlan.id());
    assertThat(assets.findById(fixture.asset().getId()).orElseThrow().getHertzbeatMonitorId()).isEqualTo(501L);
  }

  @Test
  @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
  void 新计划接管后从旧计划移除服务不得删除共享Monitor(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    Project project = projects.save(new Project("接管移除系统", "业务系统", null, null, null, null, "RUNNING"));
    Asset retained = assets.save(new Asset("旧计划保留接口", project.getName(), "生产", "HTTP",
        "https://retained.example.com/health", "UNKNOWN"));
    Asset transferred = assets.save(new Asset("被接管接口", project.getName(), "生产", "HTTP",
        "https://transferred.example.com/health", "UNKNOWN"));
    when(hertzBeatClient.syncMonitor(any(), any(Integer.class), any(Boolean.class))).thenAnswer(invocation -> {
      Asset asset = invocation.getArgument(0);
      lastSyncedAsset.set(asset);
      lastInterval.set(invocation.getArgument(1));
      lastEnabled.set(invocation.getArgument(2));
      return new HertzBeatClient.SyncResult(asset.getId().equals(retained.getId()) ? 502L : 501L, "独立Monitor");
    });
    when(hertzBeatClient.getMonitorConfig(502L)).thenAnswer(invocation -> monitorConfig(502L));
    InspectionPlanPolicyService.InspectionPlanView oldPlan = policyService.create(request(
        "旧移除计划", project.getId(), project.getName(), List.of(retained.getId(), transferred.getId())));
    policyService.update(oldPlan.id(), request("旧移除计划", project.getId(), project.getName(),
        List.of(retained.getId(), transferred.getId()), "DISABLED"));
    InspectionPlanPolicyService.InspectionPlanView newPlan = policyService.create(request(
        "新接管计划", project.getId(), project.getName(), List.of(transferred.getId())));
    clearInvocations(hertzBeatClient);

    policyService.update(oldPlan.id(), request("旧移除计划", project.getId(), project.getName(),
        List.of(retained.getId()), "DISABLED"));

    verify(hertzBeatClient, never()).deleteMonitor(anyLong());
    assertThat(bindings.findByActiveOwnerKey(transferred.getId()).orElseThrow().getPlanId()).isEqualTo(newPlan.id());
    assertThat(bindings.findByPlanIdOrderById(oldPlan.id()).stream()
        .filter(binding -> binding.getAssetId().equals(transferred.getId()))).singleElement()
        .extracting(InspectionPlanBinding::getOwnershipStatus).isEqualTo("RELEASED");
    assertThat(tasks.findByPlanIdOrderByCreatedAt(oldPlan.id()).stream()
        .filter(task -> task.getDesiredVersion().equals(3L)))
        .allSatisfy(task -> assertThat(task.getStatus()).isEqualTo("SUCCEEDED"));
    assertThat(output.getAll()).doesNotContain("no transaction is in progress");
  }

  @Test
  void Monitor一致但规则同步失败时应保留Monitor成功状态() throws Exception {
    Fixture fixture = fixture("规则部分失败系统", "规则部分失败接口");
    InspectionPlanPolicyService.InspectionPlanView plan = policyService.create(request(
        "规则部分失败计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));
    when(hertzBeatClient.getAlertRule(601L)).thenReturn(new HertzBeatClient.AlertRule(
        601L, "错误规则", "realtime", "availability == 1", 60, 1, false,
        "hertzbeat", java.util.Map.of(), java.util.Map.of()));
    doThrow(new IllegalStateException("规则写入失败")).when(hertzBeatClient).upsertAlertRule(any());
    LocalDateTime verifiedBefore = bindings.findByPlanIdOrderById(plan.id()).getFirst().getLastVerifiedAt();

    syncService.syncPlan(plan.id());

    InspectionPlanBinding binding = bindings.findByPlanIdOrderById(plan.id()).getFirst();
    assertThat(binding.getMonitorActualState()).isEqualTo("ENABLED");
    assertThat(binding.getRuleActualState()).isEqualTo("FAILED");
    assertThat(binding.getLastVerifiedAt()).isEqualTo(verifiedBefore);
    assertThat(plans.findById(plan.id()).orElseThrow().getSyncStatus()).isEqualTo("FAILED");
  }

  @Test
  void 规则删除成功但Monitor删除失败时应保留分阶段状态() throws Exception {
    Fixture fixture = fixture("删除部分失败系统", "删除部分失败接口");
    InspectionPlanPolicyService.InspectionPlanView plan = policyService.create(request(
        "删除部分失败计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));
    doThrow(new IllegalStateException("Monitor 删除失败")).when(hertzBeatClient).deleteMonitor(501L);
    LocalDateTime verifiedBefore = bindings.findByPlanIdOrderById(plan.id()).getFirst().getLastVerifiedAt();

    policyService.requestDelete(plan.id());

    InspectionPlanBinding binding = bindings.findByPlanIdOrderById(plan.id()).getFirst();
    assertThat(binding.getRuleActualState()).isEqualTo("DELETED");
    assertThat(binding.getMonitorActualState()).isEqualTo("FAILED");
    assertThat(binding.getLastVerifiedAt()).isEqualTo(verifiedBefore);
    assertThat(binding.getOwnershipStatus()).isEqualTo("PENDING_RELEASE");
    assertThat(plans.findById(plan.id()).orElseThrow().getLifecycleStatus()).isEqualTo("DELETING");
  }

  @Test
  void 并发计划更新应串行递增期望版本() throws Exception {
    Fixture fixture = fixture("并发版本系统", "并发版本接口");
    InspectionPlanPolicyService.InspectionPlanView plan = policyService.create(request(
        "并发版本计划", fixture.project().getId(), fixture.project().getName(), List.of(fixture.asset().getId())));
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = executor.submit(() -> {
        await(start);
        policyService.update(plan.id(), request("并发版本二", fixture.project().getId(), fixture.project().getName(),
            List.of(fixture.asset().getId())));
      });
      Future<?> second = executor.submit(() -> {
        await(start);
        policyService.update(plan.id(), request("并发版本三", fixture.project().getId(), fixture.project().getName(),
            List.of(fixture.asset().getId())));
      });
      start.countDown();
      first.get();
      second.get();
    } finally {
      executor.shutdownNow();
    }

    assertThat(plans.findById(plan.id()).orElseThrow().getDesiredVersion()).isEqualTo(3L);
    assertThat(tasks.findByPlanIdOrderByCreatedAt(plan.id())).extracting(MonitoringConfigTask::getDesiredVersion)
        .contains(1L, 2L, 3L);
  }

  private Fixture fixture(String projectName, String assetName) {
    Project project = projects.save(new Project(projectName, "业务系统", null, null, null, null, "RUNNING"));
    Asset asset = assets.save(new Asset(assetName, projectName, "生产", "HTTP",
        "https://example.com/health", "UNKNOWN"));
    return new Fixture(project, asset);
  }

  private InspectionPlanRequest request(String name, Long projectId, String projectName, List<Long> assetIds) {
    return request(name, projectId, projectName, assetIds, "ENABLED");
  }

  private InspectionPlanRequest request(String name, Long projectId, String projectName, List<Long> assetIds,
      String status) {
    return new InspectionPlanRequest(name, projectId, projectName, null, null, status, null, 300, 2,
        "CRITICAL", assetIds, "MONITORING");
  }

  private HertzBeatClient.MonitorConfig monitorConfig() {
    return monitorConfig(501L);
  }

  private HertzBeatClient.MonitorConfig monitorConfig(long monitorId) {
    Asset asset = lastSyncedAsset.get();
    URI endpoint = URI.create(asset.getEndpoint());
    String instance = endpoint.getHost() + ":" + (endpoint.getPort() > 0 ? endpoint.getPort() : 443);
    return new HertzBeatClient.MonitorConfig(monitorId, asset.getName(), "website", instance,
        lastInterval.get(), "fixed", lastEnabled.get(), java.util.Map.of(
            "assetId", String.valueOf(asset.getId()), "project", asset.getProjectName(),
            "environment", asset.getEnvironmentName()), parameterClient().monitorParameters(asset));
  }

  private HertzBeatClient parameterClient() {
    return new HertzBeatClient(new com.fasterxml.jackson.databind.ObjectMapper(), "http://localhost", "", "");
  }

  private void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待并发测试启动时被中断", exception);
    }
  }

  private record Fixture(Project project, Asset asset) {}
}
