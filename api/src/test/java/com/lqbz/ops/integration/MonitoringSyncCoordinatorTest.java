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
    MonitoringCleanupService cleanup = mock(MonitoringCleanupService.class);
    MonitoringSyncRun started = new MonitoringSyncRun("MANUAL", java.time.LocalDateTime.now());
    when(recorder.start(org.mockito.ArgumentMatchers.eq("MANUAL"), org.mockito.ArgumentMatchers.any())).thenReturn(started);
    when(recorder.finish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> { started.finish(invocation.getArgument(1), java.time.LocalDateTime.now()); return started; });
    when(leases.existsById(MonitoringSyncCoordinator.LEASE_KEY)).thenReturn(true);
    when(leases.findLockedById(MonitoringSyncCoordinator.LEASE_KEY))
        .thenReturn(Optional.of(new MonitoringSyncLease(MonitoringSyncCoordinator.LEASE_KEY)));
    when(projection.sync(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new MonitoringProjectionService.ProjectionResult(2, 1, List.of()));
    when(cleanup.retryAndListPending(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    MonitoringSyncCoordinator coordinator = new MonitoringSyncCoordinator(projection, leases, recorder, cleanup,
        new NoOpTransactionManager());

    MonitoringSyncRun run = coordinator.run("MANUAL");

    assertThat(run.getStatus()).isEqualTo("SUCCESS");
    verify(leases).findLockedById(MonitoringSyncCoordinator.LEASE_KEY);
  }

  @Test
  void 待清理失败应进入本次同步运行错误摘要() {
    MonitoringProjectionService projection = mock(MonitoringProjectionService.class);
    MonitoringSyncLeaseRepository leases = mock(MonitoringSyncLeaseRepository.class);
    MonitoringRunRecorder recorder = mock(MonitoringRunRecorder.class);
    MonitoringCleanupService cleanup = mock(MonitoringCleanupService.class);
    MonitoringSyncRun started = new MonitoringSyncRun("MANUAL", java.time.LocalDateTime.now());
    when(recorder.start(org.mockito.ArgumentMatchers.eq("MANUAL"), org.mockito.ArgumentMatchers.any())).thenReturn(started);
    when(recorder.finish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> { started.finish(invocation.getArgument(1), java.time.LocalDateTime.now()); return started; });
    when(leases.existsById(MonitoringSyncCoordinator.LEASE_KEY)).thenReturn(true);
    when(leases.findLockedById(MonitoringSyncCoordinator.LEASE_KEY))
        .thenReturn(Optional.of(new MonitoringSyncLease(MonitoringSyncCoordinator.LEASE_KEY)));
    when(projection.sync(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new MonitoringProjectionService.ProjectionResult(1, 0, List.of()));
    when(cleanup.retryAndListPending(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of("监控 321 清理失败：连接超时"));
    MonitoringSyncCoordinator coordinator = new MonitoringSyncCoordinator(projection, leases, recorder, cleanup,
        new NoOpTransactionManager());

    MonitoringSyncRun run = coordinator.run("MANUAL");

    assertThat(run.getStatus()).isEqualTo("PARTIAL");
    assertThat(run.getErrorSummary()).contains("监控 321 清理失败");
  }

  private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
    @Override protected void doCommit(DefaultTransactionStatus status) {}
    @Override protected void doRollback(DefaultTransactionStatus status) {}
  }
}
