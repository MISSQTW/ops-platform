package com.lqbz.ops.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;

public interface MonitoringConfigTaskRepository extends JpaRepository<MonitoringConfigTask, Long> {
  List<MonitoringConfigTask> findByStatusOrderByCreatedAt(String status);
  List<MonitoringConfigTask> findByPlanIdOrderByCreatedAt(Long planId);
  List<MonitoringConfigTask> findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAt(
      List<String> statuses, java.time.LocalDateTime nextAttemptAt);
  List<MonitoringConfigTask> findByStatusAndStartedAtLessThanEqualOrderByCreatedAt(
      String status, java.time.LocalDateTime startedAt);
  boolean existsByPlanIdAndAssetIdAndDesiredVersionAndStatusIn(
      Long planId, Long assetId, Long desiredVersion, List<String> statuses);
  Optional<MonitoringConfigTask> findByPlanIdAndAssetIdAndDesiredVersionAndOperation(
      Long planId, Long assetId, Long desiredVersion, String operation);


  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select task from MonitoringConfigTask task where task.id = :id")
  Optional<MonitoringConfigTask> findByIdForUpdate(Long id);
}
