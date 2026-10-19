package com.lqbz.ops.integration;

import com.lqbz.ops.domain.MonitoringSyncLease;
import com.lqbz.ops.domain.MonitoringSyncLeaseRepository;
import com.lqbz.ops.domain.MonitoringSyncRun;
import com.lqbz.ops.domain.MonitoringSyncRun.MonitoringRunOutcome;
import java.time.LocalDateTime;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MonitoringSyncCoordinator {
  public static final String LEASE_KEY = "hertzbeat-observability-sync";
  private final MonitoringProjectionService projection;
  private final MonitoringSyncLeaseRepository leases;
  private final MonitoringRunRecorder recorder;
  private final MonitoringCleanupService cleanup;
  private final TransactionTemplate transactionTemplate;

  public MonitoringSyncCoordinator(MonitoringProjectionService projection, MonitoringSyncLeaseRepository leases,
      MonitoringRunRecorder recorder, MonitoringCleanupService cleanup, PlatformTransactionManager transactionManager) {
    this.projection = projection;
    this.leases = leases;
    this.recorder = recorder;
    this.cleanup = cleanup;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public MonitoringSyncRun run(String triggerType) {
    LocalDateTime startedAt = LocalDateTime.now();
    MonitoringSyncRun run = recorder.start(triggerType, startedAt);
    try {
      MonitoringRunOutcome outcome = transactionTemplate.execute(status -> executeProjection(startedAt));
      return recorder.finish(run.getId(), outcome);
    } catch (Exception exception) {
      String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
      return recorder.finish(run.getId(), new MonitoringRunOutcome(0, 0, java.util.List.of(message)));
    }
  }

  private MonitoringRunOutcome executeProjection(LocalDateTime startedAt) {
    ensureLease();
    leases.findLockedById(LEASE_KEY).orElseThrow();
    MonitoringProjectionService.ProjectionResult result = projection.sync(startedAt);
    java.util.List<String> errors = new java.util.ArrayList<>(result.errors());
    errors.addAll(cleanup.retryAndListPending(startedAt));
    return new MonitoringRunOutcome(result.assetsUpdated(), result.alertsUpdated(), errors);
  }

  @PostConstruct
  public void recoverInterruptedRuns() {
    recorder.recoverInterrupted();
  }

  @Scheduled(fixedDelayString = "${ops.hertzbeat.sync-interval-ms:60000}")
  public void scheduledSync() { run("SCHEDULED"); }

  private void ensureLease() {
    if (!leases.existsById(LEASE_KEY)) {
      try { leases.saveAndFlush(new MonitoringSyncLease(LEASE_KEY)); }
      catch (RuntimeException ignored) { /* 另一实例可能已创建固定租约行。 */ }
    }
  }
}
