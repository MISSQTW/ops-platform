package com.lqbz.ops.web;

import com.lqbz.ops.domain.MonitoringMigrationProposal;
import com.lqbz.ops.service.InspectionPlanPolicyService;
import com.lqbz.ops.service.MonitoringMigrationConfirmationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inspection-plans/migration-proposals")
public class MonitoringMigrationController {
  private final MonitoringMigrationConfirmationService migrations;

  public MonitoringMigrationController(MonitoringMigrationConfirmationService migrations) {
    this.migrations = migrations;
  }

  @PostMapping
  public MonitoringMigrationProposal prepare(@Valid @RequestBody PrepareRequest request) {
    return migrations.prepare(request.assetId(), request.projectId(), request.intervalSeconds());
  }

  @GetMapping
  public java.util.List<MonitoringMigrationProposal> list() {
    return migrations.list();
  }

  @PostMapping("/{id}/confirm")
  public InspectionPlanPolicyService.InspectionPlanView confirm(@PathVariable Long id) {
    return migrations.confirm(id);
  }

  @PostMapping("/{id}/cancel")
  public MonitoringMigrationProposal cancel(@PathVariable Long id) {
    return migrations.cancel(id);
  }

  public record PrepareRequest(@NotNull Long assetId, @NotNull Long projectId,
      @Min(10) @Max(86400) Integer intervalSeconds) {}
}
