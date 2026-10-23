package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.persistence.Version;

@Entity
@Table(name = "ops_alert", uniqueConstraints = @UniqueConstraint(columnNames = { "externalSource", "externalAlertId" }))
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    private String level;

    private String projectName;

    private String targetName;

    private String message;

    private String status;

    private LocalDateTime occurredAt;

    private String externalSource;

    private String externalAlertId;

    private Long externalMonitorId;

    private Long projectId;

    private Long inspectionPlanId;

    private Long assetId;

    private String inspectionPlanName;

    private String projectNameSnapshot;

    private String targetNameSnapshot;

    private String externalLifecycle;

    private String handlingStatus;

    private LocalDateTime firstOccurredAt;

    private LocalDateTime lastOccurredAt;

    private LocalDateTime resolvedAt;

    private String handledBy;

    private LocalDateTime handledAt;

    private String handlingNote;

    protected Alert() {
    }

    public Alert(String level, String projectName, String targetName, String message, String status, LocalDateTime occurredAt) {
        this.level = level;
        this.projectName = projectName;
        this.targetName = targetName;
        this.message = message;
        this.status = status;
        this.occurredAt = occurredAt;
        this.externalLifecycle = "RESOLVED".equals(status) ? "RESOLVED" : "FIRING";
        this.handlingStatus = com.lqbz.ops.compat.Java8.eval(() -> {
            switch(status) {
                case "ACKNOWLEDGED":
                case "CLOSED":
                    return status;
                default:
                    return "OPEN";
            }
        });
        this.firstOccurredAt = occurredAt;
        this.lastOccurredAt = occurredAt;
        this.resolvedAt = "RESOLVED".equals(status) ? occurredAt : null;
    }

    public static Alert external(String source, String externalId, Long monitorId, String level, String projectName, String targetName, String message, LocalDateTime occurredAt) {
        Alert alert = new Alert(level, projectName, targetName, message, "OPEN", occurredAt);
        alert.externalSource = source;
        alert.externalAlertId = externalId;
        alert.externalMonitorId = monitorId;
        alert.externalLifecycle = "FIRING";
        alert.handlingStatus = "OPEN";
        alert.firstOccurredAt = occurredAt;
        alert.lastOccurredAt = occurredAt;
        return alert;
    }

    public boolean fire(String message, LocalDateTime occurredAt) {
        boolean newCycle = "RESOLVED".equals(this.externalLifecycle);
        this.externalLifecycle = "FIRING";
        this.message = message;
        this.lastOccurredAt = occurredAt;
        this.resolvedAt = null;
        if (newCycle) {
            this.occurredAt = occurredAt;
            this.handlingStatus = "OPEN";
            this.status = "OPEN";
            this.handledBy = null;
            this.handledAt = null;
            this.handlingNote = null;
        }
        return newCycle;
    }

    public void resolve(LocalDateTime resolvedAt) {
        this.externalLifecycle = "RESOLVED";
        this.resolvedAt = resolvedAt;
    }

    public void assignOwnershipSnapshot(Long projectId, Long inspectionPlanId, Long assetId, String inspectionPlanName, String projectName, String targetName) {
        if (this.assetId != null)
            return;
        this.projectId = projectId;
        this.inspectionPlanId = inspectionPlanId;
        this.assetId = assetId;
        this.inspectionPlanName = inspectionPlanName;
        this.projectNameSnapshot = projectName;
        this.targetNameSnapshot = targetName;
    }

    public void acknowledge() {
        if (!"OPEN".equals(getHandlingStatus()))
            throw new IllegalStateException("只有待处理告警可以确认");
        this.handlingStatus = "ACKNOWLEDGED";
        this.status = "ACKNOWLEDGED";
    }

    public void acknowledge(String operator, String note, LocalDateTime handledAt) {
        acknowledge();
        recordHandling(operator, note, handledAt);
    }

    public void close(String operator, String note, LocalDateTime handledAt) {
        if (!"RESOLVED".equals(externalLifecycle))
            throw new IllegalStateException("外部告警恢复后才能关闭");
        if ("CLOSED".equals(getHandlingStatus()))
            throw new IllegalStateException("告警已经关闭");
        this.handlingStatus = "CLOSED";
        this.status = "CLOSED";
        recordHandling(operator, note, handledAt);
    }

    private void recordHandling(String operator, String note, LocalDateTime handledAt) {
        this.handledBy = operator;
        this.handledAt = handledAt;
        this.handlingNote = note;
    }

    public Long getId() {
        return id;
    }

    public String getLevel() {
        return level;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getTargetName() {
        return targetName;
    }

    public String getMessage() {
        return message;
    }

    public String getStatus() {
        return handlingStatus == null ? status : handlingStatus;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public String getExternalSource() {
        return externalSource;
    }

    public String getExternalAlertId() {
        return externalAlertId;
    }

    public Long getExternalMonitorId() {
        return externalMonitorId;
    }

    public String getExternalLifecycle() {
        return externalLifecycle;
    }

    public String getHandlingStatus() {
        return handlingStatus == null ? status : handlingStatus;
    }

    public LocalDateTime getFirstOccurredAt() {
        return firstOccurredAt;
    }

    public LocalDateTime getLastOccurredAt() {
        return lastOccurredAt;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getInspectionPlanId() {
        return inspectionPlanId;
    }

    public Long getAssetId() {
        return assetId;
    }

    public String getInspectionPlanName() {
        return inspectionPlanName;
    }

    public String getProjectNameSnapshot() {
        return projectNameSnapshot;
    }

    public String getTargetNameSnapshot() {
        return targetNameSnapshot;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public LocalDateTime getHandledAt() {
        return handledAt;
    }

    public String getHandlingNote() {
        return handlingNote;
    }
}
