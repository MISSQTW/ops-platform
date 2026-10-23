package com.lqbz.ops.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.lqbz.ops.domain.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ProjectRepository projects;

    private final AssetRepository assets;

    private final InspectionRepository inspections;

    private final AlertRepository alerts;

    public DashboardController(ProjectRepository projects, AssetRepository assets, InspectionRepository inspections, AlertRepository alerts) {
        this.projects = projects;
        this.assets = assets;
        this.inspections = inspections;
        this.alerts = alerts;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        long passed = inspections.findAll().stream().filter(item -> "PASSED".equals(item.getStatus())).count();
        long total = inspections.count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("projectCount", projects.count());
        summary.put("assetCount", assets.count());
        summary.put("abnormalAssetCount", assets.countByStatusIn(com.lqbz.ops.compat.Java8.list("DOWN", "CRITICAL", "FAILED")));
        summary.put("openAlertCount", alerts.countByStatus("OPEN"));
        summary.put("inspectionPassRate", total == 0 ? 100 : Math.round(passed * 100.0 / total));
        return summary;
    }
}
