package com.lqbz.ops.inspection.probes;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.inspection.ProbeCheck;
import com.lqbz.ops.inspection.ProbeSettings;
import com.lqbz.ops.inspection.ProtocolProbe;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 由独立容器脚本显式运行；IT 后缀确保常规单元测试不依赖 Docker。
 */
@EnabledIfEnvironmentVariable(named = "PROBE_IT_ENABLED", matches = "true")
class RealProductProbeIT {

    @Test
    void postgresSelectOneWithCorrectPassword() throws Exception {
        database("POSTGRESQL", "POSTGRES", true);
    }

    @Test
    void postgresRejectsWrongPassword() throws Exception {
        database("POSTGRESQL", "POSTGRES", false);
    }

    @Test
    void mysqlSelectOneWithCorrectPassword() throws Exception {
        database("MYSQL", "MYSQL", true);
    }

    @Test
    void mysqlRejectsWrongPassword() throws Exception {
        database("MYSQL", "MYSQL", false);
    }

    @Test
    void redisAuthAndPingWithCorrectPassword() throws Exception {
        redis(true);
    }

    @Test
    void redisRejectsWrongPassword() throws Exception {
        redis(false);
    }

    @Test
    void rabbitAmqpWithCorrectPassword() throws Exception {
        rabbit(true);
    }

    @Test
    void rabbitRejectsWrongPassword() throws Exception {
        rabbit(false);
    }

    @Test
    void minioChecksNewDedicatedBucket() throws Exception {
        minio(true);
    }

    @Test
    void minioRejectsWrongSecretKey() throws Exception {
        minio(false);
    }

    @Test
    void kafkaReadsActualBrokerClusterMetadata() throws Exception {
        verify(new MessagingProtocolProbe(), "KAFKA", "KAFKA", com.lqbz.ops.compat.Java8.map("timeoutMillis", "5000"), com.lqbz.ops.compat.Java8.map(), true);
    }

    @Test
    void rocketReadsActualNameServerClusterInfo() throws Exception {
        // 独立 NameServer 没有 broker，读取空集群信息仍证明实际协议调用成功。
        verify(new MessagingProtocolProbe(), "ROCKETMQ", "ROCKET", com.lqbz.ops.compat.Java8.map("timeoutMillis", "5000"), com.lqbz.ops.compat.Java8.map(), true);
    }

    private void database(String type, String service, boolean correct) throws Exception {
        verify(new DatabaseProtocolProbe(), type, service, com.lqbz.ops.compat.Java8.map("timeoutMillis", "5000", "database", "inspection", "username", env("PROBE_IT_USER")), com.lqbz.ops.compat.Java8.map("password", password(correct)), correct);
    }

    private void redis(boolean correct) throws Exception {
        verify(new RedisProtocolProbe(), "REDIS", "REDIS", com.lqbz.ops.compat.Java8.map("timeoutMillis", "5000"), com.lqbz.ops.compat.Java8.map("password", password(correct)), correct);
    }

    private void rabbit(boolean correct) throws Exception {
        verify(new MessagingProtocolProbe(), "RABBITMQ", "RABBIT", com.lqbz.ops.compat.Java8.map("timeoutMillis", "5000", "username", env("PROBE_IT_USER"), "vhost", "/inspection"), com.lqbz.ops.compat.Java8.map("password", password(correct)), correct);
    }

    private void minio(boolean correct) throws Exception {
        verify(new MinioProtocolProbe(), "MINIO", "MINIO", com.lqbz.ops.compat.Java8.map("timeoutMillis", "5000", "bucket", env("PROBE_IT_BUCKET")), com.lqbz.ops.compat.Java8.map("accessKey", env("PROBE_IT_MINIO_USER"), "secretKey", password(correct)), correct);
    }

    private void verify(ProtocolProbe probe, String type, String service, Map<String, String> options, Map<String, String> secrets, boolean expected) throws Exception {
        String endpoint = System.getenv("PROBE_IT_" + service + "_ENDPOINT");
        assumeTrue(endpoint != null && !com.lqbz.ops.compat.Java8.isBlank(endpoint), "对应独立产品未就绪，详见脚本日志：" + service);
        lombok.val asset = new Asset("真实产品验收", "独立临时项目", "验收", type, endpoint, "UNKNOWN");
        lombok.val checks = probe.check(asset, new ProbeSettings(options, secrets));
        assertThat(checks).hasSize(1);
        ProbeCheck check = checks.get(0);
        // 错误认证返回固定错误分类；断言消息绝不拼接测试凭据。
        assertThat(check.message().contains(env("PROBE_IT_PASSWORD")) || check.message().contains(env("PROBE_IT_WRONG_PASSWORD"))).as("结果不能泄露测试凭据").isFalse();
        String diagnostic = "";
        if (expected && !check.passed() && (service.equals("POSTGRES") || service.equals("MYSQL"))) {
            diagnostic = jdbcDiagnostic(service, endpoint);
        }
        assertThat(check.passed()).as("%s 实际协议认证预期=%s，结果分类=%s%s", service, expected, check.message(), diagnostic).isEqualTo(expected);
    }

    private String jdbcDiagnostic(String service, String endpoint) {
        // 只报告异常类型与 SQLState，禁止诊断信息包含异常原文或连接凭据。
        String url = "jdbc:" + (service.equals("MYSQL") ? "mysql" : "postgresql") + "://" + endpoint + "/inspection";
        lombok.val properties = new java.util.Properties();
        properties.setProperty("user", env("PROBE_IT_USER"));
        properties.setProperty("password", password(true));
        properties.setProperty("connectTimeout", service.equals("MYSQL") ? "5000" : "5");
        properties.setProperty("socketTimeout", service.equals("MYSQL") ? "5000" : "5");
        properties.setProperty("sslmode", "disable");
        properties.setProperty("sslMode", "DISABLED");
        try (lombok.val connection = java.sql.DriverManager.getConnection(url, properties);
            lombok.val statement = connection.createStatement();
            lombok.val result = statement.executeQuery("SELECT 1")) {
            return ", 独立JDBC结果=" + (result.next() && result.getInt(1) == 1);
        } catch (Exception error) {
            lombok.val causes = new java.util.ArrayList<String>();
            for (Throwable cause = error; cause != null && causes.size() < 8; cause = cause.getCause()) {
                causes.add(cause.getClass().getSimpleName());
            }
            return ", 独立JDBC异常类型=" + String.join("/", causes) + ", 目标=" + endpoint + (error instanceof java.sql.SQLException ? ", SQLState=" + ((java.sql.SQLException) error).getSQLState() : "") + ", 线程中断=" + Thread.currentThread().isInterrupted();
        }
    }

    private String password(boolean correct) {
        return env(correct ? "PROBE_IT_PASSWORD" : "PROBE_IT_WRONG_PASSWORD");
    }

    private String env(String key) {
        String value = System.getenv(key);
        if (value == null || com.lqbz.ops.compat.Java8.isBlank(value))
            throw new IllegalStateException("缺少独立验收环境变量：" + key);
        return value;
    }
}
