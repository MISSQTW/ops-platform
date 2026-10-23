package com.lqbz.ops.inspection.probes;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.inspection.ProbeCheck;
import com.lqbz.ops.inspection.ProbeSettings;
import com.lqbz.ops.inspection.ProtocolProbe;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.ClientConfig;
import org.apache.rocketmq.client.impl.MQClientAPIImpl;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.netty.NettyClientConfig;
import org.springframework.stereotype.Component;

@Component
public class MessagingProtocolProbe implements ProtocolProbe {

    @Override
    public boolean supports(String type) {
        return type != null && com.lqbz.ops.compat.Java8.set("KAFKA", "RABBITMQ", "ROCKETMQ").contains(type.toUpperCase(Locale.ROOT));
    }

    @Override
    public List<ProbeCheck> check(Asset asset, ProbeSettings settings) {
        long started = System.nanoTime();
        String name = com.lqbz.ops.compat.Java8.eval(() -> {
            switch(asset.getType().toUpperCase(Locale.ROOT)) {
                case "KAFKA":
                    return "Kafka 元数据";
                case "RABBITMQ":
                    return "RabbitMQ AMQP";
                case "ROCKETMQ":
                    return "RocketMQ NameServer";
                default:
                    return "消息队列协议";
            }
        });
        String target = "";
        boolean passed = false;
        String failure = name.equals("Kafka 元数据") ? "Kafka 元数据检查失败" : name + " 检查失败";
        String message = failure;
        try {
            int timeout = settings.timeoutMillis();
            if (timeout < 1000 || timeout > 10000 || !supports(asset.getType()))
                throw new IllegalArgumentException();
            URI address = parseAddress(asset.getEndpoint(), asset.getType(), settings.flag("tls"));
            target = address.getRawAuthority();
            switch (asset.getType().toUpperCase(Locale.ROOT)) {
                case "KAFKA": passed = kafka(target, settings, timeout); break;
                case "RABBITMQ": passed = rabbit(address, settings, timeout); break;
                case "ROCKETMQ": passed = rocket(target, settings, timeout); break;
                default: passed = false;
            }
            message = passed ? name + "检查通过" : failure;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 仅返回固定分类，协议库异常可能携带地址、认证配置或密钥。
        }
        return com.lqbz.ops.compat.Java8.list(new ProbeCheck("PROTOCOL", name, target, passed, message, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
    }

    private boolean kafka(String address, ProbeSettings settings, int timeout) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", address);
        properties.setProperty("request.timeout.ms", Integer.toString(timeout));
        properties.setProperty("default.api.timeout.ms", Integer.toString(timeout));
        properties.setProperty("socket.connection.setup.timeout.ms", Integer.toString(timeout));
        properties.setProperty("socket.connection.setup.timeout.max.ms", Integer.toString(timeout));
        String protocol = settings.text("securityProtocol", "PLAINTEXT");
        if (!com.lqbz.ops.compat.Java8.set("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL").contains(protocol))
            throw new IllegalArgumentException();
        properties.setProperty("security.protocol", protocol);
        if (protocol.startsWith("SASL_")) {
            String mechanism = settings.text("saslMechanism", "PLAIN");
            if (!com.lqbz.ops.compat.Java8.set("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512").contains(mechanism))
                throw new IllegalArgumentException();
            String user = settings.text("username", "");
            String password = settings.secret("password");
            if (com.lqbz.ops.compat.Java8.isBlank(user) || password.isEmpty())
                throw new IllegalArgumentException();
            String module = mechanism.equals("PLAIN") ? "org.apache.kafka.common.security.plain.PlainLoginModule" : "org.apache.kafka.common.security.scram.ScramLoginModule";
            properties.setProperty("sasl.mechanism", mechanism);
            properties.setProperty("sasl.jaas.config", module + " required username=\"" + jaas(user) + "\" password=\"" + jaas(password) + "\";");
        }
        Admin admin = createKafkaAdmin(properties);
        try {
            return !admin.describeCluster(new DescribeClusterOptions().timeoutMs(timeout)).nodes().get(timeout, TimeUnit.MILLISECONDS).isEmpty();
        } finally {
            // 不再为客户端后台线程的优雅关闭额外占用探测预算。
            admin.close(Duration.ZERO);
        }
    }

    private boolean rabbit(URI address, ProbeSettings settings, int timeout) throws Exception {
        ConnectionFactory factory = createRabbitFactory();
        factory.setHost(address.getHost());
        factory.setPort(address.getPort());
        factory.setVirtualHost(settings.text("vhost", "/"));
        factory.setUsername(settings.text("username", "guest"));
        factory.setPassword(settings.secret("password"));
        factory.setConnectionTimeout(timeout);
        factory.setHandshakeTimeout(timeout);
        factory.setChannelRpcTimeout(timeout);
        factory.setShutdownTimeout(timeout);
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        if (settings.flag("tls") || "amqps".equals(address.getScheme())) {
            factory.useSslProtocol(javax.net.ssl.SSLContext.getDefault());
            factory.enableHostnameVerification();
        }
        Connection connection = factory.newConnection();
        try {
            Channel channel = connection.createChannel();
            if (channel == null)
                return false;
            try {
                return true;
            } finally {
                channel.abort();
            }
        } finally {
            connection.abort();
        }
    }

    private boolean rocket(String address, ProbeSettings settings, int timeout) throws Exception {
        if (com.lqbz.ops.compat.Java8.isBlank(settings.secret("accessKey")) != com.lqbz.ops.compat.Java8.isBlank(settings.secret("secretKey")))
            throw new IllegalArgumentException();
        MQClientAPIImpl client = createRocketClient(settings);
        try {
            client.updateNameServerAddressList(address);
            client.start();
            return client.getBrokerClusterInfo(timeout) != null;
        } finally {
            client.shutdown();
        }
    }

    protected Admin createKafkaAdmin(Properties properties) {
        return Admin.create(properties);
    }

    protected ConnectionFactory createRabbitFactory() {
        return new ConnectionFactory();
    }

    protected MQClientAPIImpl createRocketClient(ProbeSettings settings) {
        NettyClientConfig netty = new NettyClientConfig();
        netty.setConnectTimeoutMillis(settings.timeoutMillis());
        netty.setClientWorkerThreads(1);
        netty.setClientCallbackExecutorThreads(1);
        ClientConfig config = new ClientConfig();
        config.setUseTLS(settings.flag("tls"));
        netty.setUseTLS(settings.flag("tls"));
        RPCHook hook = com.lqbz.ops.compat.Java8.isBlank(settings.secret("accessKey")) ? null : new AclClientRPCHook(new SessionCredentials(settings.secret("accessKey"), settings.secret("secretKey")));
        return new MQClientAPIImpl(netty, null, hook, config);
    }

    private String jaas(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private URI parseAddress(String endpoint, String type, boolean tls) {
        URI uri = URI.create(endpoint.contains("://") ? endpoint : "tcp://" + endpoint);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        Set<String> allowed = com.lqbz.ops.compat.Java8.eval(() -> {
            switch(type.toUpperCase(Locale.ROOT)) {
                case "KAFKA":
                    return com.lqbz.ops.compat.Java8.set("tcp", "kafka");
                case "RABBITMQ":
                    return com.lqbz.ops.compat.Java8.set("tcp", "amqp", "amqps");
                case "ROCKETMQ":
                    return com.lqbz.ops.compat.Java8.set("tcp", "rocketmq");
                default:
                    return com.lqbz.ops.compat.Java8.set();
            }
        });
        if (!allowed.contains(scheme) || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getQuery() != null || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")))
            throw new IllegalArgumentException();
        if (uri.getPort() == -1) {
            int port = com.lqbz.ops.compat.Java8.eval(() -> {
                switch(type.toUpperCase(Locale.ROOT)) {
                    case "KAFKA":
                        return 9092;
                    case "RABBITMQ":
                        return tls || scheme.equals("amqps") ? 5671 : 5672;
                    case "ROCKETMQ":
                        return 9876;
                    default:
                        throw new IllegalArgumentException();
                }
            });
            return URI.create(scheme + "://" + uri.getHost() + ":" + port);
        }
        return uri;
    }
}
