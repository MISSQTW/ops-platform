package com.lqbz.ops.web;

import java.util.List;
import java.util.NoSuchElementException;
import com.lqbz.ops.domain.*;
import com.lqbz.ops.service.InspectionExecutionService;
import com.lqbz.ops.service.InspectionPlanPolicyService;
import com.lqbz.ops.service.InspectionPlanMonitoringSyncService;
import com.lqbz.ops.integration.MonitoringCleanupService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ResourceController {
  private final ProjectRepository projects;
  private final AssetRepository assets;
  private final InspectionRepository inspections;
  private final AlertRepository alerts;
  private final AuditLogRepository audits;
  private final InspectionPlanRepository inspectionPlans;
  private final InspectionItemRepository inspectionItems;
  private final InspectionExecutionService inspectionExecutionService;
  private final MonitoringCleanupService monitoringCleanupService;
  private final InspectionPlanPolicyService inspectionPlanPolicyService;
  private final InspectionPlanBindingRepository inspectionPlanBindings;
  private final InspectionPlanMonitoringSyncService inspectionPlanMonitoringSyncService;
  private final com.lqbz.ops.service.MonitoringTopologyLock topologyLock;

  public ResourceController(ProjectRepository projects, AssetRepository assets, InspectionRepository inspections,
      AlertRepository alerts, AuditLogRepository audits, InspectionPlanRepository inspectionPlans,
      InspectionItemRepository inspectionItems, InspectionExecutionService inspectionExecutionService,
      MonitoringCleanupService monitoringCleanupService, InspectionPlanPolicyService inspectionPlanPolicyService,
      InspectionPlanBindingRepository inspectionPlanBindings,
      InspectionPlanMonitoringSyncService inspectionPlanMonitoringSyncService,
      com.lqbz.ops.service.MonitoringTopologyLock topologyLock) {
    this.projects = projects;
    this.assets = assets;
    this.inspections = inspections;
    this.alerts = alerts;
    this.audits = audits;
    this.inspectionPlans = inspectionPlans;
    this.inspectionItems = inspectionItems;
    this.inspectionExecutionService = inspectionExecutionService;
    this.monitoringCleanupService = monitoringCleanupService;
    this.inspectionPlanPolicyService = inspectionPlanPolicyService;
    this.inspectionPlanBindings = inspectionPlanBindings;
    this.inspectionPlanMonitoringSyncService = inspectionPlanMonitoringSyncService;
    this.topologyLock = topologyLock;
  }

  @GetMapping("/projects") public List<Project> projects() { return projects.findAll(Sort.by("id")); }
  @GetMapping("/assets") public List<Asset> assets() { return assets.findAll(Sort.by("id")); }
  @GetMapping("/inspections") public List<Inspection> inspections() { return inspections.findAll(Sort.by(Sort.Direction.DESC, "executedAt")); }
  @GetMapping("/audits") public List<AuditLog> audits() { return audits.findAll(Sort.by(Sort.Direction.DESC, "operatedAt")); }
  @GetMapping("/inspection-plans") public List<InspectionPlanPolicyService.InspectionPlanView> inspectionPlans() {
    return inspectionPlanPolicyService.list();
  }
  @GetMapping("/inspection-plans/{id}")
  public InspectionPlanPolicyService.InspectionPlanView inspectionPlan(@PathVariable Long id) {
    return inspectionPlanPolicyService.get(id);
  }
  @GetMapping("/inspections/{id}/items") public List<InspectionItem> inspectionItems(@PathVariable Long id) {
    if (!inspections.existsById(id)) throw new NoSuchElementException("巡检记录不存在");
    return inspectionItems.findByInspectionIdOrderById(id);
  }

  @PostMapping("/projects")
  @ResponseStatus(HttpStatus.CREATED)
  public Project createProject(@Valid @RequestBody ProjectRequest request) {
    Project project = projects.save(new Project(request.name(), valueOr(request.projectType(), "业务系统"), request.owner(),
        request.contactPhone(), request.maintenanceExpiresAt(), request.description(), valueOr(request.status(), "RUNNING")));
    audits.save(new AuditLog("CREATE_PROJECT", "PROJECT", project.getName()));
    return project;
  }

  @PutMapping("/projects/{id}")
  @Transactional
  public Project updateProject(@PathVariable Long id, @Valid @RequestBody ProjectRequest request) {
    Project project = projects.findById(id).orElseThrow(() -> new NoSuchElementException("项目不存在"));
    if (!project.getName().equals(request.name())
        && (assets.existsByProjectName(project.getName())
            || inspectionPlans.existsByProjectName(project.getName())
            || inspectionPlans.existsByProjectId(project.getId()))) {
      throw new IllegalStateException("项目存在关联服务或巡检计划，不能修改项目名称");
    }
    project.update(request.name(), valueOr(request.projectType(), project.getProjectType()), request.owner(),
        request.contactPhone(), request.maintenanceExpiresAt(), request.description(), valueOr(request.status(), project.getStatus()));
    audits.save(new AuditLog("UPDATE_PROJECT", "PROJECT", project.getName()));
    return project;
  }

  @DeleteMapping("/projects/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public void deleteProject(@PathVariable Long id) {
    Project project = projects.findById(id).orElseThrow(() -> new NoSuchElementException("项目不存在"));
    if (assets.existsByProjectName(project.getName()) || inspectionPlans.existsByProjectName(project.getName())
        || inspectionPlans.existsByProjectId(project.getId())) {
      throw new IllegalStateException("项目存在关联服务或巡检计划，请先归档关联数据");
    }
    projects.delete(project);
    audits.save(new AuditLog("DELETE_PROJECT", "PROJECT", project.getName()));
  }

  @PostMapping("/assets")
  @ResponseStatus(HttpStatus.CREATED)
  public Asset createAsset(@Valid @RequestBody AssetRequest request) {
    Asset asset = assets.save(new Asset(request.name(), request.projectName(), valueOr(request.environmentName(), "生产"),
        request.type(), request.endpoint(), valueOr(request.status(), "UNKNOWN")));
    audits.save(new AuditLog("CREATE_ASSET", "ASSET", asset.getName()));
    return asset;
  }

  @PostMapping("/inspection-plans")
  @ResponseStatus(HttpStatus.CREATED)
  public InspectionPlanPolicyService.InspectionPlanView createInspectionPlan(
      @Valid @RequestBody InspectionPlanRequest request) {
    InspectionPlanPolicyService.InspectionPlanView plan = inspectionPlanPolicyService.create(request);
    audits.save(new AuditLog("CREATE_INSPECTION_PLAN", "INSPECTION_PLAN", plan.name()));
    return plan;
  }

  @PutMapping("/monitoring-plans/{id}")
  public InspectionPlanPolicyService.InspectionPlanView updateMonitoringPlan(@PathVariable Long id,
      @Valid @RequestBody InspectionPlanRequest request) {
    return inspectionPlanPolicyService.update(id, request);
  }

  @DeleteMapping("/monitoring-plans/{id}")
  public InspectionPlanPolicyService.InspectionPlanView deleteMonitoringPlan(@PathVariable Long id) {
    return inspectionPlanPolicyService.requestDelete(id);
  }

  @PostMapping("/monitoring-plans/{id}/sync")
  public InspectionPlanMonitoringSyncService.SyncSummary syncMonitoringPlan(@PathVariable Long id) {
    return inspectionPlanMonitoringSyncService.syncPlan(id);
  }

  @PostMapping("/monitoring-plans/sync-all")
  public InspectionPlanMonitoringSyncService.SyncAllSummary syncAllMonitoringPlans() {
    return inspectionPlanMonitoringSyncService.syncAllPlans();
  }

  @PostMapping("/inspection-plans/{id}/execute")
  @ResponseStatus(HttpStatus.CREATED)
  public Inspection executeInspectionPlan(@PathVariable Long id) {
    Inspection inspection = inspectionExecutionService.execute(id);
    audits.save(new AuditLog("EXECUTE_INSPECTION_PLAN", "INSPECTION_PLAN", inspection.getName()));
    return inspection;
  }

  @PostMapping("/assets/{id}/test-address")
  public InspectionExecutionService.AddressTestResult testAssetAddress(@PathVariable Long id) {
    return inspectionExecutionService.testAddress(id);
  }

  @PutMapping("/assets/{id}")
  public Asset updateAsset(@PathVariable Long id, @Valid @RequestBody AssetRequest request) {
    Asset asset = inspectionPlanPolicyService.updateAsset(id, request.name(), request.projectName(),
        request.environmentName(), request.type(), request.endpoint(), request.status());
    audits.save(new AuditLog("UPDATE_ASSET", "ASSET", asset.getName()));
    return asset;
  }

  @DeleteMapping("/assets/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  public void deleteAsset(@PathVariable Long id) {
    topologyLock.acquireServices(List.of(id));
    Asset asset = assets.findById(id).orElseThrow(() -> new NoSuchElementException("服务不存在"));
    if (inspectionPlanBindings.existsByAssetIdAndOwnershipStatusNot(id, "RELEASED")) {
      throw new IllegalStateException("服务已加入巡检计划，请先从计划中移除并完成监控清理");
    }
    monitoringCleanupService.cleanupOrSchedule(asset);
    assets.delete(asset);
    audits.save(new AuditLog("DELETE_ASSET", "ASSET", asset.getName()));
  }

  private String valueOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
