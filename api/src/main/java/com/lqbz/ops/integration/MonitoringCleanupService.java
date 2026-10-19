package com.lqbz.ops.integration;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.MonitoringCleanupTask;
import com.lqbz.ops.domain.MonitoringCleanupTaskRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitoringCleanupService {
  private final HertzBeatClient client;
  private final MonitoringCleanupTaskRepository tasks;

  public MonitoringCleanupService(HertzBeatClient client, MonitoringCleanupTaskRepository tasks) {
    this.client = client;
    this.tasks = tasks;
  }

  public void cleanupOrSchedule(Asset asset) {
    if (asset.getHertzbeatMonitorId() == null) return;
    try { client.deleteMonitor(asset.getHertzbeatMonitorId()); }
    catch (Exception exception) {
      if (tasks.findByMonitorIdAndStatus(asset.getHertzbeatMonitorId(), "PENDING").isEmpty()) {
        tasks.save(new MonitoringCleanupTask(asset.getHertzbeatMonitorId(), asset.getName(), message(exception)));
      }
    }
  }

  @Scheduled(fixedDelayString = "${ops.hertzbeat.cleanup-interval-ms:60000}")
  @Transactional
  public void retryPending() {
    retryAndListPending(LocalDateTime.now());
  }

  @Transactional
  public List<String> retryAndListPending(LocalDateTime now) {
    for (MonitoringCleanupTask task : tasks.findByStatusAndNextAttemptAtBefore("PENDING", now)) {
      try { client.deleteMonitor(task.getMonitorId()); task.complete(); }
      catch (Exception exception) { task.failed(message(exception)); }
    }
    return tasks.findByStatus("PENDING").stream()
        .map(task -> "监控 " + task.getMonitorId() + "（" + task.getAssetName() + "）清理失败：" + task.getLastError())
        .toList();
  }

  private String message(Exception exception) {
    return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
  }
}
