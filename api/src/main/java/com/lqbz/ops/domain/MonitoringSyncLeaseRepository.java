package com.lqbz.ops.domain;

import javax.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonitoringSyncLeaseRepository extends JpaRepository<MonitoringSyncLease, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select lease from MonitoringSyncLease lease where lease.id = :id")
    Optional<MonitoringSyncLease> findLockedById(@Param("id") String id);
}
