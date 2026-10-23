package com.lqbz.ops.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    long countByStatusNot(String status);

    long countByStatusIn(List<String> statuses);

    List<Asset> findByProjectNameOrderById(String projectName);

    List<Asset> findByMonitoringMode(String monitoringMode);

    boolean existsByProjectName(String projectName);

    long countByHertzbeatMonitorId(Long hertzbeatMonitorId);

    @Modifying
    @Transactional
    @Query("update Asset asset set asset.monitoringMode = 'HERTZBEAT', asset.mappingStatus = 'MANAGED' where asset.hertzbeatMonitorId is not null and asset.monitoringMode is null")
    int backfillManagedMonitoringMode();

    @Modifying
    @Transactional
    @Query("update Asset asset set asset.monitoringMode = 'LOCAL', asset.mappingStatus = 'NEVER_SYNCED' where asset.hertzbeatMonitorId is null and asset.monitoringMode is null")
    int backfillLocalMonitoringMode();

    @Modifying
    @Transactional
    @Query(value = "update ops_asset set version = 0 where version is null", nativeQuery = true)
    int backfillVersion();
}
