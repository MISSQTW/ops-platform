package com.lqbz.ops.service;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.InspectionPlan;
import com.lqbz.ops.domain.InspectionPlanBinding;
import com.lqbz.ops.domain.InspectionPlanBindingRepository;
import com.lqbz.ops.domain.InspectionPlanRepository;
import com.lqbz.ops.domain.MonitoringConfigTaskRepository;
import com.lqbz.ops.domain.Project;
import com.lqbz.ops.domain.ProjectRepository;
import com.lqbz.ops.web.InspectionPlanRequest;
import java.util.List;
import java.util.NoSuchElementException;
import java.time.LocalDateTime;
import org.springframework.data.domain.Sort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InspectionPlanPolicyService {
  private final ProjectRepository projects;
  private final AssetRepository assets;
  private final InspectionPlanRepository plans;
  private final InspectionPlanBindingRepository bindings;
  private final MonitoringConfigTaskRepository tasks;
  private final ApplicationEventPublisher events;
  private final MonitoringMigrationTopologyLock topologyLock;

  public InspectionPlanPolicyService(ProjectRepository projects, AssetRepository assets,
      InspectionPlanRepository plans, InspectionPlanBindingRepository bindings,
      MonitoringConfigTaskRepository tasks, ApplicationEventPublisher events, MonitoringMigrationTopologyLock topologyLock) {
    this.projects = projects;
    this.assets = assets;
    this.plans = plans;
    this.bindings = bindings;
    this.tasks = tasks;
    this.events = events;
    this.topologyLock = topologyLock;
  }

  @Transactional(readOnly = true)
  public List<InspectionPlanView> list() {
    return plans.findAll(Sort.by("id")).stream().map(this::view).toList();
  }

  @Transactional(readOnly = true)
  public InspectionPlanView get(Long id) {
    return view(plans.findById(id).orElseThrow(() -> new NoSuchElementException("巡检计划不存在")));
  }

  @Transactional
  public InspectionPlanView create(InspectionPlanRequest request) {
    Project project = projects.findById(request.projectId())
        .orElseThrow(() -> new NoSuchElementException("项目不存在"));
    if (!project.getName().equals(request.projectName())) throw new IllegalArgumentException("项目标识与项目名称不一致");
    List<Long> assetIds = request.assetIds() == null ? List.of() : request.assetIds();
    if (assetIds.isEmpty()) throw new IllegalArgumentException("巡检计划至少选择一个服务");
    topologyLock.acquireServices(assetIds);
    List<Asset> selected = assets.findAllById(assetIds);
    if (selected.size() != assetIds.size() || selected.stream().anyMatch(asset -> !project.getName().equals(asset.getProjectName()))) {
      throw new IllegalArgumentException("巡检计划只能选择所属项目下的服务");
    }
    if (request.intervalSeconds() == null || request.failureThreshold() == null || request.alertLevel() == null) {
      throw new IllegalArgumentException("巡检频率、连续失败次数和告警级别不能为空");
    }
    selected.forEach(this::requireOrdinaryAdmission);
    InspectionPlan plan = InspectionPlan.monitoring(request.name(), project.getId(), project.getName(),
        request.intervalSeconds(), request.failureThreshold(), request.alertLevel());
    plan.configureStatus(requireStatus(valueOr(request.status(), "ENABLED")));
    if ("ENABLED".equals(plan.getStatus())) selected.forEach(asset ->
        com.lqbz.ops.domain.MonitoringServiceContract.validate(asset.getType(), asset.getEndpoint()));
    plans.save(plan);
    for (Long assetId : assetIds) {
      InspectionPlanBinding binding = InspectionPlanBinding.managed(plan.getId(), assetId, plan.getDesiredVersion());
      binding.alignWithPlanStatus(plan.getStatus(), plan.getDesiredVersion());
      bindings.save(binding);
    }
    enqueueAndPublish(plan);
    return view(plan);
  }

  @Transactional
  public InspectionPlanView update(Long id, InspectionPlanRequest request) {
    InspectionPlan plan = plans.findByIdForUpdate(id).orElseThrow(() -> new NoSuchElementException("巡检计划不存在"));
    if (!plan.isMonitoringPolicy()) throw new IllegalArgumentException("旧巡检计划不能使用监控策略接口修改");
    if ("PENDING_CONFIRMATION".equals(plan.getStatus())) throw new IllegalStateException("待确认迁移计划请通过迁移确认接口操作");
    if (!"ACTIVE".equals(plan.getLifecycleStatus())) throw new IllegalStateException("删除中的巡检计划不能修改");
    Project project = projects.findById(request.projectId()).orElseThrow(() -> new NoSuchElementException("项目不存在"));
    if (!project.getId().equals(plan.getProjectId()) || !project.getName().equals(request.projectName())) {
      throw new IllegalArgumentException("结构化巡检计划不能变更所属项目");
    }
    topologyLock.acquireServices(request.assetIds() == null ? List.of() : request.assetIds());
    List<Long> assetIds = requireAssets(project, request.assetIds());
    String requestedStatus = requireStatus(valueOr(request.status(), plan.getStatus()));
    if ("ENABLED".equals(requestedStatus)) assets.findAllById(assetIds).forEach(asset ->
        com.lqbz.ops.domain.MonitoringServiceContract.validate(asset.getType(), asset.getEndpoint()));
    List<InspectionPlanBinding> existing = bindings.findByPlanIdOrderById(plan.getId());
    for (Long assetId : assetIds) {
      boolean restoring = existing.stream().anyMatch(binding -> assetId.equals(binding.getAssetId())
          && "PENDING_RELEASE".equals(binding.getOwnershipStatus()) && assetId.equals(binding.getActiveOwnerKey()));
      boolean retained = existing.stream().anyMatch(binding -> assetId.equals(binding.getAssetId())
          && ("OWNED".equals(binding.getOwnershipStatus()) || "DISABLED_RELEASED".equals(binding.getOwnershipStatus())));
      boolean reacquiring = "ENABLED".equals(requestedStatus) && existing.stream().anyMatch(binding ->
          assetId.equals(binding.getAssetId()) && "DISABLED_RELEASED".equals(binding.getOwnershipStatus()));
      Asset asset = assets.findById(assetId).orElseThrow();
      if (restoring) requireUniqueMonitor(asset);
      else if (!retained || reacquiring) requireOrdinaryAdmission(asset);
    }
    if ("ENABLED".equals(requestedStatus) && "DISABLED".equals(plan.getStatus())) {
      List<Long> conflicts = existing.stream()
          .filter(binding -> assetIds.contains(binding.getAssetId()))
          .filter(binding -> "DISABLED_RELEASED".equals(binding.getOwnershipStatus()))
          .map(InspectionPlanBinding::getAssetId)
          .filter(assetId -> bindings.findByActiveOwnerKey(assetId)
              .filter(owner -> !owner.getPlanId().equals(plan.getId())).isPresent())
          .toList();
      if (!conflicts.isEmpty()) throw new IllegalStateException("服务已被其他启用计划接管：" + conflicts);
    }
    plan.reconfigure(request.name(), require(request.intervalSeconds(), "巡检频率不能为空"),
        require(request.failureThreshold(), "连续失败次数不能为空"), require(request.alertLevel(), "告警级别不能为空"),
        requestedStatus);
    for (InspectionPlanBinding binding : existing) {
      if (!assetIds.contains(binding.getAssetId()) && !"RELEASED".equals(binding.getOwnershipStatus())) {
        binding.requestRelease(plan.getDesiredVersion());
      } else if (assetIds.contains(binding.getAssetId())) {
        if ("ENABLED".equals(plan.getStatus()) && "DISABLED_RELEASED".equals(binding.getOwnershipStatus())) {
          binding.reacquireOwnership(plan.getDesiredVersion());
        } else {
          binding.alignWithPlanStatus(plan.getStatus(), plan.getDesiredVersion());
        }
      }
    }
    List<Long> existingAssetIds = existing.stream()
        .filter(binding -> "OWNED".equals(binding.getOwnershipStatus())
            || "DISABLED_RELEASED".equals(binding.getOwnershipStatus()))
        .map(InspectionPlanBinding::getAssetId)
        .toList();
    for (Long assetId : assetIds) {
      if (existingAssetIds.contains(assetId)) continue;
      InspectionPlanBinding pendingRelease = existing.stream()
          .filter(binding -> assetId.equals(binding.getAssetId()))
          .filter(binding -> "PENDING_RELEASE".equals(binding.getOwnershipStatus()))
          .findFirst().orElse(null);
      if (pendingRelease != null) {
        pendingRelease.restoreOwnership(plan.getDesiredVersion());
        pendingRelease.alignWithPlanStatus(plan.getStatus(), plan.getDesiredVersion());
      } else {
        InspectionPlanBinding binding = InspectionPlanBinding.managed(plan.getId(), assetId, plan.getDesiredVersion());
        binding.alignWithPlanStatus(plan.getStatus(), plan.getDesiredVersion());
        bindings.save(binding);
      }
    }
    enqueueAndPublish(plan);
    return view(plan);
  }

  @Transactional
  public InspectionPlanView requestDelete(Long id) {
    InspectionPlan plan = plans.findByIdForUpdate(id).orElseThrow(() -> new NoSuchElementException("巡检计划不存在"));
    if ("PENDING_CONFIRMATION".equals(plan.getStatus())) throw new IllegalStateException("待确认迁移计划不能直接删除外部资源");
    if (!plan.isMonitoringPolicy()) throw new IllegalArgumentException("旧巡检计划不支持逻辑删除");
    if ("DELETED".equals(plan.getLifecycleStatus())) return view(plan);
    plan.markDeleting();
    bindings.findByPlanIdOrderById(plan.getId()).stream()
        .filter(binding -> !"RELEASED".equals(binding.getOwnershipStatus()))
        .forEach(binding -> binding.requestRelease(plan.getDesiredVersion()));
    enqueueAndPublish(plan);
    return view(plan);
  }

  @Transactional
  public InspectionPlanView prepareMigration(Long projectId, Long assetId, String name, int intervalSeconds) {
    topologyLock.acquireServices(List.of(assetId));
    Project project = projects.findById(projectId).orElseThrow(() -> new NoSuchElementException("项目不存在"));
    Asset asset = assets.findById(assetId).orElseThrow(() -> new NoSuchElementException("服务不存在"));
    if (!project.getName().equals(asset.getProjectName())) throw new IllegalArgumentException("服务与项目不匹配");
    com.lqbz.ops.domain.MonitoringServiceContract.validate(asset.getType(), asset.getEndpoint());
    if (intervalSeconds < 10 || intervalSeconds > 86400) throw new IllegalArgumentException("迁移频率必须在10秒至24小时之间");
    if (bindings.findByActiveOwnerKey(assetId).isPresent()) throw new IllegalStateException("服务已有巡检计划或待确认迁移");
    InspectionPlan plan = InspectionPlan.monitoring(name, projectId, project.getName(), intervalSeconds, 2, "CRITICAL");
    plan.configureStatus("PENDING_CONFIRMATION");
    plans.save(plan);
    bindings.saveAndFlush(InspectionPlanBinding.pendingMigration(plan.getId(), assetId, asset.getHertzbeatMonitorId()));
    return view(plan);
  }

  @Transactional
  public InspectionPlanView confirmMigration(Long planId, Long assetId) {
    InspectionPlan plan = plans.findByIdForUpdate(planId).orElseThrow(() -> new NoSuchElementException("迁移计划不存在"));
    if (!"PENDING_CONFIRMATION".equals(plan.getStatus()) || !"ACTIVE".equals(plan.getLifecycleStatus())) {
      throw new IllegalStateException("迁移计划不是待确认状态");
    }
    InspectionPlanBinding binding = bindings.findCurrentForUpdate(planId, assetId)
        .orElseThrow(() -> new NoSuchElementException("待确认迁移绑定不存在"));
    binding.confirmMigration();
    plan.configureStatus("ENABLED");
    enqueueAndPublish(plan);
    return view(plan);
  }

  @Transactional
  public void cancelMigration(Long planId, Long assetId) {
    InspectionPlan plan = plans.findByIdForUpdate(planId).orElseThrow(() -> new NoSuchElementException("迁移计划不存在"));
    if (!"PENDING_CONFIRMATION".equals(plan.getStatus())) throw new IllegalStateException("只能取消待确认迁移计划");
    InspectionPlanBinding binding = bindings.findCurrentForUpdate(planId, assetId)
        .orElseThrow(() -> new NoSuchElementException("待确认迁移绑定不存在"));
    if (!"MIGRATION_PENDING".equals(binding.getOwnershipStatus())) throw new IllegalStateException("迁移绑定已不再待确认");
    binding.releaseOwnership();
    plan.configureStatus("DISABLED");
    plan.markDeleted(LocalDateTime.now());
  }

  @Transactional
  public MigrationBatchResult claimExisting(List<MigrationClaim> claims) {
    throw new IllegalArgumentException("迁移认领入口已停用，请使用迁移提案确认");
  }

  @Transactional
  public Asset updateAsset(Long assetId, String name, String projectName, String environmentName,
      String type, String endpoint, String status) {
    InspectionPlanBinding owner = bindings.findByActiveOwnerKey(assetId).orElse(null);
    InspectionPlan lockedPlan = owner == null ? null : plans.findByIdForUpdate(owner.getPlanId()).orElse(null);
    List<InspectionPlanBinding> affectedBindings = lockedPlan == null ? List.of()
        : bindings.findCurrentForUpdate(lockedPlan.getId(), assetId).stream().toList();
    Asset asset = assets.findById(assetId).orElseThrow(() -> new NoSuchElementException("服务不存在"));
    if (!asset.getProjectName().equals(projectName)
        && bindings.existsByAssetIdAndOwnershipStatusNot(assetId, "RELEASED")) {
      throw new IllegalStateException("服务仍关联巡检计划，请先移除并完成清理后再变更项目");
    }
    asset.update(name, projectName, valueOr(environmentName, asset.getEnvironmentName()), type, endpoint,
        valueOr(status, asset.getStatus()));
    if (lockedPlan != null && "ENABLED".equals(lockedPlan.getStatus())) {
      com.lqbz.ops.domain.MonitoringServiceContract.validate(asset.getType(), asset.getEndpoint());
    }
    for (InspectionPlanBinding binding : affectedBindings) {
      if ("DISABLED_RELEASED".equals(binding.getOwnershipStatus())) continue;
      InspectionPlan plan = lockedPlan;
      if (plan == null || !plan.isMonitoringPolicy() || !"ACTIVE".equals(plan.getLifecycleStatus())) continue;
      plan.touchDesiredVersion();
      bindings.findByPlanIdOrderById(plan.getId()).forEach(current ->
          current.advanceDesiredVersion(plan.getDesiredVersion()));
      enqueueAndPublish(plan);
    }
    return asset;
  }

  private List<Long> requireAssets(Project project, List<Long> requestedIds) {
    List<Long> assetIds = requestedIds == null ? List.of() : requestedIds;
    if (assetIds.isEmpty()) throw new IllegalArgumentException("巡检计划至少选择一个服务");
    List<Asset> selected = assets.findAllById(assetIds);
    if (selected.size() != assetIds.size() || selected.stream().anyMatch(asset -> !project.getName().equals(asset.getProjectName()))) {
      throw new IllegalArgumentException("巡检计划只能选择所属项目下的服务");
    }
    return assetIds;
  }

  private void requireOrdinaryAdmission(Asset asset) {
    if (bindings.findByActiveOwnerKey(asset.getId()).isPresent()) {
      throw new IllegalStateException("服务已被其他启用计划接管，请先完成原计划迁移或清理");
    }
    List<InspectionPlanBinding> history = bindings.findByAssetIdOrderById(asset.getId());
    boolean approvedDisabled = history.stream().anyMatch(binding ->
        ("DISABLED_RELEASED".equals(binding.getOwnershipStatus())
            || ("PENDING_RELEASE".equals(binding.getOwnershipStatus()) && binding.getActiveOwnerKey() == null
                && "DISABLED".equals(binding.getMonitorActualState())))
            && asset.getHertzbeatMonitorId() != null
            && asset.getHertzbeatMonitorId().equals(binding.getHertzbeatMonitorId()));
    requireUniqueMonitor(asset);
    boolean legacy = plans.findAll().stream().anyMatch(plan -> !plan.isMonitoringPolicy()
        && "ENABLED".equals(plan.getStatus()) && asset.getProjectName().equals(plan.getProjectName()));
    if (!approvedDisabled && (asset.getHertzbeatMonitorId() != null || legacy)) {
      throw new IllegalStateException("服务存在历史监控或旧计划，请通过迁移提案确认");
    }
  }

  private void requireUniqueMonitor(Asset asset) {
    if (asset.getHertzbeatMonitorId() != null && assets.countByHertzbeatMonitorId(asset.getHertzbeatMonitorId()) != 1) {
      throw new IllegalStateException("Monitor映射重复，请先处理迁移冲突");
    }
  }

  private int require(Integer value, String message) {
    if (value == null) throw new IllegalArgumentException(message);
    return value;
  }

  private String require(String value, String message) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
    return value;
  }

  private String requireStatus(String status) {
    if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
      throw new IllegalArgumentException("巡检计划状态只允许 ENABLED 或 DISABLED");
    }
    return status;
  }

  private InspectionPlanView view(InspectionPlan plan) {
    List<Long> assetIds = bindings.findByPlanIdOrderById(plan.getId()).stream()
        .filter(binding -> "OWNED".equals(binding.getOwnershipStatus())
            || "DISABLED_RELEASED".equals(binding.getOwnershipStatus())
            || "MIGRATION_PENDING".equals(binding.getOwnershipStatus()))
        .map(InspectionPlanBinding::getAssetId).toList();
    return new InspectionPlanView(plan.getId(), plan.getName(), plan.getProjectId(), plan.getProjectName(),
        plan.getScopeText(), plan.getScheduleText(), plan.getStatus(), plan.getNextRunAt(), plan.getIntervalSeconds(),
        plan.getFailureThreshold(), plan.getAlertLevel(), plan.getLifecycleStatus(), plan.getSyncStatus(),
        plan.getDesiredVersion(), plan.getLastSyncAttemptAt(), plan.getLastSyncSucceededAt(), plan.getLastVerifiedAt(),
        plan.getSyncError(), assetIds);
  }

  private String valueOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private void enqueueAndPublish(InspectionPlan plan) {
    if ("PENDING_CONFIRMATION".equals(plan.getStatus())) return;
    LocalDateTime now = LocalDateTime.now();
    for (InspectionPlanBinding binding : bindings.findByPlanIdOrderById(plan.getId())) {
      if ("RELEASED".equals(binding.getOwnershipStatus())
          || "DISABLED_RELEASED".equals(binding.getOwnershipStatus())) continue;
      String operation = "DELETED".equals(binding.getRuleDesiredState()) ? "DELETE" : "UPSERT";
      if (tasks.findByPlanIdAndAssetIdAndDesiredVersionAndOperation(
          plan.getId(), binding.getAssetId(), plan.getDesiredVersion(), operation).isEmpty()) {
        tasks.saveAndFlush(new com.lqbz.ops.domain.MonitoringConfigTask(
            plan.getId(), binding.getAssetId(), plan.getDesiredVersion(), operation, now));
      }
    }
    events.publishEvent(new InspectionPlanSyncRequested(plan.getId()));
  }

  public record InspectionPlanView(Long id, String name, Long projectId, String projectName, String scopeText,
      String scheduleText, String status, java.time.LocalDateTime nextRunAt, Integer intervalSeconds,
      Integer failureThreshold, String alertLevel, String lifecycleStatus, String syncStatus, Long desiredVersion,
      java.time.LocalDateTime lastSyncAttemptAt, java.time.LocalDateTime lastSyncSucceededAt,
      java.time.LocalDateTime lastVerifiedAt, String syncError, List<Long> assetIds) {}
  public record MigrationClaim(Long planId, Long assetId) {}
  public record MigrationBatchResult(int claimed, LocalDateTime claimedAt) {}
}
