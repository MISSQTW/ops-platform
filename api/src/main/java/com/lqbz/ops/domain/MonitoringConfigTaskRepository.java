package com.lqbz.ops.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;

public interface MonitoringConfigTaskRepository extends JpaRepository<MonitoringConfigTask, Long> {
  List<MonitoringConfigTask> findByStatusOrderByCreatedAt(String status);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select task from MonitoringConfigTask task where task.id = :id")
  Optional<MonitoringConfigTask> findByIdForUpdate(Long id);
}
