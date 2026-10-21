package com.lqbz.ops.integration;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.MonitoringSyncRun;
import com.lqbz.ops.domain.MonitoringSyncRunRepository;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations")
public class MonitoringIntegrationController {
  private final HertzBeatClient hertzBeatClient;
  private final AssetMonitoringSyncService syncService;
  private final MonitoringSyncCoordinator monitoringSyncCoordinator;
  private final MonitoringSyncRunRepository monitoringRuns;
  private final AssetRepository assets;
  private final MonitoringMetricsService metricsService;

  public MonitoringIntegrationController(HertzBeatClient hertzBeatClient, AssetMonitoringSyncService syncService,
      MonitoringSyncCoordinator monitoringSyncCoordinator, MonitoringSyncRunRepository monitoringRuns,
      AssetRepository assets, MonitoringMetricsService metricsService) {
    this.hertzBeatClient = hertzBeatClient;
    this.syncService = syncService;
    this.monitoringSyncCoordinator = monitoringSyncCoordinator;
    this.monitoringRuns = monitoringRuns;
    this.assets = assets;
    this.metricsService = metricsService;
  }

  @GetMapping("/hertzbeat/status")
  public HertzBeatClient.Status hertzBeatStatus() { return hertzBeatClient.status(); }

  @PostMapping("/hertzbeat/assets/{id}/sync")
  public Asset syncAsset(@PathVariable Long id) {
    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.GONE,
        "服务同步入口已停用，请在巡检计划中保存或手动同步监控配置");
  }

  @PostMapping("/hertzbeat/assets/sync-all")
  public AssetMonitoringSyncService.SyncAllResult syncAllAssets() {
    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.GONE,
        "批量服务同步入口已停用，请使用巡检计划同步");
  }

  @PostMapping("/hertzbeat/monitoring/sync")
  public MonitoringSyncRun syncMonitoringData() { return monitoringSyncCoordinator.run("MANUAL"); }

  @GetMapping("/hertzbeat/monitoring/runs")
  public List<MonitoringSyncRun> monitoringRuns() { return monitoringRuns.findTop20ByOrderByStartedAtDesc(); }

  @GetMapping("/hertzbeat/assets/{id}/details")
  public MonitoringDetails monitoringDetails(@PathVariable Long id) throws Exception {
    Asset asset = assets.findById(id).orElseThrow(() -> new NoSuchElementException("资产不存在"));
    if (asset.getHertzbeatMonitorId() == null) {
      return new MonitoringDetails(asset, List.of(), List.of(), "尚未绑定 HertzBeat 监控", false, null, true);
    }
    MonitoringMetricsService.Result metrics = metricsService.load(asset);
    return new MonitoringDetails(asset, metrics.latest(), metrics.history(), metrics.message(), metrics.cached(),
        metrics.collectedAt(), metrics.stale());
  }

  public record MonitoringDetails(Asset asset, List<MonitoringMetric> latestMetrics,
                                  List<MonitoringMetric> history, String metricsMessage, boolean metricsCached,
                                  java.time.LocalDateTime metricsCollectedAt, boolean metricsStale) {}
}
