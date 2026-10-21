package com.lqbz.ops.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ops_inspection_profile")
public class InspectionProfile {
  @Id private Long assetId;
  @Version private Long version;
  private boolean enabled;
  private long revision;
  private String serviceType;
  @Column(columnDefinition = "text") private String encryptedSettings;
  @Column(columnDefinition = "text") private String checksJson;
  private LocalDateTime checkedAt;
  private String checkedEndpoint;
  protected InspectionProfile() {}
  public InspectionProfile(long assetId) { this.assetId = assetId; }
  public Long getAssetId() { return assetId; }
  public boolean isEnabled() { return enabled; }
  public long getRevision() { return revision; }
  public String getServiceType() { return serviceType; }
  public String getEncryptedSettings() { return encryptedSettings; }
  public String getChecksJson() { return checksJson; }
  public LocalDateTime getCheckedAt() { return checkedAt; }
  public String getCheckedEndpoint() { return checkedEndpoint; }
  public void configure(boolean enabled, String type, String encrypted) {
    this.enabled = enabled; this.serviceType = type; this.encryptedSettings = encrypted;
    this.revision++; this.checksJson = null; this.checkedAt = null; this.checkedEndpoint = null;
  }
  public void record(String checks, String endpoint) {
    this.checksJson = checks; this.checkedEndpoint = endpoint; this.checkedAt = LocalDateTime.now();
  }
}
