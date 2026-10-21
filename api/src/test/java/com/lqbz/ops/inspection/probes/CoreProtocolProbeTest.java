package com.lqbz.ops.inspection.probes;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.inspection.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CoreProtocolProbeTest {
  Asset asset(String type, String endpoint) { return new Asset("测试", "项目", "测试", type, endpoint, "UNKNOWN"); }
  ProbeSettings settings(Map<String,String> options, Map<String,String> secrets) { return new ProbeSettings(options, secrets); }

  @Test void httpSendsSecretHeadersAndBodyAndChecksResponse() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/health", exchange -> {
      boolean valid = "POST".equals(exchange.getRequestMethod()) && "private-value".equals(exchange.getRequestHeaders().getFirst("X-Token"))
          && "private-body".equals(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] body = (valid ? "ready" : "bad").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(valid ? 201 : 400, body.length);
      exchange.getResponseBody().write(body); exchange.close();
    });
    server.start();
    try {
      var probe = new HttpProtocolProbe();
      var a = asset("HTTP", "http://127.0.0.1:" + server.getAddress().getPort() + "/health");
      var checks = probe.check(a, settings(Map.of("httpMethod","POST","expectedStatus","201","responseContains","ready"), Map.of("headers","{\"X-Token\":\"private-value\"}","body","private-body")));
      assertThat(checks).isNotEmpty().allMatch(ProbeCheck::passed);
      assertThat(checks.toString()).doesNotContain("private-value", "private-body");
      assertThat(probe.check(a, settings(Map.of("expectedStatus","201"),Map.of()))).anyMatch(c -> !c.passed());
    } finally { server.stop(0); }
  }

  @Test void httpRejectsRedirectsAndOversizeBodies() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/redirect", ex -> { ex.getResponseHeaders().set("Location","/ok"); ex.sendResponseHeaders(302,-1); ex.close(); });
    server.createContext("/ok", ex -> { byte[] b=new byte[1048577]; ex.sendResponseHeaders(200,b.length); try { ex.getResponseBody().write(b); } catch(IOException ignored) {} ex.close(); });
    server.start();
    try {
      var probe=new HttpProtocolProbe(); String base="http://127.0.0.1:"+server.getAddress().getPort();
      assertThat(probe.check(asset("HTTP",base+"/redirect"),settings(Map.of(),Map.of()))).anyMatch(c -> !c.passed());
      assertThat(probe.check(asset("HTTP",base+"/ok"),settings(Map.of(),Map.of()))).anyMatch(c -> !c.passed());
    } finally { server.stop(0); }
  }

  @Test void httpTimesOutWhileResponseBodyIsStillStreaming() throws Exception {
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/slow",ex -> {
      ex.sendResponseHeaders(200,2); ex.getResponseBody().write('o'); ex.getResponseBody().flush();
      try { Thread.sleep(2200); ex.getResponseBody().write('k'); } catch(Exception ignored) {} finally { ex.close(); }
    });
    server.start();
    try {
      long start=System.nanoTime();
      var checks=new HttpProtocolProbe().check(asset("HTTP","http://127.0.0.1:"+server.getAddress().getPort()+"/slow"),settings(Map.of("timeoutMillis","1000"),Map.of()));
      assertThat(checks).anyMatch(c -> !c.passed());
      assertThat((System.nanoTime()-start)/1_000_000).isLessThan(1900);
    } finally { server.stop(0); }
  }

  @Test void redisAuthenticatesWithRespAndRequiresPong() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      var task = CompletableFuture.supplyAsync(() -> {
        try(Socket socket=server.accept()) {
          socket.setSoTimeout(3000); var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
          List<String> commands=new ArrayList<>();
          for(int i=0;i<7;i++) commands.add(in.readLine());
          socket.getOutputStream().write("+OK\r\n".getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
          for(int i=0;i<3;i++) commands.add(in.readLine());
          socket.getOutputStream().write("+PONG\r\n".getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
          return commands;
        } catch(IOException e) { throw new UncheckedIOException(e); }
      });
      var result=new RedisProtocolProbe().check(asset("REDIS","127.0.0.1:"+server.getLocalPort()),settings(Map.of("username","alice"),Map.of("password","private-value")));
      assertThat(result).allMatch(ProbeCheck::passed);
      assertThat(task.get(4,TimeUnit.SECONDS)).containsExactly("*3","$4","AUTH","$5","alice","$13","private-value","*1","$4","PING");
      assertThat(result.toString()).doesNotContain("private-value");
    }
  }

  @Test void redisErrorIsSafeAndOpenPortIsNotSuccess() throws Exception {
    try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
      var task=CompletableFuture.runAsync(() -> { try(Socket socket=server.accept()) { socket.getOutputStream().write("-ERR private-value\r\n".getBytes(StandardCharsets.UTF_8)); } catch(IOException e) { throw new UncheckedIOException(e); } });
      var result=new RedisProtocolProbe().check(asset("REDIS","127.0.0.1:"+server.getLocalPort()),settings(Map.of(),Map.of()));
      assertThat(result).anyMatch(c -> !c.passed()); assertThat(result.toString()).doesNotContain("private-value"); task.get(4,TimeUnit.SECONDS);
    }
  }

  @Test void redisSlowTrickleCannotExtendReplyTimeout() throws Exception {
    try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
      var task=CompletableFuture.runAsync(() -> {
        try(Socket socket=server.accept()) {
          var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
          for(int i=0;i<3;i++) reader.readLine();
          for(int i=0;i<15;i++) { socket.getOutputStream().write('+'); socket.getOutputStream().flush(); Thread.sleep(150); }
        } catch(Exception ignored) {}
      });
      long start=System.nanoTime();
      var checks=new RedisProtocolProbe().check(asset("REDIS","127.0.0.1:"+server.getLocalPort()),settings(Map.of("timeoutMillis","1000"),Map.of()));
      assertThat(checks).anyMatch(c -> !c.passed());
      assertThat((System.nanoTime()-start)/1_000_000).isLessThan(1900);
      task.get(4,TimeUnit.SECONDS);
    }
  }

  @Test void sshParsesEachMountAndRejectsMissingOrOverThreshold() {
    String df="Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/root 100 85 15 85% /\n/dev/data 100 70 30 70% /data\n";
    var checks=SystemProtocolProbe.diskChecks(df,settings(Map.of("diskThreshold","80","mountPoints","/,/data,/missing"),Map.of()),0);
    assertThat(checks).hasSize(3);
    assertThat(checks).anyMatch(c -> c.target().equals("/") && !c.passed() && c.message().contains("85%"));
    assertThat(checks).anyMatch(c -> c.target().equals("/data") && c.passed());
    assertThat(checks).anyMatch(c -> c.target().equals("/missing") && !c.passed());
    assertThat(SystemProtocolProbe.diskChecks(df,settings(Map.of("excludedMountPoints","/"),Map.of()),0)).hasSize(1).allMatch(ProbeCheck::passed);
  }

  @Test void diskReachingThresholdFailsAndIncludesCapacityDetails() {
    String df="Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/data 100 80 20 80% /data\n";
    var checks=SystemProtocolProbe.diskChecks(df,settings(Map.of("diskThreshold","80"),Map.of()),0);
    assertThat(checks).hasSize(1).allMatch(c -> !c.passed());
    assertThat(checks.getFirst().message()).contains("总量 100 KiB","已用 80 KiB","可用 20 KiB");
  }

  @Test void unsupportedHttpMethodsFailBeforeSending() {
    for(String method:List.of("DELETE","PATCH","OPTIONS")) {
      var checks=new HttpProtocolProbe().check(asset("HTTP","http://127.0.0.1:1"),settings(Map.of("httpMethod",method),Map.of()));
      assertThat(checks).allMatch(c -> !c.passed() && c.message().contains("配置无效"));
    }
  }

  @Test void invalidSettingsAndCredentialsProduceSafeFailures() throws Exception {
    for(ProtocolProbe probe:List.of(new SystemProtocolProbe(),new DatabaseProtocolProbe(),new RedisProtocolProbe(),new HttpProtocolProbe())) {
      var checks=probe.check(asset("MYSQL","bad://private-value@invalid"),settings(Map.of("timeoutMillis","0"),Map.of("password","private-value")));
      assertThat(checks).isNotEmpty().allMatch(c -> !c.passed()); assertThat(checks.toString()).doesNotContain("private-value");
    }
    assertThat(new SystemProtocolProbe().check(asset("SERVER","127.0.0.1"),settings(Map.of("username","root"),Map.of("password","private-value")))).allMatch(c -> !c.passed());
  }
}
