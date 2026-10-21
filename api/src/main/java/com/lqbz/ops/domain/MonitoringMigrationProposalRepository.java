package com.lqbz.ops.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MonitoringMigrationProposalRepository extends JpaRepository<MonitoringMigrationProposal, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select proposal from MonitoringMigrationProposal proposal where proposal.id = :id")
  Optional<MonitoringMigrationProposal> findByIdForUpdate(Long id);
}
