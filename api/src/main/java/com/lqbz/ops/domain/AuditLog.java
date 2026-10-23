package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "ops_audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String action;

    private String resourceType;

    private String resourceName;

    private String operatorName;

    private LocalDateTime operatedAt;

    protected AuditLog() {
    }

    public AuditLog(String action, String resourceType, String resourceName) {
        this.action = action;
        this.resourceType = resourceType;
        this.resourceName = resourceName;
        this.operatorName = "平台管理员";
        this.operatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceName() {
        return resourceName;
    }

    public String getOperatorName() {
        return operatorName;
    }

    public LocalDateTime getOperatedAt() {
        return operatedAt;
    }
}
