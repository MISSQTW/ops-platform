package com.lqbz.ops.integration;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.lqbz.ops.domain.AssetRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class MonitoringDataMigrationTest {
  @Test
  void 启动时应先回填版本再恢复监控模式() throws Exception {
    AssetRepository assets = mock(AssetRepository.class);

    new MonitoringDataMigration(assets).run(null);

    InOrder order = inOrder(assets);
    order.verify(assets).backfillVersion();
    order.verify(assets).backfillManagedMonitoringMode();
    order.verify(assets).backfillLocalMonitoringMode();
  }
}
