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

    private final MonitoringTopologyLock topologyLock;

    public InspectionPlanPolicyService(ProjectRepository projects, AssetRepository assets, InspectionPlanRepository plans, InspectionPlanBindingRepository bindings, MonitoringConfigTaskRepository tasks, ApplicationEventPublisher events, MonitoringTopologyLock topologyLock) {
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
        return plans.findAll(Sort.by("id")).stream().map(this::view).collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)
    public InspectionPlanView get(Long id) {
        return view(plans.findById(id).orElseThrow(() -> new NoSuchElementException("巡检计划不存在")));
    }

    @Transactional
    public InspectionPlanView create(InspectionPlanRequest request) {
        Project project = projects.findById(request.projectId()).orElseThrow(() -> new NoSuchElementException("项目不存在"));
        if (!project.getName().equals(request.projectName()))
            throw new IllegalArgumentException("项目标识与项目名称不一致");
        List<Long> assetIds = request.assetIds() == null ? com.lqbz.ops.compat.Java8.list() : request.assetIds();
        if (assetIds.isEmpty())
            throw new IllegalArgumentException("巡检计划至少选择一个服务");
        topologyLock.acquireServices(assetIds);
        List<Asset> selected = assets.findAllById(assetIds);
        if (selected.size() != assetIds.size() || selected.stream().anyMatch(asset -> !project.getName().equals(asset.getProjectName()))) {
            throw new IllegalArgumentException("巡检计划只能选择所属项目下的服务");
        }
        if (request.intervalSeconds() == null || request.failureThreshold() == null || request.alertLevel() == null) {
            throw new IllegalArgumentException("巡检频率、连续失败次数和告警级别不能为空");
        }
        selected.forEach(this::requireOrdinaryAdmission);
        InspectionPlan plan = InspectionPlan.monitoring(request.name(), project.getId(), project.getName(), request.intervalSeconds(), request.failureThreshold(), request.alertLevel());
        plan.configureStatus(requireStatus(valueOr(request.status(), "ENABLED")));
        if ("ENABLED".equals(plan.getStatus()))
            selected.forEach(asset -> com.lqbz.ops.domain.MonitoringServiceContract.validate(asset.getType(), asset.getEndpoint()));
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
        if (!"ACTIVE".equals(plan.getLifecycleStatus()))
            throw new IllegalStateException("删除中的巡检计划不能修改");
        Project project = projects.findById(request.projectId()).orElseThrow(() -> new NoSuchElementException("项目不存在"));
        if (!project.getId().equals(plan.getProjectId()) || !project.getName().equals(request.projectName())) {
            throw new IllegalArgumentException("结构化巡检计划不能变更所属项目");
        }
        topologyLock.acquireServices(request.assetIds() == null ? com.lqbz.ops.compat.Java8.list() : request.assetIds());
        List<Long> assetIds = requireAssets(project, request.assetIds());
        String requestedStatus = requireStatus(valueOr(request.status(), plan.getStatus()));
        if ("ENABLED".equals(requestedStatus))
            assets.findAllById(assetIds).forEach(asset -> com.lqbz.ops.domain.MonitoringServiceContract.validate(asset.getType(), asset.getEndpoint()));
        List<InspectionPlanBinding> existing = bindings.findByPlanIdOrderById(plan.getId());
        for (Long assetId : assetIds) {
            boolean restoring = existing.stream().anyMatch(binding -> assetId.equals(binding.getAssetId()) && "PENDING_RELEASE".equals(binding.getOwnershipStatus()) && assetId.equals(binding.getActiveOwnerKey()));
            boolean retained = existing.stream().anyMatch(binding -> assetId.equals(binding.getAssetId()) && ("OWNED".equals(binding.getOwnershipStatus()) || "DISABLED_RELEASED".equals(binding.getOwnershipStatus())));
            boolean reacquiring = "ENABLED".equals(requestedStatus) && existing.stream().anyMatch(binding -> assetId.equals(binding.getAssetId()) && "DISABLED_RELEASED".equals(binding.getOwnershipStatus()));
            Asset asset = assets.findById(assetId).orElseThrow(java.util.NoSuchElementException::new);
            if (restoring)
                requireUniqueMonitor(asset);
            else if (!retained || reacquiring)
                requireOrdinaryAdmission(asset);
        }
        if ("ENABLED".equals(requestedStatus) && "DISABLED".equals(plan.getStatus())) {
            List<Long> conflicts = existing.stream().filter(binding -> assetIds.contains(binding.getAssetId())).filter(binding -> "DISABLED_RELEASED".equals(binding.getOwnershipStatus())).map(InspectionPlanBinding::getAssetId).filter(assetId -> bindings.findByActiveOwnerKey(assetId).filter(owner -> !owner.getPlanId().equals(plan.getId())).isPresent()).collect(java.util.stream.Collectors.toList());
            if (!conflicts.isEmpty())
                throw new IllegalStateException("服务已被其他启用计划接管：" + conflicts);
        }
        plan.reconfigure(request.name(), require(request.intervalSeconds(), "巡检频率不能为空"), require(request.failureThreshold(), "连续失败次数不能为空"), require(request.alertLevel(), "告警级别不能为空"), requestedStatus);
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
        List<Long> existingAssetIds = existing.stream().filter(binding -> "OWNED".equals(binding.getOwnershipStatus()) || "DISABLED_RELEASED".equals(binding.getOwnershipStatus())).map(InspectionPlanBinding::getAssetId).collect(java.util.stream.Collectors.toList());
        for (Long assetId : assetIds) {
            if (existingAssetIds.contains(assetId))
                continue;
            InspectionPlanBinding pendingRelease = existing.stream().filter(binding -> assetId.equals(binding.getAssetId())).filter(binding -> "PENDING_RELEASE".equals(binding.getOwnershipStatus())).findFirst().orElse(null);
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
        if ("DELETED".equals(plan.getLifecycleStatus()))
            return view(plan);
        plan.markDeleting();
        bindings.findByPlanIdOrderById(plan.getId()).stream().filter(binding -> !"RELEASED".equals(binding.getOwnershipStatus())).forEach(binding -> binding.requestRelease(plan.getDesiredVersion()));
        enqueueAndPublish(plan);
        return view(plan);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lqbz.ops.inspection.InspectionProfileRepository inspectionProfiles;

    @Transactional
    public Asset updateAsset(Long assetId, String name, String projectName, String environmentName, String type, String endpoint, String status) {
        InspectionPlanBinding owner = bindings.findByActiveOwnerKey(assetId).orElse(null);
        InspectionPlan lockedPlan = owner == null ? null : plans.findByIdForUpdate(owner.getPlanId()).orElse(null);
        List<InspectionPlanBinding> affectedBindings = lockedPlan == null ? com.lqbz.ops.compat.Java8.list() : bindings.findCurrentForUpdate(lockedPlan.getId(), assetId).map(java.util.Collections::singletonList).orElseGet(java.util.Collections::emptyList);
        topologyLock.acquireServices(com.lqbz.ops.compat.Java8.list(assetId));
        Asset asset = assets.findById(assetId).orElseThrow(() -> new NoSuchElementException("服务不存在"));
        if (inspectionProfiles != null && !asset.getType().equals(type) && inspectionProfiles.findById(assetId).map(com.lqbz.ops.inspection.InspectionProfile::isEnabled).orElse(false)) {
            throw new IllegalStateException("请先停用深度巡检配置再修改服务类型");
        }
        if (!asset.getProjectName().equals(projectName) && bindings.existsByAssetIdAndOwnershipStatusNot(assetId, "RELEASED")) {
            throw new IllegalStateException("服务仍关联巡检计划，请先移除并完成清理后再变更项目");
        }
        asset.update(name, projectName, valueOr(environmentName, asset.getEnvironmentName()), type, endpoint, valueOr(status, asset.getStatus()));
        if (lockedPlan != null && "ENABLED".equals(lockedPlan.getStatus())) {
            com.lqbz.ops.domain.MonitoringServiceContract.validate(asset.getType(), asset.getEndpoint());
        }
        for (InspectionPlanBinding binding : affectedBindings) {
            if ("DISABLED_RELEASED".equals(binding.getOwnershipStatus()))
                continue;
            InspectionPlan plan = lockedPlan;
            if (plan == null || !"ACTIVE".equals(plan.getLifecycleStatus()))
                continue;
            plan.touchDesiredVersion();
            bindings.findByPlanIdOrderById(plan.getId()).forEach(current -> current.advanceDesiredVersion(plan.getDesiredVersion()));
            enqueueAndPublish(plan);
        }
        return asset;
    }

    private List<Long> requireAssets(Project project, List<Long> requestedIds) {
        List<Long> assetIds = requestedIds == null ? com.lqbz.ops.compat.Java8.list() : requestedIds;
        if (assetIds.isEmpty())
            throw new IllegalArgumentException("巡检计划至少选择一个服务");
        List<Asset> selected = assets.findAllById(assetIds);
        if (selected.size() != assetIds.size() || selected.stream().anyMatch(asset -> !project.getName().equals(asset.getProjectName()))) {
            throw new IllegalArgumentException("巡检计划只能选择所属项目下的服务");
        }
        return assetIds;
    }

    private void requireOrdinaryAdmission(Asset asset) {
        if (bindings.findByActiveOwnerKey(asset.getId()).isPresent()) {
            throw new IllegalStateException("服务已加入其他启用计划，请先从原计划移除");
        }
        requireUniqueMonitor(asset);
    }

    private void requireUniqueMonitor(Asset asset) {
        if (asset.getHertzbeatMonitorId() != null && assets.countByHertzbeatMonitorId(asset.getHertzbeatMonitorId()) != 1) {
            throw new IllegalStateException("Monitor映射重复，请先修正服务监控配置");
        }
    }

    private int require(Integer value, String message) {
        if (value == null)
            throw new IllegalArgumentException(message);
        return value;
    }

    private String require(String value, String message) {
        if (value == null || com.lqbz.ops.compat.Java8.isBlank(value))
            throw new IllegalArgumentException(message);
        return value;
    }

    private String requireStatus(String status) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new IllegalArgumentException("巡检计划状态只允许 ENABLED 或 DISABLED");
        }
        return status;
    }

    private InspectionPlanView view(InspectionPlan plan) {
        List<Long> assetIds = bindings.findByPlanIdOrderById(plan.getId()).stream().filter(binding -> "OWNED".equals(binding.getOwnershipStatus()) || "DISABLED_RELEASED".equals(binding.getOwnershipStatus())).map(InspectionPlanBinding::getAssetId).collect(java.util.stream.Collectors.toList());
        return new InspectionPlanView(plan.getId(), plan.getName(), plan.getProjectId(), plan.getProjectName(), plan.getScopeText(), plan.getScheduleText(), plan.getStatus(), plan.getNextRunAt(), plan.getIntervalSeconds(), plan.getFailureThreshold(), plan.getAlertLevel(), plan.getLifecycleStatus(), plan.getSyncStatus(), plan.getDesiredVersion(), plan.getLastSyncAttemptAt(), plan.getLastSyncSucceededAt(), plan.getLastVerifiedAt(), plan.getSyncError(), assetIds);
    }

    private String valueOr(String value, String fallback) {
        return value == null || com.lqbz.ops.compat.Java8.isBlank(value) ? fallback : value;
    }

    private void enqueueAndPublish(InspectionPlan plan) {
        LocalDateTime now = LocalDateTime.now();
        for (InspectionPlanBinding binding : bindings.findByPlanIdOrderById(plan.getId())) {
            if ("RELEASED".equals(binding.getOwnershipStatus()) || "DISABLED_RELEASED".equals(binding.getOwnershipStatus()))
                continue;
            String operation = "DELETED".equals(binding.getRuleDesiredState()) ? "DELETE" : "UPSERT";
            if (tasks.findByPlanIdAndAssetIdAndDesiredVersionAndOperation(plan.getId(), binding.getAssetId(), plan.getDesiredVersion(), operation).isPresent() == false) {
                tasks.saveAndFlush(new com.lqbz.ops.domain.MonitoringConfigTask(plan.getId(), binding.getAssetId(), plan.getDesiredVersion(), operation, now));
            }
        }
        events.publishEvent(new InspectionPlanSyncRequested(plan.getId()));
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class InspectionPlanView {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public InspectionPlanView(@com.fasterxml.jackson.annotation.JsonProperty("id") Long id, @com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("projectId") Long projectId, @com.fasterxml.jackson.annotation.JsonProperty("projectName") String projectName, @com.fasterxml.jackson.annotation.JsonProperty("scopeText") String scopeText, @com.fasterxml.jackson.annotation.JsonProperty("scheduleText") String scheduleText, @com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("nextRunAt") java.time.LocalDateTime nextRunAt, @com.fasterxml.jackson.annotation.JsonProperty("intervalSeconds") Integer intervalSeconds, @com.fasterxml.jackson.annotation.JsonProperty("failureThreshold") Integer failureThreshold, @com.fasterxml.jackson.annotation.JsonProperty("alertLevel") String alertLevel, @com.fasterxml.jackson.annotation.JsonProperty("lifecycleStatus") String lifecycleStatus, @com.fasterxml.jackson.annotation.JsonProperty("syncStatus") String syncStatus, @com.fasterxml.jackson.annotation.JsonProperty("desiredVersion") Long desiredVersion, @com.fasterxml.jackson.annotation.JsonProperty("lastSyncAttemptAt") java.time.LocalDateTime lastSyncAttemptAt, @com.fasterxml.jackson.annotation.JsonProperty("lastSyncSucceededAt") java.time.LocalDateTime lastSyncSucceededAt, @com.fasterxml.jackson.annotation.JsonProperty("lastVerifiedAt") java.time.LocalDateTime lastVerifiedAt, @com.fasterxml.jackson.annotation.JsonProperty("syncError") String syncError, @com.fasterxml.jackson.annotation.JsonProperty("assetIds") List<Long> assetIds) {
            this.id = id;
            this.name = name;
            this.projectId = projectId;
            this.projectName = projectName;
            this.scopeText = scopeText;
            this.scheduleText = scheduleText;
            this.status = status;
            this.nextRunAt = nextRunAt;
            this.intervalSeconds = intervalSeconds;
            this.failureThreshold = failureThreshold;
            this.alertLevel = alertLevel;
            this.lifecycleStatus = lifecycleStatus;
            this.syncStatus = syncStatus;
            this.desiredVersion = desiredVersion;
            this.lastSyncAttemptAt = lastSyncAttemptAt;
            this.lastSyncSucceededAt = lastSyncSucceededAt;
            this.lastVerifiedAt = lastVerifiedAt;
            this.syncError = syncError;
            this.assetIds = assetIds;
        }

        private final Long id;

        public Long id() {
            return this.id;
        }

        private final String name;

        public String name() {
            return this.name;
        }

        private final Long projectId;

        public Long projectId() {
            return this.projectId;
        }

        private final String projectName;

        public String projectName() {
            return this.projectName;
        }

        private final String scopeText;

        public String scopeText() {
            return this.scopeText;
        }

        private final String scheduleText;

        public String scheduleText() {
            return this.scheduleText;
        }

        private final String status;

        public String status() {
            return this.status;
        }

        private final java.time.LocalDateTime nextRunAt;

        public java.time.LocalDateTime nextRunAt() {
            return this.nextRunAt;
        }

        private final Integer intervalSeconds;

        public Integer intervalSeconds() {
            return this.intervalSeconds;
        }

        private final Integer failureThreshold;

        public Integer failureThreshold() {
            return this.failureThreshold;
        }

        private final String alertLevel;

        public String alertLevel() {
            return this.alertLevel;
        }

        private final String lifecycleStatus;

        public String lifecycleStatus() {
            return this.lifecycleStatus;
        }

        private final String syncStatus;

        public String syncStatus() {
            return this.syncStatus;
        }

        private final Long desiredVersion;

        public Long desiredVersion() {
            return this.desiredVersion;
        }

        private final java.time.LocalDateTime lastSyncAttemptAt;

        public java.time.LocalDateTime lastSyncAttemptAt() {
            return this.lastSyncAttemptAt;
        }

        private final java.time.LocalDateTime lastSyncSucceededAt;

        public java.time.LocalDateTime lastSyncSucceededAt() {
            return this.lastSyncSucceededAt;
        }

        private final java.time.LocalDateTime lastVerifiedAt;

        public java.time.LocalDateTime lastVerifiedAt() {
            return this.lastVerifiedAt;
        }

        private final String syncError;

        public String syncError() {
            return this.syncError;
        }

        private final List<Long> assetIds;

        public List<Long> assetIds() {
            return this.assetIds;
        }
    }
}
