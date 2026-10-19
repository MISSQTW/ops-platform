package com.lqbz.ops.integration;

import com.lqbz.ops.domain.Alert;
import com.lqbz.ops.domain.AlertRepository;
import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitoringProjectionService {
  private final AssetRepository assets;
  private final AlertRepository alerts;
  private final HertzBeatClient client;

  public MonitoringProjectionService(AssetRepository assets, AlertRepository alerts, HertzBeatClient client) {
    this.assets = assets;
    this.alerts = alerts;
    this.client = client;
  }

  @Transactional
  public ProjectionResult sync(LocalDateTime now) {
    List<String> errors = new ArrayList<>();
    List<Asset> managedAssets = assets.findByMonitoringMode("HERTZBEAT");
    int assetsUpdated = 0;
    for (Asset asset : managedAssets) {
      if (asset.getHertzbeatMonitorId() == null) continue;
      try {
        HertzBeatClient.MonitorStatus external = client.monitorStatus(asset.getHertzbeatMonitorId());
        if (external.disabled()) asset.applyMonitoringStatus("UNKNOWN", external.collectedAt(), now);
        else asset.applyMonitoringStatus(external.status(), external.collectedAt(), now);
        assetsUpdated++;
      } catch (HertzBeatClient.MonitorNotFoundException exception) {
        asset.invalidateMonitoring(message(exception));
        errors.add(asset.getName() + "：" + message(exception));
      } catch (Exception exception) {
        String message = message(exception);
        asset.markMonitoringSyncFailed(now, message);
        errors.add(asset.getName() + "：" + message);
      }
    }

    int alertsUpdated = 0;
    try {
      alertsUpdated += projectAlerts(client.alerts("firing"), managedAssets, false);
      alertsUpdated += projectAlerts(client.alerts("resolved"), managedAssets, true);
    } catch (Exception exception) {
      errors.add(message(exception));
    }
    return new ProjectionResult(assetsUpdated, alertsUpdated, errors);
  }

  private int projectAlerts(List<HertzBeatClient.ExternalAlert> externalAlerts, List<Asset> managedAssets, boolean resolved) {
    int updated = 0;
    for (HertzBeatClient.ExternalAlert external : externalAlerts) {
      Asset asset = managedAssets.stream()
          .filter(candidate -> candidate.getHertzbeatMonitorId() != null)
          .filter(candidate -> external.assetId() == null
              || java.util.Objects.equals(external.assetId(), candidate.getId()))
          .filter(candidate -> external.monitorId() > 0
              ? java.util.Objects.equals(external.monitorId(), candidate.getHertzbeatMonitorId())
              : external.assetId() != null && java.util.Objects.equals(external.assetId(), candidate.getId()))
          .findFirst().orElse(null);
      if (asset == null) continue;
      Alert alert = alerts.findByExternalSourceAndExternalAlertId("HERTZBEAT", external.externalId())
          .orElseGet(() -> Alert.external("HERTZBEAT", external.externalId(), asset.getHertzbeatMonitorId(),
              normalizeLevel(external.level()), asset.getProjectName(), asset.getName(), external.message(),
              external.occurredAt() == null ? LocalDateTime.now() : external.occurredAt()));
      if (resolved) alert.resolve(external.resolvedAt() == null ? LocalDateTime.now() : external.resolvedAt());
      else alert.fire(external.message(), external.occurredAt() == null ? LocalDateTime.now() : external.occurredAt());
      alerts.save(alert);
      updated++;
    }
    return updated;
  }

  private String normalizeLevel(String level) {
    if (level == null || level.isBlank()) return "WARNING";
    return level.toUpperCase();
  }

  private String message(Exception exception) {
    return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
  }

  public record ProjectionResult(int assetsUpdated, int alertsUpdated, List<String> errors) {}
}
