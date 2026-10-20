package com.lqbz.ops.service;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.InspectionPlan;
import com.lqbz.ops.domain.InspectionPlanBinding;
import com.lqbz.ops.domain.InspectionPlanBindingRepository;
import com.lqbz.ops.domain.InspectionPlanRepository;
import com.lqbz.ops.domain.Project;
import com.lqbz.ops.domain.ProjectRepository;
import com.lqbz.ops.web.InspectionPlanRequest;
import java.util.List;
import java.util.NoSuchElementException;
import java.time.LocalDateTime;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InspectionPlanPolicyService {
  private final ProjectRepository projects;
  private final AssetRepository assets;
  private final InspectionPlanRepository plans;
  private final InspectionPlanBindingRepository bindings;

  public InspectionPlanPolicyService(ProjectRepository projects, AssetRepository assets,
      InspectionPlanRepository plans, InspectionPlanBindingRepository bindings) {
    this.projects = projects;
    this.assets = assets;
    this.plans = plans;
    this.bindings = bindings;
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
    List<Asset> selected = assets.findAllById(assetIds);
    if (selected.size() != assetIds.size() || selected.stream().anyMatch(asset -> !project.getName().equals(asset.getProjectName()))) {
      throw new IllegalArgumentException("巡检计划只能选择所属项目下的服务");
    }
    if (request.intervalSeconds() == null || request.failureThreshold() == null || request.alertLevel() == null) {
      throw new IllegalArgumentException("巡检频率、连续失败次数和告警级别不能为空");
    }
    InspectionPlan plan = InspectionPlan.monitoring(request.name(), project.getId(), project.getName(),
        request.intervalSeconds(), request.failureThreshold(), request.alertLevel());
    plan.configureStatus(requireStatus(valueOr(request.status(), "ENABLED")));
    plans.save(plan);
    for (Long assetId : assetIds) {
      InspectionPlanBinding binding = InspectionPlanBinding.managed(plan.getId(), assetId, plan.getDesiredVersion());
      binding.alignWithPlanStatus(plan.getStatus(), plan.getDesiredVersion());
      bindings.save(binding);
    }
    return view(plan);
  }

  @Transactional
  public InspectionPlanView update(Long id, InspectionPlanRequest request) {
    InspectionPlan plan = plans.findById(id).orElseThrow(() -> new NoSuchElementException("巡检计划不存在"));
    if (!plan.isMonitoringPolicy()) throw new IllegalArgumentException("旧巡检计划不能使用监控策略接口修改");
    if (!"ACTIVE".equals(plan.getLifecycleStatus())) throw new IllegalStateException("删除中的巡检计划不能修改");
    Project project = projects.findById(request.projectId()).orElseThrow(() -> new NoSuchElementException("项目不存在"));
    if (!project.getId().equals(plan.getProjectId()) || !project.getName().equals(request.projectName())) {
      throw new IllegalArgumentException("结构化巡检计划不能变更所属项目");
    }
    List<Long> assetIds = requireAssets(project, request.assetIds());
    plan.reconfigure(request.name(), require(request.intervalSeconds(), "巡检频率不能为空"),
        require(request.failureThreshold(), "连续失败次数不能为空"), require(request.alertLevel(), "告警级别不能为空"),
        requireStatus(valueOr(request.status(), plan.getStatus())));
    List<InspectionPlanBinding> existing = bindings.findByPlanIdOrderById(plan.getId());
    for (InspectionPlanBinding binding : existing) {
      if (!assetIds.contains(binding.getAssetId()) && !"RELEASED".equals(binding.getOwnershipStatus())) {
        binding.requestRelease(plan.getDesiredVersion());
      } else if (assetIds.contains(binding.getAssetId())) {
        binding.alignWithPlanStatus(plan.getStatus(), plan.getDesiredVersion());
      }
    }
    List<Long> existingAssetIds = existing.stream()
        .filter(binding -> "OWNED".equals(binding.getOwnershipStatus()))
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
    return view(plan);
  }

  @Transactional
  public InspectionPlanView requestDelete(Long id) {
    InspectionPlan plan = plans.findById(id).orElseThrow(() -> new NoSuchElementException("巡检计划不存在"));
    if (!plan.isMonitoringPolicy()) throw new IllegalArgumentException("旧巡检计划不支持逻辑删除");
    plan.markDeleting();
    bindings.findByPlanIdOrderById(plan.getId()).stream()
        .filter(binding -> !"RELEASED".equals(binding.getOwnershipStatus()))
        .forEach(binding -> binding.requestRelease(plan.getDesiredVersion()));
    return view(plan);
  }

  @Transactional
  public MigrationBatchResult claimExisting(List<MigrationClaim> claims) {
    int claimed = 0;
    for (MigrationClaim claim : claims) {
      InspectionPlan plan = plans.findById(claim.planId()).orElseThrow(() -> new NoSuchElementException("巡检计划不存在"));
      Asset asset = assets.findById(claim.assetId()).orElseThrow(() -> new NoSuchElementException("服务不存在"));
      if (!plan.isMonitoringPolicy() || !asset.getProjectName().equals(plan.getProjectName())) {
        throw new IllegalArgumentException("迁移批次中的计划与服务不匹配");
      }
      if (!"ACTIVE".equals(plan.getLifecycleStatus()) || !"ENABLED".equals(plan.getStatus())) {
        throw new IllegalArgumentException("只能认领到启用中的巡检计划");
      }
      if (asset.getHertzbeatMonitorId() == null) throw new IllegalArgumentException("服务没有可认领的 HertzBeat Monitor");
      if (assets.countByHertzbeatMonitorId(asset.getHertzbeatMonitorId()) != 1) {
        throw new IllegalArgumentException("Monitor 不能唯一证明服务归属，禁止认领");
      }
      if (bindings.findByActiveOwnerKey(asset.getId()).isPresent()) {
        throw new IllegalArgumentException("服务已被其他巡检计划认领");
      }
      bindings.save(InspectionPlanBinding.claimExisting(plan.getId(), asset.getId(), plan.getDesiredVersion(),
          asset.getHertzbeatMonitorId()));
      claimed++;
    }
    return new MigrationBatchResult(claimed, LocalDateTime.now());
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
        .filter(binding -> "OWNED".equals(binding.getOwnershipStatus()))
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

  public record InspectionPlanView(Long id, String name, Long projectId, String projectName, String scopeText,
      String scheduleText, String status, java.time.LocalDateTime nextRunAt, Integer intervalSeconds,
      Integer failureThreshold, String alertLevel, String lifecycleStatus, String syncStatus, Long desiredVersion,
      java.time.LocalDateTime lastSyncAttemptAt, java.time.LocalDateTime lastSyncSucceededAt,
      java.time.LocalDateTime lastVerifiedAt, String syncError, List<Long> assetIds) {}
  public record MigrationClaim(Long planId, Long assetId) {}
  public record MigrationBatchResult(int claimed, LocalDateTime claimedAt) {}
}
