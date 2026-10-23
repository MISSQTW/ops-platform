package com.lqbz.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.lqbz.ops.domain.AlertRepository;
import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.InspectionRepository;
import com.lqbz.ops.domain.ProjectRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardStatusSemanticsTest {

    @Test
    void 只有明确不可用状态才应计入异常服务() {
        ProjectRepository projects = mock(ProjectRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        InspectionRepository inspections = mock(InspectionRepository.class);
        AlertRepository alerts = mock(AlertRepository.class);
        when(assets.countByStatusIn(com.lqbz.ops.compat.Java8.list("DOWN", "CRITICAL", "FAILED"))).thenReturn(1L);
        Map<String, Object> summary = new DashboardController(projects, assets, inspections, alerts).summary();
        assertThat(summary.get("abnormalAssetCount")).isEqualTo(1L);
    }
}
