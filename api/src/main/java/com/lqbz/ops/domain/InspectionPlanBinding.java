package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "ops_inspection_plan_binding", uniqueConstraints =
    @jakarta.persistence.UniqueConstraint(name = "uk_ops_plan_binding_active_owner", columnNames = "activeOwnerKey"))
public class InspectionPlanBinding {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Version
  private Long version;
  private Long planId;
  @Column(nullable = false)
  private Long assetId;
  private Long desiredVersion;
  private Long activeOwnerKey;
  private String ownershipStatus;
  private String monitorDesiredState;
  private String monitorActualState;
  private String ruleDesiredState;
  private String ruleActualState;
  private Long hertzbeatMonitorId;
  private Long hertzbeatAlertRuleId;
  private LocalDateTime lastSyncAttemptAt;
  private LocalDateTime lastSyncSucceededAt;
  private LocalDateTime lastVerifiedAt;
  @Column(columnDefinition = "text")
  private String syncError;

  protected InspectionPlanBinding() {}

  public static InspectionPlanBinding managed(Long planId, Long assetId, long desiredVersion) {
    InspectionPlanBinding binding = new InspectionPlanBinding();
    binding.planId = planId;
    binding.assetId = assetId;
    binding.desiredVersion = desiredVersion;
    binding.activeOwnerKey = assetId;
    binding.ownershipStatus = "OWNED";
    binding.monitorDesiredState = "ENABLED";
    binding.monitorActualState = "UNKNOWN";
    binding.ruleDesiredState = "ENABLED";
    binding.ruleActualState = "UNKNOWN";
    return binding;
  }

  public void alignWithPlanStatus(String planStatus, long desiredVersion) {
    if (!"OWNED".equals(ownershipStatus)) return;
    this.desiredVersion = desiredVersion;
    String desiredState = "ENABLED".equals(planStatus) ? "ENABLED" : "DISABLED";
    this.monitorDesiredState = desiredState;
    this.ruleDesiredState = desiredState;
  }

  public static InspectionPlanBinding claimExisting(Long planId, Long assetId, long desiredVersion, Long monitorId) {
    InspectionPlanBinding binding = managed(planId, assetId, desiredVersion);
    binding.hertzbeatMonitorId = monitorId;
    binding.monitorActualState = "ENABLED";
    binding.ruleActualState = "UNKNOWN";
    return binding;
  }

  public void requestRelease(long desiredVersion) {
    this.desiredVersion = desiredVersion;
    this.ownershipStatus = "PENDING_RELEASE";
    this.monitorDesiredState = "DELETED";
    this.ruleDesiredState = "DELETED";
  }

  public void restoreOwnership(long desiredVersion) {
    if (!"PENDING_RELEASE".equals(ownershipStatus)) throw new IllegalStateException("只有待释放绑定可以恢复");
    this.desiredVersion = desiredVersion;
    this.ownershipStatus = "OWNED";
    this.monitorDesiredState = "ENABLED";
    this.ruleDesiredState = "ENABLED";
  }

  public void releaseOwnership() {
    this.ownershipStatus = "RELEASED";
    this.activeOwnerKey = null;
  }

  public Long getId() { return id; }
  public Long getPlanId() { return planId; }
  public Long getAssetId() { return assetId; }
  public Long getDesiredVersion() { return desiredVersion; }
  public Long getActiveOwnerKey() { return activeOwnerKey; }
  public String getOwnershipStatus() { return ownershipStatus; }
  public String getMonitorDesiredState() { return monitorDesiredState; }
  public String getMonitorActualState() { return monitorActualState; }
  public String getRuleDesiredState() { return ruleDesiredState; }
  public String getRuleActualState() { return ruleActualState; }
  public Long getHertzbeatMonitorId() { return hertzbeatMonitorId; }
  public Long getHertzbeatAlertRuleId() { return hertzbeatAlertRuleId; }
  public LocalDateTime getLastSyncAttemptAt() { return lastSyncAttemptAt; }
  public LocalDateTime getLastSyncSucceededAt() { return lastSyncSucceededAt; }
  public LocalDateTime getLastVerifiedAt() { return lastVerifiedAt; }
  public String getSyncError() { return syncError; }
}
