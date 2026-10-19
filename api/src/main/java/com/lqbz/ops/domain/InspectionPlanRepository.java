package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionPlanRepository extends JpaRepository<InspectionPlan, Long> {
  List<InspectionPlan> findByStatusAndNextRunAtLessThanEqualOrderByNextRunAt(String status, LocalDateTime nextRunAt);
}
