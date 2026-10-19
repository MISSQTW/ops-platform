package com.lqbz.ops.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoringSyncRunRepository extends JpaRepository<MonitoringSyncRun, Long> {
  List<MonitoringSyncRun> findTop20ByOrderByStartedAtDesc();
  List<MonitoringSyncRun> findByStatus(String status);
}
