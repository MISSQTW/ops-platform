package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ops_asset")
public class Asset {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
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

  public void update(String name, String projectName, String environmentName, String type, String endpoint, String status) {
    this.name = name;
    this.projectName = projectName;
    this.environmentName = environmentName;
    this.type = type;
    this.endpoint = endpoint;
    this.status = status;
  }

  public void markSynced(Long monitorId, String message) {
    this.hertzbeatMonitorId = monitorId;
    this.syncStatus = "SYNCED";
    this.syncMessage = message;
    this.lastSyncedAt = LocalDateTime.now();
  }

  public void markSyncFailed(String message) {
    this.syncStatus = "FAILED";
    this.syncMessage = message;
    this.lastSyncedAt = LocalDateTime.now();
  }
}
