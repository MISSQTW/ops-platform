package com.lqbz.ops.service;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.InspectionPlan;
import com.lqbz.ops.domain.InspectionPlanBinding;
import com.lqbz.ops.domain.InspectionPlanBindingRepository;
import com.lqbz.ops.domain.InspectionPlanRepository;
import com.lqbz.ops.domain.MonitoringConfigTask;
import com.lqbz.ops.domain.MonitoringConfigTaskRepository;
import com.lqbz.ops.integration.HertzBeatClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class InspectionPlanMonitoringSyncService {

    private final InspectionPlanRepository plans;

    private final InspectionPlanBindingRepository bindings;

    private final MonitoringConfigTaskRepository tasks;

    private final AssetRepository assets;

    private final HertzBeatClient hertzBeatClient;

    private final InspectionPlanMonitoringSyncService self;

    private final MonitoringTopologyLock topologyLock;

    public InspectionPlanMonitoringSyncService(InspectionPlanRepository plans, InspectionPlanBindingRepository bindings, MonitoringConfigTaskRepository tasks, AssetRepository assets, HertzBeatClient hertzBeatClient, @Lazy InspectionPlanMonitoringSyncService self, MonitoringTopologyLock topologyLock) {
        this.plans = plans;
        this.bindings = bindings;
        this.tasks = tasks;
        this.assets = assets;
        this.hertzBeatClient = hertzBeatClient;
        this.self = self;
        this.topologyLock = topologyLock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterPlanSaved(InspectionPlanSyncRequested event) {
        syncPlan(event.planId());
    }

    public SyncSummary syncPlan(Long planId) {
        self.enqueueCurrentVersion(planId, true);
        List<Long> taskIds = tasks.findByPlanIdOrderByCreatedAt(planId).stream().filter(task -> "PENDING".equals(task.getStatus()) || "RETRY".equals(task.getStatus())).map(MonitoringConfigTask::getId).collect(java.util.stream.Collectors.toList());
        taskIds.forEach(self::processTask);
        InspectionPlan current = plans.findById(planId).orElseThrow(java.util.NoSuchElementException::new);
        return new SyncSummary(planId, current.getSyncStatus(), taskIds.size(), current.getSyncError());
    }

    public SyncAllSummary syncAllPlans() {
        List<Long> planIds = plans.findAll().stream().filter(InspectionPlan::isMonitoringPolicy).filter(plan -> !"DELETED".equals(plan.getLifecycleStatus())).map(InspectionPlan::getId).collect(java.util.stream.Collectors.toList());
        List<SyncSummary> results = planIds.stream().map(this::syncPlan).collect(java.util.stream.Collectors.toList());
        return new SyncAllSummary(results.size(), results);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processTask(Long taskId) {
        MonitoringConfigTask task = tasks.findByIdForUpdate(taskId).orElseThrow(() -> new NoSuchElementException("同步任务不存在"));
        if (!"PENDING".equals(task.getStatus()) && !"RETRY".equals(task.getStatus()))
            return;
        InspectionPlan plan = plans.findByIdForUpdate(task.getPlanId()).orElseThrow(() -> new NoSuchElementException("巡检计划不存在"));
        InspectionPlanBinding binding = bindings.findCurrentForUpdate(task.getPlanId(), task.getAssetId()).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (task.isObsolete(plan.getDesiredVersion()) || binding == null || !task.getDesiredVersion().equals(binding.getDesiredVersion())) {
            task.obsolete(now);
            refreshPlanSummary(plan, now);
            return;
        }
        topologyLock.acquireServices(com.lqbz.ops.compat.Java8.list(binding.getAssetId()));
        boolean reservedDisabled = "DISABLED_RELEASED".equals(binding.getOwnershipStatus());
        if (reservedDisabled) {
            if (bindings.findByActiveOwnerKey(binding.getAssetId()).isPresent()) {
                task.obsolete(now);
                return;
            }
            binding.reserveDisabledReconciliation();
            bindings.saveAndFlush(binding);
        }
        if ("PENDING_RELEASE".equals(binding.getOwnershipStatus())) {
            Long monitorId = binding.getHertzbeatMonitorId() == null ? assets.findById(binding.getAssetId()).map(Asset::getHertzbeatMonitorId).orElse(null) : binding.getHertzbeatMonitorId();
            boolean transferred = bindings.findByActiveOwnerKey(binding.getAssetId()).filter(owner -> !owner.getId().equals(binding.getId())).isPresent();
            if (!transferred && monitorId != null) {
                transferred = bindings.findByAssetIdOrderById(binding.getAssetId()).stream().anyMatch(other -> !other.getId().equals(binding.getId()) && !"RELEASED".equals(other.getOwnershipStatus()) && monitorId.equals(other.getHertzbeatMonitorId()));
            }
            binding.reserveReleaseOwnership(transferred);
            bindings.saveAndFlush(binding);
        }
        task.claim(now);
        binding.markSyncing(now);
        plan.markSyncing(now);
        try {
            Asset asset = assets.findById(task.getAssetId()).orElseThrow(() -> new NoSuchElementException("服务不存在"));
            if ("DELETED".equals(binding.getRuleDesiredState())) {
                deleteExternalResources(binding, asset, now);
            } else {
                synchronizeExternalResources(plan, binding, asset, now);
            }
            task.succeed(now);
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            binding.markSyncError(message, now);
            task.retry(now.plusSeconds(retryDelaySeconds(task.getAttemptCount())), message);
        } finally {
            if (reservedDisabled)
                binding.releaseDisabledReconciliation();
        }
        refreshPlanSummary(plan, now);
    }

    @Scheduled(fixedDelayString = "${ops.hertzbeat.config-sync-interval-ms:30000}", initialDelayString = "${ops.hertzbeat.config-sync-initial-delay-ms:30000}")
    public void reconcile() {
        LocalDateTime now = LocalDateTime.now();
        tasks.findByStatusAndStartedAtLessThanEqualOrderByCreatedAt("RUNNING", now.minusMinutes(1)).forEach(task -> {
            task.recover(now);
            tasks.save(task);
        });
        plans.findAll().stream().filter(InspectionPlan::isMonitoringPolicy).filter(plan -> !"DELETED".equals(plan.getLifecycleStatus())).forEach(plan -> self.enqueueCurrentVersion(plan.getId(), true));
        List<Long> taskIds = tasks.findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAt(com.lqbz.ops.compat.Java8.list("PENDING", "RETRY"), now).stream().map(MonitoringConfigTask::getId).collect(java.util.stream.Collectors.toList());
        taskIds.forEach(self::processTask);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueCurrentVersion(Long planId, boolean rescheduleCompleted) {
        InspectionPlan plan = plans.findByIdForUpdate(planId).orElseThrow(() -> new NoSuchElementException("巡检计划不存在"));
        for (InspectionPlanBinding binding : bindings.findByPlanIdOrderById(plan.getId())) {
            if ("RELEASED".equals(binding.getOwnershipStatus()))
                continue;
            if ("DISABLED_RELEASED".equals(binding.getOwnershipStatus()) && bindings.findByActiveOwnerKey(binding.getAssetId()).isPresent())
                continue;
            String operation = "DELETED".equals(binding.getRuleDesiredState()) ? "DELETE" : "UPSERT";
            MonitoringConfigTask existing = tasks.findByPlanIdAndAssetIdAndDesiredVersionAndOperation(plan.getId(), binding.getAssetId(), plan.getDesiredVersion(), operation).orElse(null);
            if (existing != null) {
                if (rescheduleCompleted) {
                    existing.reschedule(LocalDateTime.now());
                    tasks.save(existing);
                }
                continue;
            }
            tasks.saveAndFlush(new MonitoringConfigTask(plan.getId(), binding.getAssetId(), plan.getDesiredVersion(), operation, LocalDateTime.now()));
        }
    }

    private void synchronizeExternalResources(InspectionPlan plan, InspectionPlanBinding binding, Asset asset, LocalDateTime now) throws Exception {
        if (binding.getHertzbeatMonitorId() != null && asset.getHertzbeatMonitorId() == null) {
            asset.markSynced(binding.getHertzbeatMonitorId(), "恢复巡检计划 Monitor 映射");
        }
        HertzBeatClient.MonitorConfig monitorConfig = null;
        if (asset.getHertzbeatMonitorId() != null) {
            try {
                monitorConfig = hertzBeatClient.getMonitorConfig(asset.getHertzbeatMonitorId());
            } catch (HertzBeatClient.MonitorNotFoundException exception) {
                asset.invalidateMonitoring(exception.getMessage());
            }
        }
        Long alertRuleId = binding.getHertzbeatAlertRuleId();
        HertzBeatClient.AlertRule alertRule = null;
        if (alertRuleId != null) {
            try {
                alertRule = hertzBeatClient.getAlertRule(alertRuleId);
            } catch (HertzBeatClient.AlertRuleNotFoundException ignored) {
                alertRuleId = null;
            }
        }
        boolean enabled = "ENABLED".equals(binding.getMonitorDesiredState());
        Long monitorId = asset.getHertzbeatMonitorId();
        try {
            if (!monitorMatches(monitorConfig, plan, asset, enabled)) {
                HertzBeatClient.SyncResult monitor = hertzBeatClient.syncMonitor(asset, plan.getIntervalSeconds(), enabled);
                monitorId = monitor.monitorId();
                asset.markSynced(monitorId, monitor.message());
                HertzBeatClient.MonitorConfig verified = hertzBeatClient.getMonitorConfig(monitorId);
                if (!monitorMatches(verified, plan, asset, enabled))
                    throw new IllegalStateException("HertzBeat Monitor 写后校验不一致");
            }
            if (monitorId == null)
                throw new IllegalStateException("HertzBeat Monitor ID 缺失");
            binding.markMonitorSynchronized(monitorId, enabled, now);
        } catch (Exception exception) {
            binding.markMonitorFailed(message(exception), now);
            throw exception;
        }
        HertzBeatClient.AlertRule rule = alertRule;
        try {
            if (!ruleMatches(alertRule, plan, asset, enabled)) {
                rule = hertzBeatClient.upsertAlertRule(new HertzBeatClient.AlertRuleSpec(alertRuleId, ruleName(plan, asset), availabilityExpression(asset), plan.getIntervalSeconds(), plan.getFailureThreshold(), plan.getAlertLevel(), plan.getId(), asset.getId(), enabled));
            }
            if (!ruleMatches(rule, plan, asset, enabled))
                throw new IllegalStateException("HertzBeat 告警规则写后校验不一致");
            binding.markRuleSynchronized(rule.id(), enabled, now);
        } catch (Exception exception) {
            binding.markRuleFailed(message(exception), now);
            throw exception;
        }
        binding.markSynchronized(monitorId, rule.id(), enabled, now);
        if (!enabled)
            binding.releaseDisabledOwnership();
    }

    private void deleteExternalResources(InspectionPlanBinding binding, Asset asset, LocalDateTime now) throws Exception {
        if (binding.getHertzbeatAlertRuleId() != null) {
            try {
                hertzBeatClient.deleteAlertRule(binding.getHertzbeatAlertRuleId());
                binding.markRuleDeleted(now);
            } catch (Exception exception) {
                binding.markRuleFailed(message(exception), now);
                throw exception;
            }
        } else {
            binding.markRuleDeleted(now);
        }
        if ("DETACHED".equals(binding.getMonitorDesiredState())) {
            binding.markMonitorDetached(now);
        } else {
            Long monitorId = binding.getHertzbeatMonitorId() == null ? asset.getHertzbeatMonitorId() : binding.getHertzbeatMonitorId();
            try {
                if (monitorId != null)
                    hertzBeatClient.deleteMonitor(monitorId);
                binding.markMonitorDeleted(now);
            } catch (Exception exception) {
                binding.markMonitorFailed(message(exception), now);
                throw exception;
            }
            if (java.util.Objects.equals(asset.getHertzbeatMonitorId(), monitorId))
                asset.unbindMonitoring();
        }
        binding.completeRelease(now);
    }

    private void refreshPlanSummary(InspectionPlan plan, LocalDateTime now) {
        List<InspectionPlanBinding> current = bindings.findByPlanIdOrderById(plan.getId()).stream().filter(binding -> !"RELEASED".equals(binding.getOwnershipStatus())).collect(java.util.stream.Collectors.toList());
        if (current.isEmpty()) {
            if ("DELETING".equals(plan.getLifecycleStatus()))
                plan.markDeleted(now);
            else
                plan.updateSyncSummary("SYNCED", now, null);
            return;
        }
        long failed = current.stream().filter(binding -> binding.getSyncError() != null).count();
        long synced = current.stream().filter(binding -> binding.getSyncError() == null && binding.getMonitorDesiredState().equals(binding.getMonitorActualState()) && binding.getRuleDesiredState().equals(binding.getRuleActualState())).count();
        String status = failed == current.size() ? "FAILED" : failed > 0 ? "PARTIAL" : synced == current.size() ? "SYNCED" : "SYNCING";
        String error = failed == 0 ? null : failed + " 个服务同步失败";
        plan.updateSyncSummary(status, now, error);
    }

    private long retryDelaySeconds(Integer attemptCount) {
        int attempts = attemptCount == null ? 1 : attemptCount;
        return Math.min(300, 15L * (1L << Math.min(4, Math.max(0, attempts - 1))));
    }

    private String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private String ruleName(InspectionPlan plan, Asset asset) {
        return "ops-plan-" + plan.getId() + "-asset-" + asset.getId();
    }

    private boolean monitorMatches(HertzBeatClient.MonitorConfig config, InspectionPlan plan, Asset asset, boolean enabled) throws Exception {
        if (config == null)
            return false;
        HertzBeatClient.MetricTarget target = hertzBeatClient.metricTarget(asset);
        return asset.getName().equals(config.name()) && target.app().equals(config.app()) && target.instance().equals(config.instance()) && plan.getIntervalSeconds() == config.intervalSeconds() && "fixed".equals(config.scheduleType()) && enabled == config.enabled() && hertzBeatClient.monitorParametersMatch(asset, config.params()) && hertzBeatClient.monitorCredentialsMatch(asset, config.id()) && String.valueOf(asset.getId()).equals(config.labels().get("assetId")) && asset.getProjectName().equals(config.labels().get("project")) && asset.getEnvironmentName().equals(config.labels().get("environment"));
    }

    private boolean ruleMatches(HertzBeatClient.AlertRule rule, InspectionPlan plan, Asset asset, boolean enabled) {
        return rule != null && ruleName(plan, asset).equals(rule.name()) && "realtime_metric".equals(rule.type()) && availabilityExpression(asset).equals(rule.expression()) && plan.getIntervalSeconds() == rule.period() && plan.getFailureThreshold() == rule.times() && enabled == rule.enable() && "hertzbeat".equals(rule.datasource()) && "ops-platform".equals(rule.labels().get("managedBy")) && String.valueOf(plan.getId()).equals(rule.labels().get("planId")) && String.valueOf(asset.getId()).equals(rule.labels().get("assetId")) && plan.getAlertLevel().toLowerCase().equals(rule.labels().get("severity"));
    }

    private String availabilityExpression(Asset asset) {
        return "equals(__app__,\"" + hertzBeatClient.metricTarget(asset).app() + "\") && equals(__instance__,\"" + asset.getHertzbeatMonitorId() + "\") && equals(__available__,\"down\")";
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class SyncSummary {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public SyncSummary(@com.fasterxml.jackson.annotation.JsonProperty("planId") Long planId, @com.fasterxml.jackson.annotation.JsonProperty("status") String status, @com.fasterxml.jackson.annotation.JsonProperty("processedTasks") int processedTasks, @com.fasterxml.jackson.annotation.JsonProperty("error") String error) {
            this.planId = planId;
            this.status = status;
            this.processedTasks = processedTasks;
            this.error = error;
        }

        private final Long planId;

        public Long planId() {
            return this.planId;
        }

        private final String status;

        public String status() {
            return this.status;
        }

        private final int processedTasks;

        public int processedTasks() {
            return this.processedTasks;
        }

        private final String error;

        public String error() {
            return this.error;
        }
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class SyncAllSummary {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public SyncAllSummary(@com.fasterxml.jackson.annotation.JsonProperty("totalPlans") int totalPlans, @com.fasterxml.jackson.annotation.JsonProperty("results") List<SyncSummary> results) {
            this.totalPlans = totalPlans;
            this.results = results;
        }

        private final int totalPlans;

        public int totalPlans() {
            return this.totalPlans;
        }

        private final List<SyncSummary> results;

        public List<SyncSummary> results() {
            return this.results;
        }
    }
}
