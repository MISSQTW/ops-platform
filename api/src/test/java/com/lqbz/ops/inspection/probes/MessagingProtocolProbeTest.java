package com.lqbz.ops.inspection.probes;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.inspection.ProbeSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.rocketmq.client.impl.MQClientAPIImpl;
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo;
import org.junit.jupiter.api.Test;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MessagingProtocolProbeTest {

    private final ProbeSettings defaults = new ProbeSettings(com.lqbz.ops.compat.Java8.map("timeoutMillis", "1000"), com.lqbz.ops.compat.Java8.map());

    private Asset asset(String type) {
        return new Asset("mq", "p", "e", type, "127.0.0.1:9092", "UNKNOWN");
    }

    @Test
    void kafkaReadsClusterMetadataAndClosesWithBoundedDeadline() throws Exception {
        Admin admin = mock(Admin.class);
        DescribeClusterResult cluster = mock(DescribeClusterResult.class);
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
        when(cluster.nodes()).thenReturn(KafkaFuture.completedFuture(com.lqbz.ops.compat.Java8.list(new Node(1, "localhost", 9092))));
        MessagingProtocolProbe probe = new MessagingProtocolProbe() {

            @Override
            protected Admin createKafkaAdmin(Properties props) {
                return admin;
            }
        };
        assertThat(probe.check(asset("Kafka"), defaults).get(0).passed()).isTrue();
        verify(admin).close(Duration.ZERO);
    }

    @Test
    void kafkaFailureIsFixedAndCredentialConfigurationIsEscaped() throws Exception {
        Admin admin = mock(Admin.class);
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenThrow(new RuntimeException("secret-password"));
        Properties[] captured = new Properties[1];
        MessagingProtocolProbe probe = new MessagingProtocolProbe() {

            @Override
            protected Admin createKafkaAdmin(Properties props) {
                captured[0] = props;
                return admin;
            }
        };
        lombok.val settings = new ProbeSettings(com.lqbz.ops.compat.Java8.map("securityProtocol", "SASL_SSL", "saslMechanism", "PLAIN", "username", "user\""), com.lqbz.ops.compat.Java8.map("password", "secret-password"));
        lombok.val check = probe.check(asset("Kafka"), settings).get(0);
        assertThat(check.passed()).isFalse();
        assertThat(check.message()).isEqualTo("Kafka 元数据检查失败");
        assertThat(check.toString()).doesNotContain("secret-password", "user\"");
        assertThat(captured[0].getProperty("sasl.jaas.config")).contains("user\\\"");
        assertThat(captured[0].getProperty("security.protocol")).isEqualTo("SASL_SSL");
        verify(admin).close(Duration.ZERO);
    }

    @Test
    void rabbitOpensChannelAndReleasesBothResources() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        when(factory.newConnection()).thenReturn(connection);
        when(connection.createChannel()).thenReturn(channel);
        MessagingProtocolProbe probe = new MessagingProtocolProbe() {

            @Override
            protected ConnectionFactory createRabbitFactory() {
                return factory;
            }
        };
        lombok.val result = probe.check(asset("RabbitMQ"), new ProbeSettings(com.lqbz.ops.compat.Java8.map("vhost", "/ops", "username", "inspector", "tls", "true"), com.lqbz.ops.compat.Java8.map("password", "secret-password"))).get(0);
        assertThat(result.passed()).isTrue();
        verify(factory).setVirtualHost("/ops");
        verify(factory).setUsername("inspector");
        verify(factory).setPassword("secret-password");
        verify(factory).useSslProtocol(any(javax.net.ssl.SSLContext.class));
        verify(factory).enableHostnameVerification();
        verify(channel).abort();
        verify(connection).abort();
    }

    @Test
    void rabbitFailureIsFixed() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.newConnection()).thenThrow(new TimeoutException("secret-password"));
        MessagingProtocolProbe probe = new MessagingProtocolProbe() {

            @Override
            protected ConnectionFactory createRabbitFactory() {
                return factory;
            }
        };
        lombok.val check = probe.check(asset("RabbitMQ"), defaults).get(0);
        assertThat(check.passed()).isFalse();
        assertThat(check.message()).isEqualTo("RabbitMQ AMQP 检查失败");
    }

    @Test
    void rocketReadsNameServerClusterInfoAndShutsDown() throws Exception {
        MQClientAPIImpl client = mock(MQClientAPIImpl.class);
        when(client.getBrokerClusterInfo(1000)).thenReturn(new ClusterInfo());
        MessagingProtocolProbe probe = new MessagingProtocolProbe() {

            @Override
            protected MQClientAPIImpl createRocketClient(ProbeSettings settings) {
                return client;
            }
        };
        assertThat(probe.check(asset("RocketMQ"), defaults).get(0).passed()).isTrue();
        verify(client).updateNameServerAddressList("127.0.0.1:9092");
        verify(client).start();
        verify(client).shutdown();
    }

    @Test
    void rocketFailureIsFixedAndShutsDown() throws Exception {
        MQClientAPIImpl client = mock(MQClientAPIImpl.class);
        when(client.getBrokerClusterInfo(1000)).thenThrow(new RuntimeException("secret-key"));
        MessagingProtocolProbe probe = new MessagingProtocolProbe() {

            @Override
            protected MQClientAPIImpl createRocketClient(ProbeSettings settings) {
                return client;
            }
        };
        lombok.val check = probe.check(asset("RocketMQ"), defaults).get(0);
        assertThat(check.passed()).isFalse();
        assertThat(check.message()).isEqualTo("RocketMQ NameServer 检查失败");
        verify(client).shutdown();
    }

    @Test
    void rejectsCredentialBearingEndpointAndUnsupportedTypes() {
        lombok.val probe = new MessagingProtocolProbe();
        lombok.val asset = new Asset("mq", "p", "e", "Kafka", "kafka://user:secret@localhost:9092", "UNKNOWN");
        lombok.val result = probe.check(asset, defaults).get(0);
        assertThat(result.passed()).isFalse();
        assertThat(result.toString()).doesNotContain("secret", "user:");
        assertThat(probe.supports("Kafka")).isTrue();
        assertThat(probe.supports("RabbitMQ")).isTrue();
        assertThat(probe.supports("RocketMQ")).isTrue();
        assertThat(probe.supports("Redis")).isFalse();
    }

    @Test
    void queriesRealRocketRemotingNameServerFixtureWithAclSignature() throws Exception {
        lombok.val serverConfig = new org.apache.rocketmq.remoting.netty.NettyServerConfig();
        serverConfig.setListenPort(0);
        serverConfig.setServerWorkerThreads(1);
        serverConfig.setServerSelectorThreads(1);
        lombok.val server = new org.apache.rocketmq.remoting.netty.NettyRemotingServer(serverConfig);
        lombok.val executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        lombok.val accessKey = new java.util.concurrent.atomic.AtomicReference<String>();
        lombok.val signature = new java.util.concurrent.atomic.AtomicReference<String>();
        lombok.val responseCode = new java.util.concurrent.atomic.AtomicInteger(0);
        server.registerProcessor(org.apache.rocketmq.remoting.protocol.RequestCode.GET_BROKER_CLUSTER_INFO, new org.apache.rocketmq.remoting.netty.NettyRequestProcessor() {

            @Override
            public boolean rejectRequest() {
                return false;
            }

            @Override
            public org.apache.rocketmq.remoting.protocol.RemotingCommand processRequest(io.netty.channel.ChannelHandlerContext context, org.apache.rocketmq.remoting.protocol.RemotingCommand request) {
                accessKey.set(request.getExtFields().get("AccessKey"));
                signature.set(request.getExtFields().get("Signature"));
                lombok.val response = org.apache.rocketmq.remoting.protocol.RemotingCommand.createResponseCommand(responseCode.get(), null);
                response.setBody(new ClusterInfo().encode());
                return response;
            }
        }, executor);
        server.start();
        try {
            lombok.val target = new Asset("mq", "p", "e", "RocketMQ", "127.0.0.1:" + server.localListenPort(), "UNKNOWN");
            lombok.val credentials = new ProbeSettings(com.lqbz.ops.compat.Java8.map("timeoutMillis", "2000"), com.lqbz.ops.compat.Java8.map("accessKey", "inspection", "secretKey", "secret-key"));
            lombok.val probe = new MessagingProtocolProbe();
            assertThat(probe.check(target, credentials).get(0).passed()).isTrue();
            assertThat(accessKey.get()).isEqualTo("inspection");
            assertThat(signature.get()).isNotBlank();
            responseCode.set(16);
            lombok.val denied = probe.check(target, credentials).get(0);
            assertThat(denied.passed()).isFalse();
            assertThat(denied.message()).isEqualTo("RocketMQ NameServer 检查失败");
        } finally {
            server.shutdown();
            executor.shutdownNow();
        }
    }

    @Test
    void completesRealAmqpHandshakeAndChannelThenHandlesAuthenticationRejection() throws Exception {
        for (boolean accept : com.lqbz.ops.compat.Java8.list(true, false)) {
            try (lombok.val socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                lombok.val executor = com.lqbz.ops.testutil.Java8TestSupport.executor()) {
                lombok.val served = executor.submit(() -> {
                    try (lombok.val peer = socket.accept()) {
                        peer.setSoTimeout(3000);
                        lombok.val input = new java.io.DataInputStream(peer.getInputStream());
                        lombok.val output = new java.io.DataOutputStream(peer.getOutputStream());
                        assertThat(com.lqbz.ops.testutil.Java8TestSupport.readBytes(input, 8)).isEqualTo(new byte[] { 65, 77, 81, 80, 0, 0, 9, 1 });
                        lombok.val plain = com.rabbitmq.client.impl.LongStringHelper.asLongString("PLAIN");
                        lombok.val locale = com.rabbitmq.client.impl.LongStringHelper.asLongString("en_US");
                        new com.rabbitmq.client.impl.AMQImpl.Connection.Start(0, 9, com.lqbz.ops.compat.Java8.map(), plain, locale).toFrame(0).writeTo(output);
                        output.flush();
                        lombok.val start = (com.rabbitmq.client.impl.AMQImpl.Connection.StartOk) readAmqp(input);
                        assertThat(start.getResponse().toString()).isEqualTo("\0inspector\0probe-password");
                        if (!accept) {
                            new com.rabbitmq.client.impl.AMQImpl.Connection.Close(403, "forbidden probe-password", 0, 0).toFrame(0).writeTo(output);
                            output.flush();
                            return true;
                        }
                        new com.rabbitmq.client.impl.AMQImpl.Connection.Tune(100, 131072, 0).toFrame(0).writeTo(output);
                        output.flush();
                        assertThat(readAmqp(input)).isInstanceOf(com.rabbitmq.client.impl.AMQImpl.Connection.TuneOk.class);
                        lombok.val open = (com.rabbitmq.client.impl.AMQImpl.Connection.Open) readAmqp(input);
                        assertThat(open.getVirtualHost()).isEqualTo("/ops");
                        new com.rabbitmq.client.impl.AMQImpl.Connection.OpenOk("").toFrame(0).writeTo(output);
                        output.flush();
                        lombok.val channelOpen = com.rabbitmq.client.impl.Frame.readFrom(input, 131072);
                        assertThat(com.rabbitmq.client.impl.AMQImpl.readMethodFrom(channelOpen.getInputStream())).isInstanceOf(com.rabbitmq.client.impl.AMQImpl.Channel.Open.class);
                        new com.rabbitmq.client.impl.AMQImpl.Channel.OpenOk(plain).toFrame(channelOpen.channel).writeTo(output);
                        output.flush();
                        assertThat(readAmqp(input)).isInstanceOf(com.rabbitmq.client.impl.AMQImpl.Channel.Close.class);
                        new com.rabbitmq.client.impl.AMQImpl.Channel.CloseOk().toFrame(channelOpen.channel).writeTo(output);
                        output.flush();
                        assertThat(readAmqp(input)).isInstanceOf(com.rabbitmq.client.impl.AMQImpl.Connection.Close.class);
                        new com.rabbitmq.client.impl.AMQImpl.Connection.CloseOk().toFrame(0).writeTo(output);
                        output.flush();
                        return true;
                    }
                });
                lombok.val target = new Asset("mq", "p", "e", "RabbitMQ", "127.0.0.1:" + socket.getLocalPort(), "UNKNOWN");
                lombok.val credentials = new ProbeSettings(com.lqbz.ops.compat.Java8.map("timeoutMillis", "2000", "vhost", "/ops", "username", "inspector"), com.lqbz.ops.compat.Java8.map("password", "probe-password"));
                lombok.val result = new MessagingProtocolProbe().check(target, credentials).get(0);
                assertThat(result.passed()).isEqualTo(accept);
                assertThat(result.toString()).doesNotContain("probe-password");
                assertThat(served.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private com.rabbitmq.client.impl.Method readAmqp(java.io.DataInputStream input) throws java.io.IOException {
        lombok.val frame = com.rabbitmq.client.impl.Frame.readFrom(input, 131072);
        return com.rabbitmq.client.impl.AMQImpl.readMethodFrom(frame.getInputStream());
    }

    @Test
    void readsKafkaMetadataOverRealKafkaWireProtocol() throws Exception {
        try (lombok.val socket = new java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
            lombok.val executor = com.lqbz.ops.testutil.Java8TestSupport.executor()) {
            lombok.val metadataRequests = new java.util.concurrent.atomic.AtomicInteger();
            lombok.val fixtureErrors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            executor.submit(() -> {
                while (!socket.isClosed()) {
                    try {
                        lombok.val peer = socket.accept();
                        executor.submit(() -> {
                            try (java.net.Socket ownedPeer = peer) {
                                peer.setSoTimeout(5000);
                                lombok.val input = new java.io.DataInputStream(peer.getInputStream());
                                lombok.val output = new java.io.DataOutputStream(peer.getOutputStream());
                                while (true) {
                                    int length;
                                    try {
                                        length = input.readInt();
                                    } catch (java.io.EOFException closed) {
                                        break;
                                    }
                                    lombok.val bytes = java.nio.ByteBuffer.wrap(com.lqbz.ops.testutil.Java8TestSupport.readBytes(input, length));
                                    lombok.val header = org.apache.kafka.common.requests.RequestHeader.parse(bytes);
                                    org.apache.kafka.common.protocol.ApiMessage body;
                                    if (header.apiKey() == org.apache.kafka.common.protocol.ApiKeys.API_VERSIONS) {
                                        lombok.val versions = new org.apache.kafka.common.message.ApiVersionsResponseData.ApiVersionCollection();
                                        versions.add(new org.apache.kafka.common.message.ApiVersionsResponseData.ApiVersion().setApiKey(org.apache.kafka.common.protocol.ApiKeys.API_VERSIONS.id).setMinVersion((short) 0).setMaxVersion((short) 3));
                                        versions.add(new org.apache.kafka.common.message.ApiVersionsResponseData.ApiVersion().setApiKey(org.apache.kafka.common.protocol.ApiKeys.METADATA.id).setMinVersion((short) 0).setMaxVersion((short) 12));
                                        body = new org.apache.kafka.common.message.ApiVersionsResponseData().setApiKeys(versions);
                                    } else if (header.apiKey() == org.apache.kafka.common.protocol.ApiKeys.METADATA) {
                                        metadataRequests.incrementAndGet();
                                        lombok.val brokers = new org.apache.kafka.common.message.MetadataResponseData.MetadataResponseBrokerCollection();
                                        brokers.add(new org.apache.kafka.common.message.MetadataResponseData.MetadataResponseBroker().setNodeId(1).setHost("127.0.0.1").setPort(socket.getLocalPort()));
                                        body = new org.apache.kafka.common.message.MetadataResponseData().setBrokers(brokers).setClusterId("fixture").setControllerId(1);
                                    } else {
                                        throw new AssertionError("非预期 Kafka 请求: " + header.apiKey());
                                    }
                                    lombok.val responseHeader = org.apache.kafka.common.protocol.MessageUtil.toByteBuffer(new org.apache.kafka.common.message.ResponseHeaderData().setCorrelationId(header.correlationId()), header.apiKey().responseHeaderVersion(header.apiVersion()));
                                    lombok.val responseBody = org.apache.kafka.common.protocol.MessageUtil.toByteBuffer(body, header.apiVersion());
                                    output.writeInt(responseHeader.remaining() + responseBody.remaining());
                                    output.write(responseHeader.array(), responseHeader.position(), responseHeader.remaining());
                                    output.write(responseBody.array(), responseBody.position(), responseBody.remaining());
                                    output.flush();
                                }
                            } catch (Throwable error) {
                                fixtureErrors.add(error);
                            }
                        });
                    } catch (java.net.SocketException closed) {
                        break;
                    } catch (java.io.IOException error) {
                        fixtureErrors.add(error);
                        break;
                    }
                }
            });
            try {
                lombok.val target = new Asset("mq", "p", "e", "Kafka", "127.0.0.1:" + socket.getLocalPort(), "UNKNOWN");
                lombok.val result = new MessagingProtocolProbe().check(target, new ProbeSettings(com.lqbz.ops.compat.Java8.map("timeoutMillis", "3000"), com.lqbz.ops.compat.Java8.map())).get(0);
                assertThat(fixtureErrors).isEmpty();
                assertThat(result.passed()).isTrue();
                assertThat(metadataRequests.get()).isGreaterThan(0);
            } finally {
                socket.close();
            }
        }
    }

    @Test
    void usesServiceDefaultPortsIncludingRabbitTls() throws Exception {
        Admin admin = mock(Admin.class);
        DescribeClusterResult cluster = mock(DescribeClusterResult.class);
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
        when(cluster.nodes()).thenReturn(KafkaFuture.completedFuture(com.lqbz.ops.compat.Java8.list(new Node(1, "localhost", 9092))));
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        when(factory.newConnection()).thenReturn(connection);
        when(connection.createChannel()).thenReturn(mock(Channel.class));
        MQClientAPIImpl rocket = mock(MQClientAPIImpl.class);
        when(rocket.getBrokerClusterInfo(anyLong())).thenReturn(new ClusterInfo());
        lombok.val probe = new MessagingProtocolProbe() {

            @Override
            protected Admin createKafkaAdmin(Properties properties) {
                return admin;
            }

            @Override
            protected ConnectionFactory createRabbitFactory() {
                return factory;
            }

            @Override
            protected MQClientAPIImpl createRocketClient(ProbeSettings settings) {
                return rocket;
            }
        };
        for (lombok.val entry : com.lqbz.ops.compat.Java8.<String, Integer>map("Kafka", 9092, "RabbitMQ", 5672, "RocketMQ", 9876).entrySet()) {
            lombok.val target = new Asset("mq", "p", "e", entry.getKey(), "localhost", "UNKNOWN");
            lombok.val check = probe.check(target, defaults).get(0);
            assertThat(check.passed()).isTrue();
            assertThat(check.target()).isEqualTo("localhost:" + entry.getValue());
        }
        lombok.val rabbit = new Asset("mq", "p", "e", "RabbitMQ", "localhost", "UNKNOWN");
        lombok.val secure = new ProbeSettings(com.lqbz.ops.compat.Java8.map("tls", "true"), com.lqbz.ops.compat.Java8.map());
        assertThat(probe.check(rabbit, secure).get(0).target()).isEqualTo("localhost:5671");
        rabbit.update("mq", "p", "e", "RabbitMQ", "amqps://localhost", "UNKNOWN");
        assertThat(probe.check(rabbit, defaults).get(0).target()).isEqualTo("localhost:5671");
    }
}
