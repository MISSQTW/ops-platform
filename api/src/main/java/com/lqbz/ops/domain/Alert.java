package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "ops_alert", uniqueConstraints = @UniqueConstraint(columnNames = {"externalSource", "externalAlertId"}))
public class Alert {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
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

  protected Alert() {}

  public Alert(String level, String projectName, String targetName, String message, String status, LocalDateTime occurredAt) {
    this.level = level;
    this.projectName = projectName;
    this.targetName = targetName;
    this.message = message;
    this.status = status;
    this.occurredAt = occurredAt;
    this.handlingStatus = status;
  }

  public static Alert external(String source, String externalId, Long monitorId, String level, String projectName,
      String targetName, String message, LocalDateTime occurredAt) {
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

  public void fire(String message, LocalDateTime occurredAt) {
    this.externalLifecycle = "FIRING";
    this.message = message;
    this.lastOccurredAt = occurredAt;
    this.resolvedAt = null;
  }

  public void resolve(LocalDateTime resolvedAt) {
    this.externalLifecycle = "RESOLVED";
    this.resolvedAt = resolvedAt;
  }

  public void assignOwnershipSnapshot(Long projectId, Long inspectionPlanId, Long assetId,
      String inspectionPlanName, String projectName, String targetName) {
    if (this.assetId != null) return;
    this.projectId = projectId;
    this.inspectionPlanId = inspectionPlanId;
    this.assetId = assetId;
    this.inspectionPlanName = inspectionPlanName;
    this.projectNameSnapshot = projectName;
    this.targetNameSnapshot = targetName;
  }

  public void acknowledge() {
    this.handlingStatus = "ACKNOWLEDGED";
    this.status = "ACKNOWLEDGED";
  }

  public Long getId() { return id; }
  public String getLevel() { return level; }
  public String getProjectName() { return projectName; }
  public String getTargetName() { return targetName; }
  public String getMessage() { return message; }
  public String getStatus() { return handlingStatus == null ? status : handlingStatus; }
  public LocalDateTime getOccurredAt() { return occurredAt; }
  public String getExternalSource() { return externalSource; }
  public String getExternalAlertId() { return externalAlertId; }
  public Long getExternalMonitorId() { return externalMonitorId; }
  public String getExternalLifecycle() { return externalLifecycle; }
  public String getHandlingStatus() { return handlingStatus == null ? status : handlingStatus; }
  public LocalDateTime getFirstOccurredAt() { return firstOccurredAt; }
  public LocalDateTime getLastOccurredAt() { return lastOccurredAt; }
  public LocalDateTime getResolvedAt() { return resolvedAt; }
  public Long getProjectId() { return projectId; }
  public Long getInspectionPlanId() { return inspectionPlanId; }
  public Long getAssetId() { return assetId; }
  public String getInspectionPlanName() { return inspectionPlanName; }
  public String getProjectNameSnapshot() { return projectNameSnapshot; }
  public String getTargetNameSnapshot() { return targetNameSnapshot; }
  public String getHandledBy() { return handledBy; }
  public LocalDateTime getHandledAt() { return handledAt; }
  public String getHandlingNote() { return handlingNote; }
}
