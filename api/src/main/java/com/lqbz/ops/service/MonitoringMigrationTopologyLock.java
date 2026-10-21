package com.lqbz.ops.service;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitoringMigrationTopologyLock {
  private final JdbcTemplate jdbc;

  public MonitoringMigrationTopologyLock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Transactional(propagation = Propagation.MANDATORY)
  public void acquireServices(java.util.List<Long> assetIds) {
    jdbc.execute((ConnectionCallback<Void>) connection -> {
      boolean postgres = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
      if (postgres) {
        try (var statement = connection.createStatement()) {
          statement.execute("SET LOCAL lock_timeout = '5s'");
        }
      }
      String sql = postgres ? "SELECT pg_advisory_xact_lock(?)" : "SELECT id FROM ops_asset WHERE id=? FOR UPDATE";
      try (var statement = connection.prepareStatement(sql)) {
        for (Long assetId : assetIds.stream().distinct().sorted().toList()) {
          statement.setLong(1, postgres ? assetId ^ 0x4f50530000000000L : assetId);
          statement.execute();
        }
      }
      return null;
    });
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void acquire() {
    jdbc.execute((ConnectionCallback<Void>) connection -> {
      if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
        throw new IllegalStateException("迁移准备和确认需要PostgreSQL事务锁，请使用正式数据库");
      }
      try (var statement = connection.createStatement()) {
        statement.execute("SET LOCAL lock_timeout = '5s'");
        statement.execute("LOCK TABLE ops_asset, ops_inspection_plan, ops_inspection_plan_binding, ops_project IN SHARE ROW EXCLUSIVE MODE");
      }
      return null;
    });
  }
}
