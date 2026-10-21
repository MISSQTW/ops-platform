package com.lqbz.ops.domain;

import java.net.URI;
import java.util.Locale;

public enum MonitoringServiceContract {
  HTTP(80), SERVER(22), MYSQL(3306), POSTGRESQL(5432), REDIS(6379),
  KAFKA(9092), RABBITMQ(5672), ROCKETMQ(9876), MINIO(9000);

  private final int defaultPort;

  MonitoringServiceContract(int defaultPort) { this.defaultPort = defaultPort; }

  public int defaultPort() { return defaultPort; }

  public static MonitoringServiceContract validate(String type, String address) {
    MonitoringServiceContract contract;
    try {
      contract = valueOf(type.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new IllegalArgumentException("服务类型没有有效监控契约：" + type);
    }
    if (address == null || address.isBlank()) throw new IllegalArgumentException("服务连接地址不能为空");
    URI endpoint = URI.create(address.contains("://") ? address : "tcp://" + address);
    if (endpoint.getHost() == null || endpoint.getHost().isBlank()
        || endpoint.getPort() == 0 || endpoint.getPort() > 65535) {
      throw new IllegalArgumentException("服务连接地址或端口无效");
    }
    if (contract == HTTP && !"http".equalsIgnoreCase(endpoint.getScheme())
        && !"https".equalsIgnoreCase(endpoint.getScheme())) {
      throw new IllegalArgumentException("HTTP服务必须使用http或https地址");
    }
    return contract;
  }
}
