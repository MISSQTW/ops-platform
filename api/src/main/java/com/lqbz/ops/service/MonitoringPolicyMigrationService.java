package com.lqbz.ops.service;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.InspectionPlan;
import com.lqbz.ops.domain.InspectionPlanRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitoringPolicyMigrationService {
  private final AssetRepository assets;
  private final InspectionPlanRepository plans;
  private final com.lqbz.ops.domain.InspectionPlanBindingRepository bindings;
  private final com.lqbz.ops.integration.HertzBeatClient client;

  public MonitoringPolicyMigrationService(AssetRepository assets, InspectionPlanRepository plans,
      com.lqbz.ops.domain.InspectionPlanBindingRepository bindings,
      com.lqbz.ops.integration.HertzBeatClient client) {
    this.assets = assets;
    this.plans = plans;
    this.bindings = bindings;
    this.client = client;
  }

  @Transactional(readOnly = true)
  public MigrationReport dryRun() {
    return report(null);
  }

  @Transactional(readOnly = true)
  public MigrationItem inspect(Long assetId) {
    return report(assetId).items().stream().findFirst()
        .orElseThrow(() -> new java.util.NoSuchElementException("迁移报告中没有此服务"));
  }

  private MigrationReport report(Long selectedAssetId) {
    List<MigrationItem> items = new ArrayList<>();
    java.util.Map<Long, Long> monitorOwners = new java.util.HashMap<>();
    List<Asset> services = assets.findAll();
    List<InspectionPlan> legacyPlans = plans.findAll().stream()
        .filter(plan -> !plan.isMonitoringPolicy() && "ENABLED".equals(plan.getStatus())).toList();
    for (Asset asset : services) {
      if (asset.getHertzbeatMonitorId() != null) monitorOwners.merge(asset.getHertzbeatMonitorId(), 1L, Long::sum);
    }
    for (Asset asset : services) {
      if (selectedAssetId != null && !selectedAssetId.equals(asset.getId())) continue;
      List<InspectionPlan> matches = legacyPlans.stream()
          .filter(plan -> plan.getProjectName().equals(asset.getProjectName())).toList();
      Integer oldInterval = matches.size() == 1 ? fixedInterval(matches.getFirst().getScheduleText()) : null;
      var existingBinding = bindings.findByActiveOwnerKey(asset.getId());
      if (existingBinding.isPresent() && !"MIGRATION_PENDING".equals(existingBinding.get().getOwnershipStatus())) {
        items.add(item(asset, matches, oldInterval, null, "ALREADY_MANAGED", "ALREADY_MANAGED", "服务已有结构化巡检计划 binding"));
      } else if (asset.getHertzbeatMonitorId() != null && monitorOwners.getOrDefault(asset.getHertzbeatMonitorId(), 0L) > 1) {
        items.add(item(asset, matches, oldInterval, null, "MIGRATION_REQUIRED", "DUPLICATE_MONITOR",
            "多个服务引用同一 Monitor，禁止自动认领"));
      } else if (matches.size() > 1) {
        items.add(item(asset, matches, null, null, "MIGRATION_REQUIRED", "MULTIPLE_LEGACY_PLANS",
            "服务命中多个启用旧计划，必须人工确认归属"));
      } else if (asset.getHertzbeatMonitorId() != null) {
        try {
          var monitor = client.getMonitorConfig(asset.getHertzbeatMonitorId());
          if (monitor == null) throw new IllegalStateException("没有有效外部配置");
          String reason = matches.isEmpty() ? "UNIQUE_MONITOR_WITHOUT_PLAN"
              : oldInterval == null ? "UNSUPPORTED_LEGACY_SCHEDULE"
              : oldInterval != monitor.intervalSeconds() ? "FREQUENCY_CONFLICT" : "LEGACY_MONITOR_MATCHED";
          items.add(item(asset, matches, oldInterval, monitor.intervalSeconds(), "MIGRATION_REQUIRED", reason,
              "保留现有 Monitor 和执行方式，确认前不得覆盖外部频率"));
        } catch (com.lqbz.ops.integration.HertzBeatClient.MonitorNotFoundException exception) {
          items.add(item(asset, matches, oldInterval, null, "MIGRATION_REQUIRED", "MONITOR_MISSING",
              "外部 Monitor 已不存在，确认新计划后才允许重建"));
        } catch (Exception exception) {
          items.add(item(asset, matches, oldInterval, null, "MIGRATION_REQUIRED", "EXTERNAL_UNAVAILABLE",
              "保留现有 Monitor 和执行方式，暂时无法核实外部配置"));
        }
      } else {
        items.add(item(asset, matches, oldInterval, null, matches.isEmpty() ? "UNCONFIGURED" : "MIGRATION_REQUIRED",
            matches.isEmpty() ? "UNCONFIGURED" : "LEGACY_PLAN_WITHOUT_MONITOR",
            matches.isEmpty() ? "服务没有 Monitor 或结构化计划，不执行自动迁移" : "旧计划待确认转换，继续原本地调度"));
      }
    }
    return new MigrationReport(items);
  }

  public record MigrationReport(List<MigrationItem> items) {}
  private MigrationItem item(Asset asset, List<InspectionPlan> legacy, Integer oldInterval, Integer externalInterval,
      String status, String reason, String action) {
    return new MigrationItem(asset.getId(), asset.getName(), status, action, reason, asset.getProjectName(),
        asset.getHertzbeatMonitorId(), legacy.stream().map(InspectionPlan::getId).toList(), oldInterval, externalInterval);
  }

  private Integer fixedInterval(String schedule) {
    var matcher = java.util.regex.Pattern.compile("每\\s*(\\d+)\\s*(秒|分钟|小时)").matcher(schedule == null ? "" : schedule);
    if (!matcher.matches()) return null;
    try {
      int multiplier = "小时".equals(matcher.group(2)) ? 3600 : "分钟".equals(matcher.group(2)) ? 60 : 1;
      return Math.multiplyExact(Integer.parseInt(matcher.group(1)), multiplier);
    } catch (ArithmeticException | NumberFormatException exception) { return null; }
  }

  public record MigrationItem(Long serviceId, String serviceName, String status, String action, String reason,
      String projectName, Long monitorId, List<Long> legacyPlanIds, Integer legacyIntervalSeconds,
      Integer monitorIntervalSeconds) {}
}
