package com.lqbz.ops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "ops_monitoring_migration_proposal")
public class MonitoringMigrationProposal {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Version
  private Long version;
  @Column(nullable = false)
  private Long assetId;
  @Column(nullable = false)
  private Long projectId;
  @Column(nullable = false, columnDefinition = "text")
  private String observedSnapshot;
  @Column(nullable = false)
  private Integer intervalSeconds;
  @Column(nullable = false)
  private String planName;
  @Column(nullable = false)
  private String status;
  @Column(nullable = false)
  private LocalDateTime createdAt;
  private LocalDateTime confirmedAt;
  private Long resultPlanId;
  private Long preparedPlanId;

  protected MonitoringMigrationProposal() {}

  public MonitoringMigrationProposal(Long assetId, Long projectId, String observedSnapshot,
      int intervalSeconds, String planName) {
    if (assetId == null || projectId == null || observedSnapshot == null || observedSnapshot.isBlank()
        || planName == null || planName.isBlank() || intervalSeconds < 10 || intervalSeconds > 86400) {
      throw new IllegalArgumentException("迁移提案缺少有效服务、项目、现场或频率");
    }
    this.assetId = assetId;
    this.projectId = projectId;
    this.observedSnapshot = observedSnapshot;
    this.intervalSeconds = intervalSeconds;
    this.planName = planName;
    this.status = "PENDING_CONFIRMATION";
    this.createdAt = LocalDateTime.now();
  }

  public void confirm(String currentSnapshot, Long planId) {
    if ("CONFIRMED".equals(status)) {
      if (!Objects.equals(resultPlanId, planId)) throw new IllegalStateException("迁移提案已确认到其他计划");
      return;
    }
    if (!"PENDING_CONFIRMATION".equals(status)) throw new IllegalStateException("迁移提案不能再确认");
    if (!Objects.equals(observedSnapshot, currentSnapshot)) throw new IllegalStateException("迁移现场已变化，请重新生成提案");
    if (planId == null) throw new IllegalArgumentException("确认迁移必须记录目标计划");
    this.resultPlanId = planId;
    this.confirmedAt = LocalDateTime.now();
    this.status = "CONFIRMED";
  }

  public Long getId() { return id; }
  public Long getAssetId() { return assetId; }
  public Long getProjectId() { return projectId; }
  public String getObservedSnapshot() { return observedSnapshot; }
  public Integer getIntervalSeconds() { return intervalSeconds; }
  public String getPlanName() { return planName; }
  public String getStatus() { return status; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public LocalDateTime getConfirmedAt() { return confirmedAt; }
  public Long getResultPlanId() { return resultPlanId; }
  public Long getPreparedPlanId() { return preparedPlanId; }

  public void cancel() {
    if ("CANCELLED".equals(status)) return;
    if (!"PENDING_CONFIRMATION".equals(status)) throw new IllegalStateException("已确认迁移不能取消");
    this.status = "CANCELLED";
  }

  public void attachPreparedPlan(Long planId) {
    if (preparedPlanId != null || planId == null || !"PENDING_CONFIRMATION".equals(status)) {
      throw new IllegalStateException("迁移待确认计划不能重复指定");
    }
    this.preparedPlanId = planId;
  }
}
