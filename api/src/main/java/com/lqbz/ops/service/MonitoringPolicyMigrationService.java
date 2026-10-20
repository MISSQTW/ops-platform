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

  public MonitoringPolicyMigrationService(AssetRepository assets, InspectionPlanRepository plans,
      com.lqbz.ops.domain.InspectionPlanBindingRepository bindings) {
    this.assets = assets;
    this.plans = plans;
    this.bindings = bindings;
  }

  @Transactional(readOnly = true)
  public MigrationReport dryRun() {
    List<MigrationItem> items = new ArrayList<>();
    java.util.Map<Long, Long> monitorOwners = new java.util.HashMap<>();
    for (Asset asset : assets.findAll()) {
      if (asset.getHertzbeatMonitorId() != null) monitorOwners.merge(asset.getHertzbeatMonitorId(), 1L, Long::sum);
    }
    for (Asset asset : assets.findAll()) {
      var existingBinding = bindings.findByActiveOwnerKey(asset.getId());
      if (existingBinding.isPresent()) {
        items.add(new MigrationItem(asset.getId(), asset.getName(), "ALREADY_MANAGED", "服务已有结构化巡检计划 binding"));
      } else if (asset.getHertzbeatMonitorId() != null && monitorOwners.getOrDefault(asset.getHertzbeatMonitorId(), 0L) > 1) {
        items.add(new MigrationItem(asset.getId(), asset.getName(), "MIGRATION_REQUIRED",
            "多个服务引用同一 Monitor，禁止自动认领"));
      } else if (asset.getHertzbeatMonitorId() != null) {
        items.add(new MigrationItem(asset.getId(), asset.getName(), "MIGRATION_REQUIRED",
            "保留现有 Monitor 和执行方式，必须由确认批次指定目标计划"));
      } else {
        items.add(new MigrationItem(asset.getId(), asset.getName(), "UNCONFIGURED",
            "服务没有 Monitor 或结构化计划，不执行自动迁移"));
      }
    }
    return new MigrationReport(items);
  }

  public record MigrationReport(List<MigrationItem> items) {}
  public record MigrationItem(Long serviceId, String serviceName, String status, String action) {}
}
