package com.lqbz.ops.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class InspectionExecutionServiceTest {
  private final InspectionExecutionService service = new InspectionExecutionService(null, null, null, null);

  @Test
  void 应支持任意分钟和小时间隔() {
    LocalDateTime now = LocalDateTime.of(2026, 10, 19, 9, 0);
    assertThat(service.calculateNextRun("每 7 分钟", now)).isEqualTo(now.plusMinutes(7));
    assertThat(service.calculateNextRun("每 3 小时", now)).isEqualTo(now.plusHours(3));
  }

  @Test
  void 应支持每天定时和Cron表达式() {
    LocalDateTime now = LocalDateTime.of(2026, 10, 19, 9, 5);
    assertThat(service.calculateNextRun("每天 10:30", now)).isEqualTo(LocalDateTime.of(2026, 10, 19, 10, 30));
    assertThat(service.calculateNextRun("Cron: 0 */10 * * * *", now)).isEqualTo(LocalDateTime.of(2026, 10, 19, 9, 10));
  }
}
