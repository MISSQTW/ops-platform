package com.lqbz.ops.integration;

import com.lqbz.ops.domain.Asset;
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

  public MonitoringIntegrationController(HertzBeatClient hertzBeatClient, AssetMonitoringSyncService syncService) {
    this.hertzBeatClient = hertzBeatClient;
    this.syncService = syncService;
  }

  @GetMapping("/hertzbeat/status")
  public HertzBeatClient.Status hertzBeatStatus() { return hertzBeatClient.status(); }

  @PostMapping("/hertzbeat/assets/{id}/sync")
  public Asset syncAsset(@PathVariable Long id) { return syncService.sync(id); }

  @PostMapping("/hertzbeat/assets/sync-all")
  public AssetMonitoringSyncService.SyncAllResult syncAllAssets() { return syncService.syncAll(); }
}
