package com.lqbz.ops.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import com.lqbz.ops.domain.MonitoringSyncRun;
import com.lqbz.ops.domain.MonitoringSyncRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "ops.hertzbeat.sync-interval-ms=3600000")
@ActiveProfiles("test")
class MonitoringSyncTransactionTest {

    @Autowired
    private MonitoringSyncCoordinator coordinator;

    @Autowired
    private MonitoringSyncRunRepository runs;

    @MockBean
    private MonitoringProjectionService projection;

    @BeforeEach
    void 清理运行记录() {
        runs.deleteAll();
    }

    @Test
    void 投影事务回滚后失败运行记录仍应保留() {
        when(projection.sync(any())).thenThrow(new IllegalStateException("模拟投影失败"));
        MonitoringSyncRun result = coordinator.run("MANUAL");
        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(runs.findById(result.getId())).get().extracting(MonitoringSyncRun::getStatus, MonitoringSyncRun::getErrorSummary).containsExactly("FAILED", "模拟投影失败");
    }
}
