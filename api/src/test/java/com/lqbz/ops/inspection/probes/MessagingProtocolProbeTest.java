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
  private final ProbeSettings defaults = new ProbeSettings(Map.of("timeoutMillis", "1000"), Map.of());
  private Asset asset(String type) { return new Asset("mq", "p", "e", type, "127.0.0.1:9092", "UNKNOWN"); }

  @Test void kafkaReadsClusterMetadataAndClosesWithBoundedDeadline() throws Exception {
    Admin admin = mock(Admin.class);
    DescribeClusterResult cluster = mock(DescribeClusterResult.class);
    when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
    when(cluster.nodes()).thenReturn(KafkaFuture.completedFuture(List.of(new Node(1, "localhost", 9092))));
    MessagingProtocolProbe probe = new MessagingProtocolProbe() {
      @Override protected Admin createKafkaAdmin(Properties props) { return admin; }
    };
    assertThat(probe.check(asset("Kafka"), defaults).getFirst().passed()).isTrue();
    verify(admin).close(Duration.ZERO);
  }

  @Test void kafkaFailureIsFixedAndCredentialConfigurationIsEscaped() throws Exception {
    Admin admin = mock(Admin.class);
    when(admin.describeCluster(any(DescribeClusterOptions.class))).thenThrow(new RuntimeException("secret-password"));
    Properties[] captured = new Properties[1];
    MessagingProtocolProbe probe = new MessagingProtocolProbe() {
      @Override protected Admin createKafkaAdmin(Properties props) { captured[0] = props; return admin; }
    };
    var settings = new ProbeSettings(Map.of("securityProtocol", "SASL_SSL", "saslMechanism", "PLAIN", "username", "user\""), Map.of("password", "secret-password"));
    var check = probe.check(asset("Kafka"), settings).getFirst();
    assertThat(check.passed()).isFalse();
    assertThat(check.message()).isEqualTo("Kafka 元数据检查失败");
    assertThat(check.toString()).doesNotContain("secret-password", "user\"");
    assertThat(captured[0].getProperty("sasl.jaas.config")).contains("user\\\"");
    assertThat(captured[0].getProperty("security.protocol")).isEqualTo("SASL_SSL");
    verify(admin).close(Duration.ZERO);
  }

  @Test void rabbitOpensChannelAndReleasesBothResources() throws Exception {
    ConnectionFactory factory = mock(ConnectionFactory.class);
    Connection connection = mock(Connection.class);
    Channel channel = mock(Channel.class);
    when(factory.newConnection()).thenReturn(connection);
    when(connection.createChannel()).thenReturn(channel);
    MessagingProtocolProbe probe = new MessagingProtocolProbe() {
      @Override protected ConnectionFactory createRabbitFactory() { return factory; }
    };
    var result = probe.check(asset("RabbitMQ"), new ProbeSettings(Map.of("vhost", "/ops", "username", "inspector", "tls", "true"), Map.of("password", "secret-password"))).getFirst();
    assertThat(result.passed()).isTrue();
    verify(factory).setVirtualHost("/ops");
    verify(factory).setUsername("inspector");
    verify(factory).setPassword("secret-password");
    verify(factory).useSslProtocol(any(javax.net.ssl.SSLContext.class));
    verify(factory).enableHostnameVerification();
    verify(channel).abort();
    verify(connection).abort();
  }

  @Test void rabbitFailureIsFixed() throws Exception {
    ConnectionFactory factory = mock(ConnectionFactory.class);
    when(factory.newConnection()).thenThrow(new TimeoutException("secret-password"));
    MessagingProtocolProbe probe = new MessagingProtocolProbe() {
      @Override protected ConnectionFactory createRabbitFactory() { return factory; }
    };
    var check = probe.check(asset("RabbitMQ"), defaults).getFirst();
    assertThat(check.passed()).isFalse();
    assertThat(check.message()).isEqualTo("RabbitMQ AMQP 检查失败");
  }

  @Test void rocketReadsNameServerClusterInfoAndShutsDown() throws Exception {
    MQClientAPIImpl client = mock(MQClientAPIImpl.class);
    when(client.getBrokerClusterInfo(1000)).thenReturn(new ClusterInfo());
    MessagingProtocolProbe probe = new MessagingProtocolProbe() {
      @Override protected MQClientAPIImpl createRocketClient(ProbeSettings settings) { return client; }
    };
    assertThat(probe.check(asset("RocketMQ"), defaults).getFirst().passed()).isTrue();
    verify(client).updateNameServerAddressList("127.0.0.1:9092");
    verify(client).start();
    verify(client).shutdown();
  }

  @Test void rocketFailureIsFixedAndShutsDown() throws Exception {
    MQClientAPIImpl client = mock(MQClientAPIImpl.class);
    when(client.getBrokerClusterInfo(1000)).thenThrow(new RuntimeException("secret-key"));
    MessagingProtocolProbe probe = new MessagingProtocolProbe() {
      @Override protected MQClientAPIImpl createRocketClient(ProbeSettings settings) { return client; }
    };
    var check = probe.check(asset("RocketMQ"), defaults).getFirst();
    assertThat(check.passed()).isFalse();
    assertThat(check.message()).isEqualTo("RocketMQ NameServer 检查失败");
    verify(client).shutdown();
  }

  @Test void rejectsCredentialBearingEndpointAndUnsupportedTypes() {
    var probe = new MessagingProtocolProbe();
    var asset = new Asset("mq", "p", "e", "Kafka", "kafka://user:secret@localhost:9092", "UNKNOWN");
    var result = probe.check(asset, defaults).getFirst();
    assertThat(result.passed()).isFalse();
    assertThat(result.toString()).doesNotContain("secret", "user:");
    assertThat(probe.supports("Kafka")).isTrue();
    assertThat(probe.supports("RabbitMQ")).isTrue();
    assertThat(probe.supports("RocketMQ")).isTrue();
    assertThat(probe.supports("Redis")).isFalse();
  }
  @Test void queriesRealRocketRemotingNameServerFixtureWithAclSignature() throws Exception {
    var serverConfig = new org.apache.rocketmq.remoting.netty.NettyServerConfig();
    serverConfig.setListenPort(0);
    serverConfig.setServerWorkerThreads(1);
    serverConfig.setServerSelectorThreads(1);
    var server = new org.apache.rocketmq.remoting.netty.NettyRemotingServer(serverConfig);
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    var accessKey = new java.util.concurrent.atomic.AtomicReference<String>();
    var signature = new java.util.concurrent.atomic.AtomicReference<String>();
    var responseCode = new java.util.concurrent.atomic.AtomicInteger(0);
    server.registerProcessor(org.apache.rocketmq.remoting.protocol.RequestCode.GET_BROKER_CLUSTER_INFO,
        new org.apache.rocketmq.remoting.netty.NettyRequestProcessor() {
          @Override public boolean rejectRequest() { return false; }
          @Override public org.apache.rocketmq.remoting.protocol.RemotingCommand processRequest(
              io.netty.channel.ChannelHandlerContext context, org.apache.rocketmq.remoting.protocol.RemotingCommand request) {
            accessKey.set(request.getExtFields().get("AccessKey"));
            signature.set(request.getExtFields().get("Signature"));
            var response = org.apache.rocketmq.remoting.protocol.RemotingCommand.createResponseCommand(responseCode.get(), null);
            response.setBody(new ClusterInfo().encode());
            return response;
          }
        }, executor);
    server.start();
    try {
      var target = new Asset("mq", "p", "e", "RocketMQ", "127.0.0.1:" + server.localListenPort(), "UNKNOWN");
      var credentials = new ProbeSettings(Map.of("timeoutMillis", "2000"), Map.of("accessKey", "inspection", "secretKey", "secret-key"));
      var probe = new MessagingProtocolProbe();
      assertThat(probe.check(target, credentials).getFirst().passed()).isTrue();
      assertThat(accessKey.get()).isEqualTo("inspection");
      assertThat(signature.get()).isNotBlank();
      responseCode.set(16);
      var denied = probe.check(target, credentials).getFirst();
      assertThat(denied.passed()).isFalse();
      assertThat(denied.message()).isEqualTo("RocketMQ NameServer 检查失败");
    } finally { server.shutdown(); executor.shutdownNow(); }
  }

  @Test void completesRealAmqpHandshakeAndChannelThenHandlesAuthenticationRejection() throws Exception {
    for (boolean accept : List.of(true, false)) {
      try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
          var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        var served = executor.submit(() -> {
          try (var peer = socket.accept()) {
            peer.setSoTimeout(3000);
            var input = new java.io.DataInputStream(peer.getInputStream());
            var output = new java.io.DataOutputStream(peer.getOutputStream());
            assertThat(input.readNBytes(8)).isEqualTo(new byte[] {65, 77, 81, 80, 0, 0, 9, 1});
            var plain = com.rabbitmq.client.impl.LongStringHelper.asLongString("PLAIN");
            var locale = com.rabbitmq.client.impl.LongStringHelper.asLongString("en_US");
            new com.rabbitmq.client.impl.AMQImpl.Connection.Start(0, 9, Map.of(), plain, locale).toFrame(0).writeTo(output);
            output.flush();
            var start = (com.rabbitmq.client.impl.AMQImpl.Connection.StartOk) readAmqp(input);
            assertThat(start.getResponse().toString()).isEqualTo("\0inspector\0probe-password");
            if (!accept) {
              new com.rabbitmq.client.impl.AMQImpl.Connection.Close(403, "forbidden probe-password", 0, 0).toFrame(0).writeTo(output);
              output.flush();
              return true;
            }
            new com.rabbitmq.client.impl.AMQImpl.Connection.Tune(100, 131072, 0).toFrame(0).writeTo(output);
            output.flush();
            assertThat(readAmqp(input)).isInstanceOf(com.rabbitmq.client.impl.AMQImpl.Connection.TuneOk.class);
            var open = (com.rabbitmq.client.impl.AMQImpl.Connection.Open) readAmqp(input);
            assertThat(open.getVirtualHost()).isEqualTo("/ops");
            new com.rabbitmq.client.impl.AMQImpl.Connection.OpenOk("").toFrame(0).writeTo(output);
            output.flush();
            var channelOpen = com.rabbitmq.client.impl.Frame.readFrom(input, 131072);
            assertThat(com.rabbitmq.client.impl.AMQImpl.readMethodFrom(channelOpen.getInputStream()))
                .isInstanceOf(com.rabbitmq.client.impl.AMQImpl.Channel.Open.class);
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
        var target = new Asset("mq", "p", "e", "RabbitMQ", "127.0.0.1:" + socket.getLocalPort(), "UNKNOWN");
        var credentials = new ProbeSettings(Map.of("timeoutMillis", "2000", "vhost", "/ops", "username", "inspector"), Map.of("password", "probe-password"));
        var result = new MessagingProtocolProbe().check(target, credentials).getFirst();
        assertThat(result.passed()).isEqualTo(accept);
        assertThat(result.toString()).doesNotContain("probe-password");
        assertThat(served.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private com.rabbitmq.client.impl.Method readAmqp(java.io.DataInputStream input) throws java.io.IOException {
    var frame = com.rabbitmq.client.impl.Frame.readFrom(input, 131072);
    return com.rabbitmq.client.impl.AMQImpl.readMethodFrom(frame.getInputStream());
  }

  @Test void readsKafkaMetadataOverRealKafkaWireProtocol() throws Exception {
    try (var socket = new java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var metadataRequests = new java.util.concurrent.atomic.AtomicInteger();
      var fixtureErrors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
      executor.submit(() -> {
        while (!socket.isClosed()) {
          try {
            var peer = socket.accept();
            executor.submit(() -> {
              try (peer) {
                peer.setSoTimeout(5000);
                var input = new java.io.DataInputStream(peer.getInputStream());
                var output = new java.io.DataOutputStream(peer.getOutputStream());
                while (true) {
                  int length;
                  try { length = input.readInt(); } catch (java.io.EOFException closed) { break; }
                  var bytes = java.nio.ByteBuffer.wrap(input.readNBytes(length));
                  var header = org.apache.kafka.common.requests.RequestHeader.parse(bytes);
                  org.apache.kafka.common.protocol.ApiMessage body;
                  if (header.apiKey() == org.apache.kafka.common.protocol.ApiKeys.API_VERSIONS) {
                    var versions = new org.apache.kafka.common.message.ApiVersionsResponseData.ApiVersionCollection();
                    versions.add(new org.apache.kafka.common.message.ApiVersionsResponseData.ApiVersion()
                        .setApiKey(org.apache.kafka.common.protocol.ApiKeys.API_VERSIONS.id).setMinVersion((short) 0).setMaxVersion((short) 3));
                    versions.add(new org.apache.kafka.common.message.ApiVersionsResponseData.ApiVersion()
                        .setApiKey(org.apache.kafka.common.protocol.ApiKeys.METADATA.id).setMinVersion((short) 0).setMaxVersion((short) 12));
                    body = new org.apache.kafka.common.message.ApiVersionsResponseData().setApiKeys(versions);
                  } else if (header.apiKey() == org.apache.kafka.common.protocol.ApiKeys.METADATA) {
                    metadataRequests.incrementAndGet();
                    var brokers = new org.apache.kafka.common.message.MetadataResponseData.MetadataResponseBrokerCollection();
                    brokers.add(new org.apache.kafka.common.message.MetadataResponseData.MetadataResponseBroker()
                        .setNodeId(1).setHost("127.0.0.1").setPort(socket.getLocalPort()));
                    body = new org.apache.kafka.common.message.MetadataResponseData().setBrokers(brokers).setClusterId("fixture").setControllerId(1);
                  } else { throw new AssertionError("非预期 Kafka 请求: " + header.apiKey()); }
                  var responseHeader = org.apache.kafka.common.protocol.MessageUtil.toByteBuffer(
                      new org.apache.kafka.common.message.ResponseHeaderData().setCorrelationId(header.correlationId()),
                      header.apiKey().responseHeaderVersion(header.apiVersion()));
                  var responseBody = org.apache.kafka.common.protocol.MessageUtil.toByteBuffer(body, header.apiVersion());
                  output.writeInt(responseHeader.remaining() + responseBody.remaining());
                  output.write(responseHeader.array(), responseHeader.position(), responseHeader.remaining());
                  output.write(responseBody.array(), responseBody.position(), responseBody.remaining());
                  output.flush();
                }
              } catch (Throwable error) { fixtureErrors.add(error); }
            });
          } catch (java.net.SocketException closed) { break; }
          catch (java.io.IOException error) { fixtureErrors.add(error); break; }
        }
      });
      try {
        var target = new Asset("mq", "p", "e", "Kafka", "127.0.0.1:" + socket.getLocalPort(), "UNKNOWN");
        var result = new MessagingProtocolProbe().check(target, new ProbeSettings(Map.of("timeoutMillis", "3000"), Map.of())).getFirst();
        assertThat(fixtureErrors).isEmpty();
        assertThat(result.passed()).isTrue();
        assertThat(metadataRequests.get()).isGreaterThan(0);
      } finally { socket.close(); }
    }
  }

  @Test void usesServiceDefaultPortsIncludingRabbitTls() throws Exception {
    Admin admin = mock(Admin.class);
    DescribeClusterResult cluster = mock(DescribeClusterResult.class);
    when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
    when(cluster.nodes()).thenReturn(KafkaFuture.completedFuture(List.of(new Node(1, "localhost", 9092))));
    ConnectionFactory factory = mock(ConnectionFactory.class);
    Connection connection = mock(Connection.class);
    when(factory.newConnection()).thenReturn(connection);
    when(connection.createChannel()).thenReturn(mock(Channel.class));
    MQClientAPIImpl rocket = mock(MQClientAPIImpl.class);
    when(rocket.getBrokerClusterInfo(anyLong())).thenReturn(new ClusterInfo());
    var probe = new MessagingProtocolProbe() {
      @Override protected Admin createKafkaAdmin(Properties properties) { return admin; }
      @Override protected ConnectionFactory createRabbitFactory() { return factory; }
      @Override protected MQClientAPIImpl createRocketClient(ProbeSettings settings) { return rocket; }
    };
    for (var entry : Map.of("Kafka", 9092, "RabbitMQ", 5672, "RocketMQ", 9876).entrySet()) {
      var target = new Asset("mq", "p", "e", entry.getKey(), "localhost", "UNKNOWN");
      var check = probe.check(target, defaults).getFirst();
      assertThat(check.passed()).isTrue();
      assertThat(check.target()).isEqualTo("localhost:" + entry.getValue());
    }
    var rabbit = new Asset("mq", "p", "e", "RabbitMQ", "localhost", "UNKNOWN");
    var secure = new ProbeSettings(Map.of("tls", "true"), Map.of());
    assertThat(probe.check(rabbit, secure).getFirst().target()).isEqualTo("localhost:5671");
    rabbit.update("mq", "p", "e", "RabbitMQ", "amqps://localhost", "UNKNOWN");
    assertThat(probe.check(rabbit, defaults).getFirst().target()).isEqualTo("localhost:5671");
  }

}
