package com.lqbz.ops.inspection;

import static org.assertj.core.api.Assertions.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {
  private final String key = Base64.getEncoder().encodeToString(new byte[32]);

  @Test void 加密后不可见原文且相同输入使用不同随机数() {
    SecretCipher cipher = new SecretCipher(key);
    String first = cipher.encrypt("监控密码-secret");
    assertThat(first).doesNotContain("secret");
    assertThat(cipher.encrypt("监控密码-secret")).isNotEqualTo(first);
    assertThat(cipher.decrypt(first)).isEqualTo("监控密码-secret");
  }

  @Test void 篡改密文与错误密钥都必须拒绝() {
    SecretCipher cipher = new SecretCipher(key);
    String encrypted = cipher.encrypt("secret");
    byte[] other = new byte[32]; other[0] = 1;
    assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(other)).decrypt(encrypted))
        .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret");
    byte[] bytes = Base64.getDecoder().decode(encrypted); bytes[bytes.length - 1] ^= 1;
    assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test void 未设置主密钥时禁止保存但不影响旧功能启动() {
    SecretCipher cipher = new SecretCipher("");
    assertThat(cipher.available()).isFalse();
    assertThatThrownBy(() -> cipher.encrypt("secret")).isInstanceOf(IllegalStateException.class);
  }
}
