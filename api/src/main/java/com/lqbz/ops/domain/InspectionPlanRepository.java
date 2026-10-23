package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import javax.persistence.LockModeType;

public interface InspectionPlanRepository extends JpaRepository<InspectionPlan, Long> {

    List<InspectionPlan> findByStatusAndNextRunAtLessThanEqualOrderByNextRunAt(String status, LocalDateTime nextRunAt);

    boolean existsByProjectName(String projectName);

    boolean existsByProjectId(Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select plan from InspectionPlan plan where plan.id = :id")
    Optional<InspectionPlan> findByIdForUpdate(Long id);
}
