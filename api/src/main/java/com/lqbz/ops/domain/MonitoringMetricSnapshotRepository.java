package com.lqbz.ops.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoringMetricSnapshotRepository extends JpaRepository<MonitoringMetricSnapshot, Long> {

    Optional<MonitoringMetricSnapshot> findByAssetId(Long assetId);
}
