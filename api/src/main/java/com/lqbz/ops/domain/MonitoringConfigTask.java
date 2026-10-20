package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "ops_monitoring_config_task", uniqueConstraints = @jakarta.persistence.UniqueConstraint(
    name = "uk_ops_monitoring_task_target", columnNames = {"planId", "assetId", "desiredVersion", "operation"}))
public class MonitoringConfigTask {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Version
  private Long version;
  private Long planId;
  private Long assetId;
  private Long desiredVersion;
  private String operation;
  private String status = "PENDING";
  private Integer attemptCount = 0;
  private LocalDateTime nextAttemptAt;
  private LocalDateTime createdAt;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private String errorMessage;

  protected MonitoringConfigTask() {}

  public MonitoringConfigTask(Long planId, Long assetId, Long desiredVersion, String operation, LocalDateTime createdAt) {
    this.planId = planId;
    this.assetId = assetId;
    this.desiredVersion = desiredVersion;
    this.operation = operation;
    this.createdAt = createdAt;
    this.nextAttemptAt = createdAt;
  }

  public boolean isObsolete(long currentDesiredVersion) {
    return desiredVersion == null || desiredVersion < currentDesiredVersion;
  }

  public void claim(LocalDateTime now) {
    if (!"PENDING".equals(status) && !"RETRY".equals(status)) throw new IllegalStateException("同步任务不可领取");
    status = "RUNNING";
    startedAt = now;
    attemptCount = attemptCount == null ? 1 : attemptCount + 1;
  }

  public void retry(LocalDateTime nextAttemptAt, String errorMessage) {
    status = "RETRY";
    this.nextAttemptAt = nextAttemptAt;
    this.errorMessage = errorMessage;
  }

  public void succeed(LocalDateTime now) {
    status = "SUCCEEDED";
    finishedAt = now;
    errorMessage = null;
  }

  public void obsolete(LocalDateTime now) {
    status = "OBSOLETE";
    finishedAt = now;
    errorMessage = null;
  }

  public void recover(LocalDateTime now) {
    if (!"RUNNING".equals(status)) return;
    status = "RETRY";
    nextAttemptAt = now;
    errorMessage = "同步进程中断，任务已恢复";
  }

  public void reschedule(LocalDateTime now) {
    if ("RUNNING".equals(status) || "PENDING".equals(status) || "RETRY".equals(status)) return;
    status = "PENDING";
    nextAttemptAt = now;
    startedAt = null;
    finishedAt = null;
    errorMessage = null;
  }

  public Long getId() { return id; }
  public Long getPlanId() { return planId; }
  public Long getAssetId() { return assetId; }
  public Long getDesiredVersion() { return desiredVersion; }
  public String getOperation() { return operation; }
  public String getStatus() { return status; }
  public Integer getAttemptCount() { return attemptCount; }
  public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public LocalDateTime getStartedAt() { return startedAt; }
  public LocalDateTime getFinishedAt() { return finishedAt; }
  public String getErrorMessage() { return errorMessage; }
}
