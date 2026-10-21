package com.lqbz.ops.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.MonitoringMigrationProposal;
import com.lqbz.ops.domain.MonitoringMigrationProposalRepository;
import com.lqbz.ops.domain.MonitoringServiceContract;
import com.lqbz.ops.domain.ProjectRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitoringMigrationConfirmationService {
  private final AssetRepository assets;
  private final ProjectRepository projects;
  private final MonitoringMigrationProposalRepository proposals;
  private final MonitoringPolicyMigrationService reports;
  private final InspectionPlanPolicyService policies;
  private final ObjectMapper mapper;
  private final MonitoringMigrationTopologyLock topologyLock;

  public MonitoringMigrationConfirmationService(AssetRepository assets, ProjectRepository projects,
      MonitoringMigrationProposalRepository proposals, MonitoringPolicyMigrationService reports,
      InspectionPlanPolicyService policies, ObjectMapper mapper, MonitoringMigrationTopologyLock topologyLock) {
    this.assets = assets;
    this.projects = projects;
    this.proposals = proposals;
    this.reports = reports;
    this.policies = policies;
    this.mapper = mapper;
    this.topologyLock = topologyLock;
  }

  @Transactional
  public MonitoringMigrationProposal prepare(Long assetId, Long projectId, Integer selectedIntervalSeconds) {
    topologyLock.acquire();
    Asset asset = requireAsset(assetId, projectId);
    var item = eligibleItem(assetId);
    Integer interval = selectedIntervalSeconds;
    if (interval == null) {
      if ("FREQUENCY_CONFLICT".equals(item.reason()) || "UNSUPPORTED_LEGACY_SCHEDULE".equals(item.reason())) {
        throw new IllegalArgumentException("请明确选择迁移后的巡检频率");
      }
      interval = item.monitorIntervalSeconds() != null ? item.monitorIntervalSeconds() : item.legacyIntervalSeconds();
    }
    if (interval == null) throw new IllegalArgumentException("请明确选择迁移后的巡检频率");
    MonitoringMigrationProposal proposal = new MonitoringMigrationProposal(assetId, projectId,
        snapshot(asset, item), interval, asset.getName() + "迁移巡检");
    var plan = policies.prepareMigration(projectId, assetId, proposal.getPlanName(), interval);
    proposal.attachPreparedPlan(plan.id());
    return proposals.save(proposal);
  }

  @Transactional(readOnly = true)
  public List<MonitoringMigrationProposal> list() {
    return proposals.findAll(org.springframework.data.domain.Sort.by("id"));
  }

  @Transactional
  public InspectionPlanPolicyService.InspectionPlanView confirm(Long proposalId) {
    topologyLock.acquire();
    MonitoringMigrationProposal proposal = proposals.findByIdForUpdate(proposalId)
        .orElseThrow(() -> new NoSuchElementException("迁移提案不存在"));
    if ("CONFIRMED".equals(proposal.getStatus())) return policies.get(proposal.getResultPlanId());
    if (!"PENDING_CONFIRMATION".equals(proposal.getStatus())) throw new IllegalStateException("迁移提案已取消");
    Asset asset = requireAsset(proposal.getAssetId(), proposal.getProjectId());
    var item = eligibleItem(proposal.getAssetId());
    String currentSnapshot = snapshot(asset, item);
    if (!Objects.equals(proposal.getObservedSnapshot(), currentSnapshot)) {
      throw new IllegalStateException("迁移现场已变化，请重新生成提案");
    }
    var plan = policies.confirmMigration(proposal.getPreparedPlanId(), proposal.getAssetId());
    proposal.confirm(currentSnapshot, plan.id());
    return plan;
  }

  @Transactional
  public MonitoringMigrationProposal cancel(Long proposalId) {
    topologyLock.acquire();
    MonitoringMigrationProposal proposal = proposals.findByIdForUpdate(proposalId)
        .orElseThrow(() -> new NoSuchElementException("迁移提案不存在"));
    if ("CANCELLED".equals(proposal.getStatus())) return proposal;
    proposal.cancel();
    policies.cancelMigration(proposal.getPreparedPlanId(), proposal.getAssetId());
    return proposal;
  }

  private Asset requireAsset(Long assetId, Long projectId) {
    Asset asset = assets.findById(assetId).orElseThrow(() -> new NoSuchElementException("服务不存在"));
    var project = projects.findById(projectId).orElseThrow(() -> new NoSuchElementException("项目不存在"));
    if (!Objects.equals(project.getName(), asset.getProjectName())) throw new IllegalArgumentException("服务与项目不匹配");
    MonitoringServiceContract.validate(asset.getType(), asset.getEndpoint());
    return asset;
  }

  private MonitoringPolicyMigrationService.MigrationItem eligibleItem(Long assetId) {
    var item = reports.inspect(assetId);
    if (!Set.of("UNIQUE_MONITOR_WITHOUT_PLAN", "LEGACY_PLAN_WITHOUT_MONITOR", "FREQUENCY_CONFLICT",
        "LEGACY_MONITOR_MATCHED", "MONITOR_MISSING", "UNSUPPORTED_LEGACY_SCHEDULE").contains(item.reason())) {
      throw new IllegalStateException("服务尚不能安全迁移：" + item.reason());
    }
    return item;
  }

  private String snapshot(Asset asset, MonitoringPolicyMigrationService.MigrationItem item) {
    try {
      return mapper.writeValueAsString(new MigrationSnapshot(asset.getName(), asset.getProjectName(),
          asset.getType(), asset.getEndpoint(), asset.getHertzbeatMonitorId(), item.reason(),
          item.legacyPlanIds().stream().sorted().toList(), item.legacyIntervalSeconds(), item.monitorIntervalSeconds()));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("无法保存迁移现场", exception);
    }
  }

  private record MigrationSnapshot(String serviceName, String projectName, String type, String endpoint,
      Long monitorId, String reason, List<Long> legacyPlanIds, Integer legacyIntervalSeconds,
      Integer monitorIntervalSeconds) {}
}
