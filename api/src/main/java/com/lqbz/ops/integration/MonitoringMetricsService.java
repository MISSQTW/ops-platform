package com.lqbz.ops.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.MonitoringMetricSnapshot;
import com.lqbz.ops.domain.MonitoringMetricSnapshotRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitoringMetricsService {

    private static final TypeReference<List<MonitoringMetric>> METRIC_LIST = new TypeReference<List<MonitoringMetric>>() {
    };

    private final HertzBeatClient client;

    private final MonitoringMetricSnapshotRepository snapshots;

    private final ObjectMapper objectMapper;

    public MonitoringMetricsService(HertzBeatClient client, MonitoringMetricSnapshotRepository snapshots, ObjectMapper objectMapper) {
        this.client = client;
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Result load(Asset asset) {
        try {
            HertzBeatClient.MetricTarget target = client.metricTarget(asset);
            HertzBeatClient.MetricResult metrics = client.metrics(asset.getHertzbeatMonitorId(), target.instance(), target.app());
            MonitoringMetricSnapshot snapshot = snapshots.findByAssetId(asset.getId()).orElseGet(() -> new MonitoringMetricSnapshot(asset.getId()));
            snapshot.update(write(metrics.latest()), write(metrics.history()), metrics.collectedAt(), LocalDateTime.now());
            snapshots.save(snapshot);
            String message = metrics.errors().isEmpty() ? null : String.join("；", metrics.errors());
            return new Result(metrics.latest(), metrics.history(), message, false, metrics.collectedAt(), isStale(metrics.collectedAt()));
        } catch (Exception exception) {
            MonitoringMetricSnapshot snapshot = snapshots.findByAssetId(asset.getId()).orElse(null);
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            if (snapshot == null)
                return new Result(com.lqbz.ops.compat.Java8.list(), com.lqbz.ops.compat.Java8.list(), message, false, null, true);
            return new Result(read(snapshot.getLatestJson()), read(snapshot.getHistoryJson()), "HertzBeat 暂不可用，展示最后有效指标：" + message, true, snapshot.getCollectedAt(), isStale(snapshot.getCollectedAt()));
        }
    }

    private String write(List<MonitoringMetric> metrics) throws Exception {
        return objectMapper.writeValueAsString(metrics);
    }

    private List<MonitoringMetric> read(String json) {
        try {
            return objectMapper.readValue(json, METRIC_LIST);
        } catch (Exception ignored) {
            return com.lqbz.ops.compat.Java8.list();
        }
    }

    private boolean isStale(LocalDateTime collectedAt) {
        return collectedAt == null || collectedAt.plusSeconds(180).isBefore(LocalDateTime.now());
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class Result {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public Result(@com.fasterxml.jackson.annotation.JsonProperty("latest") List<MonitoringMetric> latest, @com.fasterxml.jackson.annotation.JsonProperty("history") List<MonitoringMetric> history, @com.fasterxml.jackson.annotation.JsonProperty("message") String message, @com.fasterxml.jackson.annotation.JsonProperty("cached") boolean cached, @com.fasterxml.jackson.annotation.JsonProperty("collectedAt") LocalDateTime collectedAt, @com.fasterxml.jackson.annotation.JsonProperty("stale") boolean stale) {
            this.latest = latest;
            this.history = history;
            this.message = message;
            this.cached = cached;
            this.collectedAt = collectedAt;
            this.stale = stale;
        }

        private final List<MonitoringMetric> latest;

        public List<MonitoringMetric> latest() {
            return this.latest;
        }

        private final List<MonitoringMetric> history;

        public List<MonitoringMetric> history() {
            return this.history;
        }

        private final String message;

        public String message() {
            return this.message;
        }

        private final boolean cached;

        public boolean cached() {
            return this.cached;
        }

        private final LocalDateTime collectedAt;

        public LocalDateTime collectedAt() {
            return this.collectedAt;
        }

        private final boolean stale;

        public boolean stale() {
            return this.stale;
        }
    }
}
