package com.lqbz.ops.inspection;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public class ProbeRunner {
  private final InspectionProfileService profiles;
  private final ProbeRegistry registry;
  private final Semaphore capacity = new Semaphore(16);
  private final ConcurrentHashMap<Long, Semaphore> serviceSlots = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  public ProbeRunner(InspectionProfileService profiles, ProbeRegistry registry) {
    this.profiles = profiles; this.registry = registry;
  }
  public List<ProbeCheck> run(long assetId, boolean requirePlan, boolean recordInspection) {
    return run(assetId, requirePlan, recordInspection, null);
  }
  public List<ProbeCheck> run(long assetId, boolean requirePlan, boolean recordInspection, Long authorizedRevision) {
    InspectionProfileService.Snapshot snapshot = profiles.snapshot(assetId, requirePlan);
    if (authorizedRevision != null && snapshot.revision() != authorizedRevision)
      return ProbeRegistry.failure(snapshot.asset(), "认证后配置版本已变化，本次请求已拒绝");
    Semaphore slot = serviceSlots.computeIfAbsent(assetId, ignored -> new Semaphore(1));
    if (!slot.tryAcquire()) return ProbeRegistry.failure(snapshot.asset(), "采集链路忙碌：该服务已有检查在执行");
    if (!capacity.tryAcquire()) {
      slot.release(); return ProbeRegistry.failure(snapshot.asset(), "采集链路过载，请稍后重试");
    }
    AtomicBoolean valid = new AtomicBoolean(true);
    Future<List<ProbeCheck>> future = executor.submit(() -> {
      try {
        List<ProbeCheck> checks = registry.check(snapshot.asset(), snapshot.settings());
        if (!valid.get() || Thread.currentThread().isInterrupted())
          return ProbeRegistry.failure(snapshot.asset(), "检查超过总预算，结果已丢弃");
        if (!profiles.finish(snapshot, checks, recordInspection, requirePlan))
          return ProbeRegistry.failure(snapshot.asset(), "执行期间配置或计划归属已变化，旧结果已丢弃");
        return checks;
      } finally {
        // 即使客户端超时，仍等真实任务退出再归还资源，避免失控线程突破并发上限。
        capacity.release(); slot.release();
      }
    });
    try { return future.get(30, TimeUnit.SECONDS); }
    catch (InterruptedException exception) {
      valid.set(false); future.cancel(true); Thread.currentThread().interrupt();
      return ProbeRegistry.failure(snapshot.asset(), "检查已中断");
    } catch (TimeoutException exception) {
      valid.set(false); future.cancel(true);
      return ProbeRegistry.failure(snapshot.asset(), "检查超过30秒总预算");
    } catch (ExecutionException exception) {
      return ProbeRegistry.failure(snapshot.asset(), "检查结果保存失败，请检查配置和数据库状态");
    }
  }
  @PreDestroy public void close() { executor.shutdownNow(); }
}
