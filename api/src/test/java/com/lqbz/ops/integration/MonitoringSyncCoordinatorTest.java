package com.lqbz.ops.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lqbz.ops.domain.MonitoringSyncLease;
import com.lqbz.ops.domain.MonitoringSyncLeaseRepository;
import com.lqbz.ops.domain.MonitoringSyncRun;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class MonitoringSyncCoordinatorTest {
  @Test
  void 同步运行应先获取数据库悲观锁() {
    MonitoringProjectionService projection = mock(MonitoringProjectionService.class);
    MonitoringSyncLeaseRepository leases = mock(MonitoringSyncLeaseRepository.class);
    MonitoringRunRecorder recorder = mock(MonitoringRunRecorder.class);
    MonitoringSyncRun started = new MonitoringSyncRun("MANUAL", java.time.LocalDateTime.now());
    when(recorder.start(org.mockito.ArgumentMatchers.eq("MANUAL"), org.mockito.ArgumentMatchers.any())).thenReturn(started);
    when(recorder.finish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> { started.finish(invocation.getArgument(1), java.time.LocalDateTime.now()); return started; });
    when(leases.existsById(MonitoringSyncCoordinator.LEASE_KEY)).thenReturn(true);
    when(leases.findLockedById(MonitoringSyncCoordinator.LEASE_KEY))
        .thenReturn(Optional.of(new MonitoringSyncLease(MonitoringSyncCoordinator.LEASE_KEY)));
    when(projection.sync(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new MonitoringProjectionService.ProjectionResult(2, 1, List.of()));
    MonitoringSyncCoordinator coordinator = new MonitoringSyncCoordinator(projection, leases, recorder, new NoOpTransactionManager());

    MonitoringSyncRun run = coordinator.run("MANUAL");

    assertThat(run.getStatus()).isEqualTo("SUCCESS");
    verify(leases).findLockedById(MonitoringSyncCoordinator.LEASE_KEY);
  }

  private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
    @Override protected void doCommit(DefaultTransactionStatus status) {}
    @Override protected void doRollback(DefaultTransactionStatus status) {}
  }
}
