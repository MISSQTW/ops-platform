package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ops_alert")
public class Alert {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  private String level;
  private String projectName;
  private String targetName;
  private String message;
  private String status;
  private LocalDateTime occurredAt;

  protected Alert() {}

  public Alert(String level, String projectName, String targetName, String message, String status, LocalDateTime occurredAt) {
    this.level = level;
    this.projectName = projectName;
    this.targetName = targetName;
    this.message = message;
    this.status = status;
    this.occurredAt = occurredAt;
  }

  public Long getId() { return id; }
  public String getLevel() { return level; }
  public String getProjectName() { return projectName; }
  public String getTargetName() { return targetName; }
  public String getMessage() { return message; }
  public String getStatus() { return status; }
  public LocalDateTime getOccurredAt() { return occurredAt; }
}
