package com.lqbz.ops.integration;

import com.lqbz.ops.domain.AssetRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MonitoringDataMigration implements ApplicationRunner {

    private final AssetRepository assets;

    public MonitoringDataMigration(AssetRepository assets) {
        this.assets = assets;
    }

    @Override
    public void run(ApplicationArguments args) {
        assets.backfillVersion();
        assets.backfillManagedMonitoringMode();
        assets.backfillLocalMonitoringMode();
    }
}
