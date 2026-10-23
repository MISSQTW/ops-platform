package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "ops_monitoring_cleanup_task")
public class MonitoringCleanupTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long monitorId;

    private String assetName;

    private String status = "PENDING";

    private int attempts;

    private LocalDateTime nextAttemptAt;

    @Column(columnDefinition = "text")
    private String lastError;

    protected MonitoringCleanupTask() {
    }

    public MonitoringCleanupTask(Long monitorId, String assetName, String error) {
        this.monitorId = monitorId;
        this.assetName = assetName;
        this.lastError = error;
        this.nextAttemptAt = LocalDateTime.now().plusMinutes(1);
    }

    public void failed(String error) {
        attempts++;
        lastError = error;
        nextAttemptAt = LocalDateTime.now().plusMinutes(Math.min(30, attempts));
    }

    public void complete() {
        status = "COMPLETED";
        lastError = null;
    }

    public Long getMonitorId() {
        return monitorId;
    }

    public String getAssetName() {
        return assetName;
    }

    public String getStatus() {
        return status;
    }

    public String getLastError() {
        return lastError;
    }
}
