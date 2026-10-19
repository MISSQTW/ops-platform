package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ops_inspection_plan")
public class InspectionPlan {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  private String name;
  private String projectName;
  private String scopeText;
  private String scheduleText;
  private String status;
  private LocalDateTime nextRunAt;

  protected InspectionPlan() {}

  public InspectionPlan(String name, String projectName, String scopeText, String scheduleText, String status, LocalDateTime nextRunAt) {
    this.name = name;
    this.projectName = projectName;
    this.scopeText = scopeText;
    this.scheduleText = scheduleText;
    this.status = status;
    this.nextRunAt = nextRunAt;
  }

  public Long getId() { return id; }
  public String getName() { return name; }
  public String getProjectName() { return projectName; }
  public String getScopeText() { return scopeText; }
  public String getScheduleText() { return scheduleText; }
  public String getStatus() { return status; }
  public LocalDateTime getNextRunAt() { return nextRunAt; }

  public void update(String name, String projectName, String scopeText, String scheduleText, String status,
      LocalDateTime nextRunAt) {
    this.name = name;
    this.projectName = projectName;
    this.scopeText = scopeText;
    this.scheduleText = scheduleText;
    this.status = status;
    this.nextRunAt = nextRunAt;
  }

  public void scheduleNextRun(LocalDateTime nextRunAt) {
    this.nextRunAt = nextRunAt;
  }
}
