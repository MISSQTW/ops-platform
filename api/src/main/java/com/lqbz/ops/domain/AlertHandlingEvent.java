package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ops_alert_handling_event")
public class AlertHandlingEvent {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  private Long alertId;
  private String action;
  private String fromStatus;
  private String toStatus;
  private String operatorName;
  @Column(length = 1000)
  private String note;
  private LocalDateTime occurredAt;

  protected AlertHandlingEvent() {}

  public AlertHandlingEvent(Long alertId, String action, String fromStatus, String toStatus,
      String operatorName, String note, LocalDateTime occurredAt) {
    this.alertId = alertId;
    this.action = action;
    this.fromStatus = fromStatus;
    this.toStatus = toStatus;
    this.operatorName = operatorName;
    this.note = note;
    this.occurredAt = occurredAt;
  }

  public Long getId() { return id; }
  public Long getAlertId() { return alertId; }
  public String getAction() { return action; }
  public String getFromStatus() { return fromStatus; }
  public String getToStatus() { return toStatus; }
  public String getOperatorName() { return operatorName; }
  public String getNote() { return note; }
  public LocalDateTime getOccurredAt() { return occurredAt; }
}
