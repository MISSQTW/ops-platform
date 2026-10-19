package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ops_inspection")
public class Inspection {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  private String name;
  private String projectName;
  private String status;
  private int passedChecks;
  private int totalChecks;
  private long durationSeconds;
  private LocalDateTime executedAt;

  protected Inspection() {}

  public Inspection(String name, String projectName, String status, int passedChecks, int totalChecks, long durationSeconds, LocalDateTime executedAt) {
    this.name = name;
    this.projectName = projectName;
    this.status = status;
    this.passedChecks = passedChecks;
    this.totalChecks = totalChecks;
    this.durationSeconds = durationSeconds;
    this.executedAt = executedAt;
  }

  public Long getId() { return id; }
  public String getName() { return name; }
  public String getProjectName() { return projectName; }
  public String getStatus() { return status; }
  public int getPassedChecks() { return passedChecks; }
  public int getTotalChecks() { return totalChecks; }
  public long getDurationSeconds() { return durationSeconds; }
  public LocalDateTime getExecutedAt() { return executedAt; }
}
