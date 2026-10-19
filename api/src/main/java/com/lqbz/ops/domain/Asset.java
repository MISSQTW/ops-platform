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
@Table(name = "ops_asset")
public class Asset {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Version
  private Long version;
  private String name;
  private String projectName;
  private String environmentName;
  private String type;
  private String endpoint;
  private String status;
  private Long hertzbeatMonitorId;
  private String syncStatus = "NOT_SYNCED";
  @Column(columnDefinition = "text")
  private String syncMessage;
  private LocalDateTime lastSyncedAt;
  private String monitoringMode = "LOCAL";
  private String mappingStatus = "NEVER_SYNCED";
  private Boolean monitoringStale;
  private LocalDateTime lastCollectedAt;
  private LocalDateTime lastSyncAttemptAt;
  private LocalDateTime lastSuccessfulSyncAt;
  @Column(columnDefinition = "text")
  private String monitoringError;

  protected Asset() {}

  public Asset(String name, String projectName, String environmentName, String type, String endpoint, String status) {
    this.name = name;
    this.projectName = projectName;
    this.environmentName = environmentName;
    this.type = type;
    this.endpoint = endpoint;
    this.status = status;
  }

  public Long getId() { return id; }
  public String getName() { return name; }
  public String getProjectName() { return projectName; }
  public String getEnvironmentName() { return environmentName; }
  public String getType() { return type; }
  public String getEndpoint() { return endpoint; }
  public String getStatus() { return status; }
  public Long getHertzbeatMonitorId() { return hertzbeatMonitorId; }
  public String getSyncStatus() { return syncStatus == null ? "NOT_SYNCED" : syncStatus; }
  public String getSyncMessage() { return syncMessage; }
  public LocalDateTime getLastSyncedAt() { return lastSyncedAt; }
  public String getMonitoringMode() { return monitoringMode == null ? "LOCAL" : monitoringMode; }
  public String getMappingStatus() { return mappingStatus == null ? "NEVER_SYNCED" : mappingStatus; }
  public boolean isMonitoringStale() { return Boolean.TRUE.equals(monitoringStale); }
  public LocalDateTime getLastCollectedAt() { return lastCollectedAt; }
  public LocalDateTime getLastSyncAttemptAt() { return lastSyncAttemptAt; }
  public LocalDateTime getLastSuccessfulSyncAt() { return lastSuccessfulSyncAt; }
  public String getMonitoringError() { return monitoringError; }
  public boolean isMonitoringManaged() { return "HERTZBEAT".equals(getMonitoringMode()); }

  public void update(String name, String projectName, String environmentName, String type, String endpoint, String status) {
    this.name = name;
    this.projectName = projectName;
    this.environmentName = environmentName;
    this.type = type;
    this.endpoint = endpoint;
    if (!isMonitoringManaged()) this.status = status;
  }

  public void markSynced(Long monitorId, String message) {
    this.hertzbeatMonitorId = monitorId;
    this.monitoringMode = "HERTZBEAT";
    this.mappingStatus = "MANAGED";
    this.syncStatus = "SYNCED";
    this.syncMessage = message;
    this.lastSyncedAt = LocalDateTime.now();
  }

  public void markSyncFailed(String message) {
    this.syncStatus = "FAILED";
    this.syncMessage = message;
    this.lastSyncedAt = LocalDateTime.now();
  }

  public void applyMonitoringStatus(String status, LocalDateTime collectedAt, LocalDateTime syncedAt) {
    if (lastSyncAttemptAt != null && syncedAt.isBefore(lastSyncAttemptAt)) return;
    this.status = status;
    this.lastCollectedAt = collectedAt;
    this.lastSyncAttemptAt = syncedAt;
    this.lastSuccessfulSyncAt = syncedAt;
    this.monitoringStale = collectedAt == null || collectedAt.plusSeconds(180).isBefore(syncedAt);
    this.monitoringError = null;
  }

  public void markMonitoringSyncFailed(LocalDateTime attemptedAt, String message) {
    if (lastSyncAttemptAt != null && attemptedAt.isBefore(lastSyncAttemptAt)) return;
    this.lastSyncAttemptAt = attemptedAt;
    this.monitoringError = message;
    this.monitoringStale = lastSuccessfulSyncAt != null && lastSuccessfulSyncAt.plusSeconds(180).isBefore(attemptedAt);
    if (lastSuccessfulSyncAt == null) this.status = "UNKNOWN";
  }

  public void invalidateMonitoring(String message) {
    this.hertzbeatMonitorId = null;
    this.monitoringMode = "HERTZBEAT";
    this.mappingStatus = "INVALIDATED";
    this.status = "UNKNOWN";
    this.monitoringStale = true;
    this.monitoringError = message;
  }

  public void unbindMonitoring() {
    this.hertzbeatMonitorId = null;
    this.monitoringMode = "LOCAL";
    this.mappingStatus = "UNBOUND";
    this.monitoringStale = false;
    this.monitoringError = null;
  }

  public void applyInspectionStatus(String status) {
    if (!isMonitoringManaged()) this.status = status;
  }
}
