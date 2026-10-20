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
    if (!"OWNED".equals(ownershipStatus) && !"DISABLED_RELEASED".equals(ownershipStatus)) return;
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
    this.monitorDesiredState = activeOwnerKey == null ? "DETACHED" : "DELETED";
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

  public void releaseDisabledOwnership() {
    if (!"OWNED".equals(ownershipStatus)) return;
    this.ownershipStatus = "DISABLED_RELEASED";
    this.activeOwnerKey = null;
  }

  public void reacquireOwnership(long desiredVersion) {
    if (!"DISABLED_RELEASED".equals(ownershipStatus)) throw new IllegalStateException("只有已停用释放的绑定可以重新获取所有权");
    this.desiredVersion = desiredVersion;
    this.ownershipStatus = "OWNED";
    this.activeOwnerKey = assetId;
    this.monitorDesiredState = "ENABLED";
    this.ruleDesiredState = "ENABLED";
  }

  public void markSyncing(LocalDateTime now) {
    this.lastSyncAttemptAt = now;
    this.syncError = null;
  }

  public void markSynchronized(Long monitorId, Long alertRuleId, boolean enabled, LocalDateTime now) {
    this.hertzbeatMonitorId = monitorId;
    this.hertzbeatAlertRuleId = alertRuleId;
    this.monitorActualState = enabled ? "ENABLED" : "DISABLED";
    this.ruleActualState = enabled ? "ENABLED" : "DISABLED";
    this.lastSyncSucceededAt = now;
    this.lastVerifiedAt = now;
    this.syncError = null;
  }

  public void markSyncFailed(String message, LocalDateTime now) {
    this.monitorActualState = "FAILED";
    this.ruleActualState = "FAILED";
    this.lastSyncAttemptAt = now;
    this.syncError = message;
  }

  public void markMonitorSynchronized(Long monitorId, boolean enabled, LocalDateTime now) {
    this.hertzbeatMonitorId = monitorId;
    this.monitorActualState = enabled ? "ENABLED" : "DISABLED";
  }

  public void markRuleSynchronized(Long alertRuleId, boolean enabled, LocalDateTime now) {
    this.hertzbeatAlertRuleId = alertRuleId;
    this.ruleActualState = enabled ? "ENABLED" : "DISABLED";
  }

  public void markMonitorFailed(String message, LocalDateTime now) {
    this.monitorActualState = "FAILED";
    this.lastSyncAttemptAt = now;
    this.syncError = message;
  }

  public void markRuleFailed(String message, LocalDateTime now) {
    this.ruleActualState = "FAILED";
    this.lastSyncAttemptAt = now;
    this.syncError = message;
  }

  public void markRuleDeleted(LocalDateTime now) {
    this.ruleActualState = "DELETED";
    this.hertzbeatAlertRuleId = null;
  }

  public void markMonitorDeleted(LocalDateTime now) {
    this.monitorActualState = "DELETED";
    this.hertzbeatMonitorId = null;
  }

  public void markMonitorDetached(LocalDateTime now) {
    this.monitorActualState = "DETACHED";
    this.hertzbeatMonitorId = null;
  }

  public void completeRelease(LocalDateTime now) {
    this.lastSyncSucceededAt = now;
    this.lastVerifiedAt = now;
    this.syncError = null;
    releaseOwnership();
  }

  public void markSyncError(String message, LocalDateTime now) {
    this.lastSyncAttemptAt = now;
    this.syncError = message;
  }

  public void markDeleted(LocalDateTime now) {
    this.monitorActualState = "DELETED";
    this.ruleActualState = "DELETED";
    this.hertzbeatMonitorId = null;
    this.hertzbeatAlertRuleId = null;
    this.lastSyncSucceededAt = now;
    this.lastVerifiedAt = now;
    this.syncError = null;
    releaseOwnership();
  }

  public boolean isHertzBeatReady(long currentDesiredVersion) {
    return "OWNED".equals(ownershipStatus)
        && Long.valueOf(currentDesiredVersion).equals(desiredVersion)
        && "ENABLED".equals(monitorDesiredState)
        && "ENABLED".equals(monitorActualState)
        && "ENABLED".equals(ruleDesiredState)
        && "ENABLED".equals(ruleActualState)
        && hertzbeatMonitorId != null
        && hertzbeatAlertRuleId != null
        && syncError == null;
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
