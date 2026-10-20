package com.lqbz.ops.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionPlanBindingRepository extends JpaRepository<InspectionPlanBinding, Long> {
  List<InspectionPlanBinding> findByPlanIdOrderById(Long planId);
  Optional<InspectionPlanBinding> findByActiveOwnerKey(Long activeOwnerKey);
  boolean existsByPlanIdAndOwnershipStatusNot(Long planId, String ownershipStatus);
  void deleteByPlanId(Long planId);
}
