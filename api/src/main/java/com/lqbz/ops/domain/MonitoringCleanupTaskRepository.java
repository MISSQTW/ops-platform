package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoringCleanupTaskRepository extends JpaRepository<MonitoringCleanupTask, Long> {
  List<MonitoringCleanupTask> findByStatusAndNextAttemptAtBefore(String status, LocalDateTime nextAttemptAt);
  Optional<MonitoringCleanupTask> findByMonitorIdAndStatus(Long monitorId, String status);
}
