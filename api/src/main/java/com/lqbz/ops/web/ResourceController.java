package com.lqbz.ops.web;

import java.util.List;
import java.util.NoSuchElementException;
import com.lqbz.ops.domain.*;
import com.lqbz.ops.service.InspectionExecutionService;
import com.lqbz.ops.integration.MonitoringCleanupService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
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

  public ResourceController(ProjectRepository projects, AssetRepository assets, InspectionRepository inspections,
      AlertRepository alerts, AuditLogRepository audits, InspectionPlanRepository inspectionPlans,
      InspectionItemRepository inspectionItems, InspectionExecutionService inspectionExecutionService,
      MonitoringCleanupService monitoringCleanupService) {
    this.projects = projects;
    this.assets = assets;
    this.inspections = inspections;
    this.alerts = alerts;
    this.audits = audits;
    this.inspectionPlans = inspectionPlans;
    this.inspectionItems = inspectionItems;
    this.inspectionExecutionService = inspectionExecutionService;
    this.monitoringCleanupService = monitoringCleanupService;
  }

  @GetMapping("/projects") public List<Project> projects() { return projects.findAll(Sort.by("id")); }
  @GetMapping("/assets") public List<Asset> assets() { return assets.findAll(Sort.by("id")); }
  @GetMapping("/inspections") public List<Inspection> inspections() { return inspections.findAll(Sort.by(Sort.Direction.DESC, "executedAt")); }
  @GetMapping("/alerts") public List<Alert> alerts() { return alerts.findAll(Sort.by(Sort.Direction.DESC, "occurredAt")); }
  @GetMapping("/audits") public List<AuditLog> audits() { return audits.findAll(Sort.by(Sort.Direction.DESC, "operatedAt")); }
  @GetMapping("/inspection-plans") public List<InspectionPlan> inspectionPlans() { return inspectionPlans.findAll(Sort.by("id")); }
  @GetMapping("/inspections/{id}/items") public List<InspectionItem> inspectionItems(@PathVariable Long id) {
    if (!inspections.existsById(id)) throw new NoSuchElementException("巡检记录不存在");
    return inspectionItems.findByInspectionIdOrderById(id);
  }

  @PostMapping("/projects")
  @ResponseStatus(HttpStatus.CREATED)
  public Project createProject(@Valid @RequestBody ProjectRequest request) {
    Project project = projects.save(new Project(request.name(), request.owner(), request.description(), valueOr(request.status(), "RUNNING")));
    audits.save(new AuditLog("CREATE_PROJECT", "PROJECT", project.getName()));
    return project;
  }

  @PutMapping("/projects/{id}")
  @Transactional
  public Project updateProject(@PathVariable Long id, @Valid @RequestBody ProjectRequest request) {
    Project project = projects.findById(id).orElseThrow(() -> new NoSuchElementException("项目不存在"));
    project.update(request.name(), request.owner(), request.description(), valueOr(request.status(), project.getStatus()));
    audits.save(new AuditLog("UPDATE_PROJECT", "PROJECT", project.getName()));
    return project;
  }

  @DeleteMapping("/projects/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteProject(@PathVariable Long id) {
    Project project = projects.findById(id).orElseThrow(() -> new NoSuchElementException("项目不存在"));
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
  public InspectionPlan createInspectionPlan(@Valid @RequestBody InspectionPlanRequest request) {
    InspectionPlan plan = inspectionPlans.save(new InspectionPlan(request.name(), request.projectName(),
        request.scopeText(), request.scheduleText(), valueOr(request.status(), "ENABLED"), request.nextRunAt()));
    audits.save(new AuditLog("CREATE_INSPECTION_PLAN", "INSPECTION_PLAN", plan.getName()));
    return plan;
  }

  @PutMapping("/inspection-plans/{id}")
  @Transactional
  public InspectionPlan updateInspectionPlan(@PathVariable Long id,
      @Valid @RequestBody InspectionPlanRequest request) {
    InspectionPlan plan = inspectionPlans.findById(id)
        .orElseThrow(() -> new NoSuchElementException("巡检计划不存在"));
    plan.update(request.name(), request.projectName(), request.scopeText(), request.scheduleText(),
        valueOr(request.status(), plan.getStatus()), request.nextRunAt());
    audits.save(new AuditLog("UPDATE_INSPECTION_PLAN", "INSPECTION_PLAN", plan.getName()));
    return plan;
  }

  @DeleteMapping("/inspection-plans/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteInspectionPlan(@PathVariable Long id) {
    InspectionPlan plan = inspectionPlans.findById(id)
        .orElseThrow(() -> new NoSuchElementException("巡检计划不存在"));
    inspectionPlans.delete(plan);
    audits.save(new AuditLog("DELETE_INSPECTION_PLAN", "INSPECTION_PLAN", plan.getName()));
  }

  @PostMapping("/inspection-plans/{id}/execute")
  @ResponseStatus(HttpStatus.CREATED)
  public Inspection executeInspectionPlan(@PathVariable Long id) {
    Inspection inspection = inspectionExecutionService.execute(id);
    audits.save(new AuditLog("EXECUTE_INSPECTION_PLAN", "INSPECTION_PLAN", inspection.getName()));
    return inspection;
  }

  @PutMapping("/assets/{id}")
  @Transactional
  public Asset updateAsset(@PathVariable Long id, @Valid @RequestBody AssetRequest request) {
    Asset asset = assets.findById(id).orElseThrow(() -> new NoSuchElementException("资产不存在"));
    asset.update(request.name(), request.projectName(), valueOr(request.environmentName(), asset.getEnvironmentName()),
        request.type(), request.endpoint(), valueOr(request.status(), asset.getStatus()));
    audits.save(new AuditLog("UPDATE_ASSET", "ASSET", asset.getName()));
    return asset;
  }

  @DeleteMapping("/assets/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteAsset(@PathVariable Long id) {
    Asset asset = assets.findById(id).orElseThrow(() -> new NoSuchElementException("资产不存在"));
    monitoringCleanupService.cleanupOrSchedule(asset);
    assets.delete(asset);
    audits.save(new AuditLog("DELETE_ASSET", "ASSET", asset.getName()));
  }

  private String valueOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
