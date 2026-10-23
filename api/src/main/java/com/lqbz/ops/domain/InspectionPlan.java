package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Version;

@Entity
@Table(name = "ops_inspection_plan")
public class InspectionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    private String name;

    private Long projectId;

    private String projectName;

    private String scopeText;

    private String scheduleText;

    private String status;

    private LocalDateTime nextRunAt;

    private Integer intervalSeconds;

    private Integer failureThreshold;

    private String alertLevel;

    private String lifecycleStatus = "ACTIVE";

    private String syncStatus = "NOT_SYNCED";

    private Long desiredVersion = 1L;

    private LocalDateTime lastSyncAttemptAt;

    private LocalDateTime lastSyncSucceededAt;

    private LocalDateTime lastVerifiedAt;

    private String syncError;

    protected InspectionPlan() {
    }

    public InspectionPlan(String name, String projectName, String scopeText, String scheduleText, String status, LocalDateTime nextRunAt) {
        this.name = name;
        this.projectName = projectName;
        this.scopeText = scopeText;
        this.scheduleText = scheduleText;
        this.status = status;
        this.nextRunAt = nextRunAt;
    }

    public static InspectionPlan monitoring(String name, Long projectId, String projectName, int intervalSeconds, int failureThreshold, String alertLevel) {
        InspectionPlan plan = new InspectionPlan(name, projectName, "", "每 " + intervalSeconds + " 秒", "ENABLED", LocalDateTime.now());
        plan.projectId = projectId;
        plan.intervalSeconds = intervalSeconds;
        plan.failureThreshold = failureThreshold;
        plan.alertLevel = alertLevel;
        return plan;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getScopeText() {
        return scopeText;
    }

    public String getScheduleText() {
        return isMonitoringPolicy() ? "每 " + intervalSeconds + " 秒" : scheduleText;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getNextRunAt() {
        return nextRunAt;
    }

    public Integer getIntervalSeconds() {
        return intervalSeconds;
    }

    public Integer getFailureThreshold() {
        return failureThreshold;
    }

    public String getAlertLevel() {
        return alertLevel;
    }

    public String getLifecycleStatus() {
        return lifecycleStatus;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public Long getDesiredVersion() {
        return desiredVersion == null ? 1L : desiredVersion;
    }

    public LocalDateTime getLastSyncAttemptAt() {
        return lastSyncAttemptAt;
    }

    public LocalDateTime getLastSyncSucceededAt() {
        return lastSyncSucceededAt;
    }

    public LocalDateTime getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public String getSyncError() {
        return syncError;
    }

    public void update(String name, String projectName, String scopeText, String scheduleText, String status, LocalDateTime nextRunAt) {
        this.name = name;
        this.projectName = projectName;
        this.scopeText = scopeText;
        this.scheduleText = scheduleText;
        this.status = status;
        this.nextRunAt = nextRunAt;
    }

    public void scheduleNextRun(LocalDateTime nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public void configureStatus(String status) {
        this.status = status;
    }

    public void reconfigure(String name, int intervalSeconds, int failureThreshold, String alertLevel, String status) {
        this.name = name;
        this.intervalSeconds = intervalSeconds;
        this.failureThreshold = failureThreshold;
        this.alertLevel = alertLevel;
        this.status = status;
        this.desiredVersion = getDesiredVersion() + 1;
        this.syncStatus = "NOT_SYNCED";
        this.syncError = null;
    }

    public void markDeleting() {
        this.lifecycleStatus = "DELETING";
        this.status = "DISABLED";
        this.desiredVersion = getDesiredVersion() + 1;
        this.syncStatus = "NOT_SYNCED";
    }

    public boolean isMonitoringPolicy() {
        return projectId != null && intervalSeconds != null;
    }

    public void markSyncing(LocalDateTime now) {
        this.syncStatus = "SYNCING";
        this.lastSyncAttemptAt = now;
        this.syncError = null;
    }

    public void updateSyncSummary(String syncStatus, LocalDateTime now, String error) {
        this.syncStatus = syncStatus;
        this.lastSyncAttemptAt = now;
        this.syncError = error;
        if ("SYNCED".equals(syncStatus)) {
            this.lastSyncSucceededAt = now;
            this.lastVerifiedAt = now;
        }
    }

    public void touchDesiredVersion() {
        this.desiredVersion = getDesiredVersion() + 1;
        this.syncStatus = "NOT_SYNCED";
        this.syncError = null;
    }

    public void markDeleted(LocalDateTime now) {
        this.lifecycleStatus = "DELETED";
        updateSyncSummary("SYNCED", now, null);
    }
}
