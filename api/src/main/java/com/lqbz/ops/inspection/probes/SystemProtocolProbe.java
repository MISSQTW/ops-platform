package com.lqbz.ops.inspection.probes;

import com.jcraft.jsch.*;
import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.inspection.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class SystemProtocolProbe implements ProtocolProbe {
  public boolean supports(String type) { return "SERVER".equalsIgnoreCase(type); }
  public List<ProbeCheck> check(Asset asset, ProbeSettings settings) {
    long start=System.nanoTime(); Session session=null; ChannelExec channel=null;
    try {
      int timeout=RedisProtocolProbe.timeout(settings); URI endpoint=RedisProtocolProbe.endpoint(asset.getEndpoint(),22);
      String username=settings.text("username",""), fingerprint=settings.text("hostKey","");
      if(username.isBlank() || !fingerprint.startsWith("SHA256:") || Base64.getDecoder().decode(fingerprint.substring(7)).length!=32) throw new IllegalArgumentException();
      threshold(settings);
      JSch jsch=new JSch(); jsch.setHostKeyRepository(new FingerprintRepository(fingerprint));
      String key=settings.secret("privateKey"),password=settings.secret("password");
      if(key.isBlank() && password.isEmpty()) throw new IllegalArgumentException();
      if(!key.isBlank()) jsch.addIdentity("巡检密钥",key.getBytes(StandardCharsets.UTF_8),null,settings.secret("privateKeyPassphrase").getBytes(StandardCharsets.UTF_8));
      session=jsch.getSession(username,endpoint.getHost(),endpoint.getPort());
      session.setConfig("StrictHostKeyChecking","yes"); session.setConfig("PreferredAuthentications",key.isBlank()?"password":"publickey");
      if(!password.isEmpty()) session.setPassword(password);
      session.setTimeout(timeout); session.connect(timeout);
      channel=(ChannelExec)session.openChannel("exec"); channel.setCommand("LC_ALL=C df -Pk"); channel.setInputStream(null);
      // 丢弃远端标准错误，不允许将服务端返回的认证/命令文本写入巡检结果。
      channel.setErrStream(OutputStream.nullOutputStream()); InputStream in=channel.getInputStream();
      channel.connect(timeout); long deadline=System.nanoTime()+timeout*1_000_000L; ByteArrayOutputStream output=new ByteArrayOutputStream();
      byte[] buffer=new byte[8192];
      while(true) {
        if(System.nanoTime()>deadline) throw new IOException();
        while(in.available()>0) { int count=in.read(buffer); if(count<0) break; if(output.size()+count>1_048_576) throw new IOException(); output.write(buffer,0,count); }
        if(channel.isClosed() && in.available()==0) break;
        Thread.sleep(10);
      }
      if(channel.getExitStatus()!=0) return failure("SSH 磁盘命令执行失败",start);
      List<ProbeCheck> checks=new ArrayList<>(); checks.add(new ProbeCheck("SSH","SSH 主机密钥与认证","SSH",true,"SSH 主机密钥校验及认证通过",RedisProtocolProbe.elapsed(start)));
      checks.addAll(diskChecks(output.toString(StandardCharsets.UTF_8),settings,RedisProtocolProbe.elapsed(start))); return checks;
    } catch(IllegalArgumentException e) { return failure("SSH 配置无效或缺少主机密钥指纹",start); }
    catch(Exception e) { if(e instanceof InterruptedException) Thread.currentThread().interrupt(); return failure("SSH 连接、主机密钥校验或认证失败",start); }
    finally { if(channel!=null) channel.disconnect(); if(session!=null) session.disconnect(); }
  }
  private List<ProbeCheck> failure(String message,long start) { return List.of(new ProbeCheck("SSH","SSH 主机密钥与认证","SSH",false,message,RedisProtocolProbe.elapsed(start))); }
  private static int threshold(ProbeSettings settings) { int value=settings.number("diskThreshold",80); if(value<1 || value>100) throw new IllegalArgumentException(); return value; }

  static List<ProbeCheck> diskChecks(String output,ProbeSettings settings,long duration) {
    int threshold=threshold(settings); Map<String,DiskUsage> mounts=new LinkedHashMap<>();
    for(String line:output.split("\\R")) {
      String[] fields=line.trim().split("\\s+",6);
      if(fields.length!=6 || !fields[4].matches("[0-9]{1,3}%")) continue;
      int usage=Integer.parseInt(fields[4].substring(0,fields[4].length()-1));
      String mount=fields[5].replace("\\040"," ").replace("\\011","\t").replace("\\134","\\");
      if(!fields[1].matches("[0-9]+") || !fields[2].matches("[0-9]+") || !fields[3].matches("[0-9]+")) continue;
      mounts.put(mount,new DiskUsage(fields[1],fields[2],fields[3],usage));
    }
    Set<String> selected=new LinkedHashSet<>(settings.list("mountPoints")); if(selected.isEmpty()) selected.addAll(mounts.keySet());
    selected.removeAll(settings.list("excludedMountPoints")); List<ProbeCheck> checks=new ArrayList<>();
    for(String mount:selected) {
      DiskUsage usage=mounts.get(mount);
      checks.add(new ProbeCheck("DISK","挂载点磁盘容量",mount,usage!=null && usage.percent()<threshold,usage==null?"指定挂载点不存在":"总量 "+usage.total()+" KiB，已用 "+usage.used()+" KiB，可用 "+usage.available()+" KiB，使用率 "+usage.percent()+"%，阈值 "+threshold+"%",duration));
    }
    if(checks.isEmpty()) checks.add(new ProbeCheck("DISK","挂载点磁盘容量","磁盘",false,"未找到可检查的挂载点",duration));
    return checks;
  }

  private record DiskUsage(String total,String used,String available,int percent) {}

  // 只接受配置的 SHA256 指纹；未知或变更的主机密钥始终拒绝。
  private static final class FingerprintRepository implements HostKeyRepository {
    private final String expected;
    FingerprintRepository(String fingerprint) { expected=fingerprint.replace("=",""); }
    public int check(String host,byte[] key) {
      try { String actual="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key)); return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),actual.getBytes(StandardCharsets.US_ASCII))?OK:CHANGED; }
      catch(Exception e) { return NOT_INCLUDED; }
    }
    public void add(HostKey key,UserInfo info) { }
    public void remove(String host,String type) { }
    public void remove(String host,String type,byte[] key) { }
    public String getKnownHostsRepositoryID() { return "配置的 SHA256 指纹"; }
    public HostKey[] getHostKey() { return new HostKey[0]; }
    public HostKey[] getHostKey(String host,String type) { return new HostKey[0]; }
  }
}
