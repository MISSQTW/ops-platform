package com.lqbz.ops.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MonitoringMigrationProposalTest {
  @Test
  void 取消提案不能再次确认且重复取消幂等() {
    var proposal = new MonitoringMigrationProposal(1L, 2L, "snapshot", 60, "默认巡检");
    proposal.cancel();
    proposal.cancel();

    assertThat(proposal.getStatus()).isEqualTo("CANCELLED");
    assertThatThrownBy(() -> proposal.confirm("snapshot", 3L)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 确认必须基于同一份迁移现场且保留目标计划() {
    var proposal = new MonitoringMigrationProposal(1L, 2L, "snapshot-v1", 60, "默认巡检");

    assertThatThrownBy(() -> proposal.confirm("snapshot-v2", 3L))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("现场已变化");
    assertThat(proposal.getStatus()).isEqualTo("PENDING_CONFIRMATION");
    proposal.confirm("snapshot-v1", 3L);
    assertThat(proposal.getStatus()).isEqualTo("CONFIRMED");
    assertThat(proposal.getResultPlanId()).isEqualTo(3L);
    assertThat(proposal.getConfirmedAt()).isNotNull();
  }

  @Test
  void 重复确认只能返回同一计划不能改写结果() {
    var proposal = new MonitoringMigrationProposal(1L, 2L, "snapshot", 60, "默认巡检");
    proposal.confirm("snapshot", 3L);
    proposal.confirm("snapshot", 3L);

    assertThatThrownBy(() -> proposal.confirm("snapshot", 4L)).isInstanceOf(IllegalStateException.class);
    assertThat(proposal.getResultPlanId()).isEqualTo(3L);
  }
}
