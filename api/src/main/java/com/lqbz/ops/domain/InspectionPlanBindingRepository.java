package com.lqbz.ops.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;

public interface InspectionPlanBindingRepository extends JpaRepository<InspectionPlanBinding, Long> {
  List<InspectionPlanBinding> findByPlanIdOrderById(Long planId);
  Optional<InspectionPlanBinding> findByPlanIdAndAssetIdAndOwnershipStatusNot(
      Long planId, Long assetId, String ownershipStatus);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select binding from InspectionPlanBinding binding where binding.planId = :planId "
      + "and binding.assetId = :assetId and binding.ownershipStatus <> 'RELEASED'")
  Optional<InspectionPlanBinding> findCurrentForUpdate(Long planId, Long assetId);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select binding from InspectionPlanBinding binding where binding.assetId = :assetId "
      + "and binding.ownershipStatus <> 'RELEASED'")
  List<InspectionPlanBinding> findActiveHistoryForUpdate(Long assetId);
  Optional<InspectionPlanBinding> findByActiveOwnerKey(Long activeOwnerKey);
  boolean existsByPlanIdAndOwnershipStatusNot(Long planId, String ownershipStatus);
  void deleteByPlanId(Long planId);
}
