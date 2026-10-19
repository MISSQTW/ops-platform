package com.lqbz.ops.integration;

import com.lqbz.ops.domain.MonitoringSyncRun;
import com.lqbz.ops.domain.MonitoringSyncRun.MonitoringRunOutcome;
import com.lqbz.ops.domain.MonitoringSyncRunRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MonitoringRunRecorder {
  private final MonitoringSyncRunRepository runs;
  private final TransactionTemplate requiresNew;

  public MonitoringRunRecorder(MonitoringSyncRunRepository runs, PlatformTransactionManager transactionManager) {
    this.runs = runs;
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public MonitoringSyncRun start(String triggerType, LocalDateTime startedAt) {
    return requiresNew.execute(status -> runs.saveAndFlush(new MonitoringSyncRun(triggerType, startedAt)));
  }

  public MonitoringSyncRun finish(Long runId, MonitoringRunOutcome outcome) {
    return requiresNew.execute(status -> {
      MonitoringSyncRun run = runs.findById(runId).orElseThrow();
      run.finish(outcome, LocalDateTime.now());
      return runs.saveAndFlush(run);
    });
  }

  public void recoverInterrupted() {
    requiresNew.executeWithoutResult(status -> {
      for (MonitoringSyncRun run : runs.findByStatus("RUNNING")) {
        run.abandon(LocalDateTime.now());
        runs.save(run);
      }
    });
  }
}
