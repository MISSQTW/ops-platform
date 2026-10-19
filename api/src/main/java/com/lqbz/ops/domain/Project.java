package com.lqbz.ops.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ops_project")
public class Project {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  private String name;
  private String owner;
  private String description;
  private String status;

  protected Project() {}

  public Project(String name, String owner, String description, String status) {
    this.name = name;
    this.owner = owner;
    this.description = description;
    this.status = status;
  }

  public Long getId() { return id; }
  public String getName() { return name; }
  public String getOwner() { return owner; }
  public String getDescription() { return description; }
  public String getStatus() { return status; }

  public void update(String name, String owner, String description, String status) {
    this.name = name;
    this.owner = owner;
    this.description = description;
    this.status = status;
  }
}
