package com.lqbz.ops.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionServiceTest {
  @Test
  void 登录令牌在退出后应立即失效() {
    SessionService sessions = new SessionService("admin", "secret", "平台管理员");
    String token = sessions.login("admin", "secret");
    assertThat(token).isNotBlank();
    assertThat(sessions.isValid(token)).isTrue();
    sessions.logout(token);
    assertThat(sessions.isValid(token)).isFalse();
  }

  @Test
  void 错误密码不应签发令牌() {
    SessionService sessions = new SessionService("admin", "secret", "平台管理员");
    assertThat(sessions.login("admin", "wrong")).isNull();
  }
}
