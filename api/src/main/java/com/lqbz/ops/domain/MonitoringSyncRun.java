package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "ops_monitoring_sync_run")
public class MonitoringSyncRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String triggerType;

    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private int assetsUpdated;

    private int alertsUpdated;

    @Column(columnDefinition = "text")
    private String errorSummary;

    protected MonitoringSyncRun() {
    }

    public MonitoringSyncRun(String triggerType, LocalDateTime startedAt) {
        this.triggerType = triggerType;
        this.startedAt = startedAt;
        this.status = "RUNNING";
    }

    public void finish(MonitoringRunOutcome outcome, LocalDateTime finishedAt) {
        this.assetsUpdated = outcome.assetsUpdated();
        this.alertsUpdated = outcome.alertsUpdated();
        this.errorSummary = String.join("；", outcome.errors());
        this.status = outcome.errors().isEmpty() ? "SUCCESS" : outcome.assetsUpdated() + outcome.alertsUpdated() > 0 ? "PARTIAL" : "FAILED";
        this.finishedAt = finishedAt;
    }

    public void skip(LocalDateTime finishedAt) {
        this.status = "SKIPPED";
        this.finishedAt = finishedAt;
    }

    public void abandon(LocalDateTime finishedAt) {
        this.status = "FAILED";
        this.finishedAt = finishedAt;
        this.errorSummary = "服务重启，运行已中断";
    }

    public Long getId() {
        return id;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public int getAssetsUpdated() {
        return assetsUpdated;
    }

    public int getAlertsUpdated() {
        return alertsUpdated;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class MonitoringRunOutcome {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public MonitoringRunOutcome(@com.fasterxml.jackson.annotation.JsonProperty("assetsUpdated") int assetsUpdated, @com.fasterxml.jackson.annotation.JsonProperty("alertsUpdated") int alertsUpdated, @com.fasterxml.jackson.annotation.JsonProperty("errors") java.util.List<String> errors) {
            this.assetsUpdated = assetsUpdated;
            this.alertsUpdated = alertsUpdated;
            this.errors = errors;
        }

        private final int assetsUpdated;

        public int assetsUpdated() {
            return this.assetsUpdated;
        }

        private final int alertsUpdated;

        public int alertsUpdated() {
            return this.alertsUpdated;
        }

        private final java.util.List<String> errors;

        public java.util.List<String> errors() {
            return this.errors;
        }
    }
}
