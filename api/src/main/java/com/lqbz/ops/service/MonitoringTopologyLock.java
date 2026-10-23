package com.lqbz.ops.service;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitoringTopologyLock {

    private final JdbcTemplate jdbc;

    public MonitoringTopologyLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquireServices(java.util.List<Long> assetIds) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            boolean postgres = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
            if (postgres) {
                try (lombok.val statement = connection.createStatement()) {
                    statement.execute("SET LOCAL lock_timeout = '5s'");
                }
            }
            String sql = postgres ? "SELECT pg_advisory_xact_lock(?)" : "SELECT id FROM ops_asset WHERE id=? FOR UPDATE";
            try (lombok.val statement = connection.prepareStatement(sql)) {
                for (Long assetId : assetIds.stream().distinct().sorted().collect(java.util.stream.Collectors.toList())) {
                    statement.setLong(1, postgres ? assetId ^ 0x4f50530000000000L : assetId);
                    statement.execute();
                }
            }
            return null;
        });
    }
}
