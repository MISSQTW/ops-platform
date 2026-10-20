package com.lqbz.ops.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;

@Entity
@Table(name = "ops_project", uniqueConstraints = @UniqueConstraint(name = "uk_ops_project_name", columnNames = "name"))
public class Project {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  private String name;
  private String projectType;
  private String owner;
  private String contactPhone;
  private LocalDate maintenanceExpiresAt;
  private String description;
  private String status;

  protected Project() {}

  public Project(String name, String owner, String description, String status) {
    this(name, "业务系统", owner, null, null, description, status);
  }

  public Project(String name, String projectType, String owner, String contactPhone,
      LocalDate maintenanceExpiresAt, String description, String status) {
    this.name = name;
    this.projectType = projectType;
    this.owner = owner;
    this.contactPhone = contactPhone;
    this.maintenanceExpiresAt = maintenanceExpiresAt;
    this.description = description;
    this.status = status;
  }

  public Long getId() { return id; }
  public String getName() { return name; }
  public String getProjectType() { return projectType; }
  public String getOwner() { return owner; }
  public String getContactPhone() { return contactPhone; }
  public LocalDate getMaintenanceExpiresAt() { return maintenanceExpiresAt; }
  public String getDescription() { return description; }
  public String getStatus() { return status; }

  public void update(String name, String owner, String description, String status) {
    update(name, projectType, owner, contactPhone, maintenanceExpiresAt, description, status);
  }

  public void update(String name, String projectType, String owner, String contactPhone,
      LocalDate maintenanceExpiresAt, String description, String status) {
    this.name = name;
    this.projectType = projectType;
    this.owner = owner;
    this.contactPhone = contactPhone;
    this.maintenanceExpiresAt = maintenanceExpiresAt;
    this.description = description;
    this.status = status;
  }
}
