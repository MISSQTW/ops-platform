package com.lqbz.ops.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class MonitoringServiceContractTest {
  @Test
  void 未知类型不能因显式端口而绕过准入() {
    assertThatThrownBy(() -> MonitoringServiceContract.validate("UNKNOWN", "localhost:9000"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("没有有效监控契约");
  }

  @Test
  void HTTP与端口类型必须使用有效地址() {
    assertThat(MonitoringServiceContract.validate("HTTP", "https://example.com/health")).isEqualTo(MonitoringServiceContract.HTTP);
    assertThat(MonitoringServiceContract.validate("REDIS", "localhost:6379").defaultPort()).isEqualTo(6379);
    assertThatThrownBy(() -> MonitoringServiceContract.validate("HTTP", "localhost:80")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MonitoringServiceContract.validate("MYSQL", "localhost:70000")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MonitoringServiceContract.validate("SERVER", "localhost:0")).isInstanceOf(IllegalArgumentException.class);
  }
}
