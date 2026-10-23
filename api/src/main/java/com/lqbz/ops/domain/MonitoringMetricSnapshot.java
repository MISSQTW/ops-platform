package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

@Entity
@Table(name = "ops_monitoring_metric_snapshot", uniqueConstraints = @UniqueConstraint(columnNames = "assetId"))
public class MonitoringMetricSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long assetId;

    @Column(columnDefinition = "text")
    private String latestJson;

    @Column(columnDefinition = "text")
    private String historyJson;

    private LocalDateTime collectedAt;

    private LocalDateTime syncedAt;

    protected MonitoringMetricSnapshot() {
    }

    public MonitoringMetricSnapshot(Long assetId) {
        this.assetId = assetId;
    }

    public void update(String latestJson, String historyJson, LocalDateTime collectedAt, LocalDateTime syncedAt) {
        this.latestJson = latestJson;
        this.historyJson = historyJson;
        this.collectedAt = collectedAt;
        this.syncedAt = syncedAt;
    }

    public String getLatestJson() {
        return latestJson;
    }

    public String getHistoryJson() {
        return historyJson;
    }

    public LocalDateTime getCollectedAt() {
        return collectedAt;
    }

    public LocalDateTime getSyncedAt() {
        return syncedAt;
    }
}
