package com.lqbz.ops.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotSerializeTransactionException;

class ApiExceptionHandlerTest {
  @Test
  void 序列化冲突应返回可重试提示() {
    Map<String, Object> result = new ApiExceptionHandler()
        .serializationConflict(new CannotSerializeTransactionException("并发事务冲突"));

    assertThat(result.get("message")).isEqualTo("数据正在被并发修改，请重试");
  }
}
