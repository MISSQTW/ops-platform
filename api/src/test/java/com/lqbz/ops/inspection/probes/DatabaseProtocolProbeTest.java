package com.lqbz.ops.inspection.probes;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.inspection.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DatabaseProtocolProbeTest {

    @Test
    void executesSelectOneWithVerifiedTlsAndClosesResources() throws Exception {
        lombok.val driver = new QueryDriver();
        DriverManager.registerDriver(driver);
        try {
            lombok.val result = new DatabaseProtocolProbe().check(new Asset("DB", "p", "e", "MYSQL", "127.0.0.1:16543", "UNKNOWN"), new ProbeSettings(com.lqbz.ops.compat.Java8.map("username", "alice", "database", "ops", "tls", "true", "timeoutMillis", "1000"), com.lqbz.ops.compat.Java8.map("password", "private-value")));
            assertThat(result).isNotEmpty().allMatch(ProbeCheck::passed);
            assertThat(driver.query).isEqualTo("SELECT 1");
            assertThat(driver.url).isEqualTo("jdbc:mysql://127.0.0.1:16543/ops");
            assertThat(driver.properties).containsEntry("user", "alice").containsEntry("password", "private-value").containsEntry("sslMode", "VERIFY_IDENTITY");
            assertThat(driver.closed).containsExactlyInAnyOrder("result", "statement", "connection");
            assertThat(result.toString()).doesNotContain("private-value");
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void queryFailureNeverExposesSqlExceptionText() throws Exception {
        lombok.val driver = new QueryDriver();
        driver.fail = true;
        DriverManager.registerDriver(driver);
        try {
            lombok.val result = new DatabaseProtocolProbe().check(new Asset("DB", "p", "e", "MYSQL", "127.0.0.1:16543", "UNKNOWN"), new ProbeSettings(com.lqbz.ops.compat.Java8.map("username", "alice", "database", "ops"), com.lqbz.ops.compat.Java8.map("password", "private-value")));
            assertThat(result).anyMatch(c -> !c.passed());
            assertThat(result.toString()).doesNotContain("private-value");
            assertThat(driver.closed).contains("connection", "statement");
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void postgresUsesVerifyFullAndBoundedTimeouts() throws Exception {
        lombok.val driver = new QueryDriver();
        DriverManager.registerDriver(driver);
        try {
            lombok.val result = new DatabaseProtocolProbe().check(new Asset("DB", "p", "e", "POSTGRESQL", "127.0.0.1:16543", "UNKNOWN"), new ProbeSettings(com.lqbz.ops.compat.Java8.map("username", "alice", "database", "ops", "tls", "true", "timeoutMillis", "1000"), com.lqbz.ops.compat.Java8.map("password", "private-value")));
            assertThat(result).allMatch(ProbeCheck::passed);
            assertThat(driver.url).isEqualTo("jdbc:postgresql://127.0.0.1:16543/ops");
            assertThat(driver.properties).containsEntry("sslmode", "verify-full").containsEntry("connectTimeout", "1").containsEntry("socketTimeout", "1");
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    // 伪驱动仅替代外部数据库边界，真实执行探测器的连接属性、SQL和资源关闭路径。
    static class QueryDriver implements Driver {

        String query, url;

        Properties properties;

        boolean fail;

        Set<String> closed = new HashSet<>();

        @SuppressWarnings("unchecked")
        <T> T object(Class<T> type, String kind) {
            return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { type }, (p, m, a) -> {
                switch(m.getName()) {
                    case "createStatement":
                        return object(Statement.class, "statement");
                    case "executeQuery":
                        {
                            query = (String) a[0];
                            if (fail)
                                throw new SQLException("private-value");
                            return object(ResultSet.class, "result");
                        }
                    case "next":
                        return true;
                    case "getInt":
                        return 1;
                    case "close":
                        {
                            closed.add(kind);
                            return null;
                        }
                    case "setQueryTimeout":
                    case "setNetworkTimeout":
                        return null;
                    case "isClosed":
                        return false;
                    default:
                        throw new UnsupportedOperationException(m.getName());
                }
            });
        }

        public Connection connect(String u, Properties p) {
            if (!acceptsURL(u))
                return null;
            url = u;
            properties = p;
            return object(Connection.class, "connection");
        }

        public boolean acceptsURL(String u) {
            return u.startsWith("jdbc:mysql://127.0.0.1:16543/") || u.startsWith("jdbc:postgresql://127.0.0.1:16543/");
        }

        public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) {
            return new DriverPropertyInfo[0];
        }

        public int getMajorVersion() {
            return 1;
        }

        public int getMinorVersion() {
            return 0;
        }

        public boolean jdbcCompliant() {
            return false;
        }

        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }
}
