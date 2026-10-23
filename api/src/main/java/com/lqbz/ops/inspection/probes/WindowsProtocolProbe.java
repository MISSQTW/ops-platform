package com.lqbz.ops.inspection.probes;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.inspection.*;
import java.net.URI;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import javax.net.ssl.SSLContext;

/** Windows 登录与固定磁盘的只读巡检，由 SERVER 探测器根据操作系统委托。 */
public final class WindowsProtocolProbe implements ProtocolProbe {
  // 不拼接盘符、用户名或其他用户输入；选择与排除逻辑全部在本地执行。
  static final String DISK_SCRIPT="$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; [Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false); "
      +"Get-CimInstance -ClassName Win32_LogicalDisk -Filter 'DriveType=3' | ForEach-Object { "
      +"[Console]::WriteLine(('{0}|{1}|{2}' -f $_.DeviceID, $_.Size, $_.FreeSpace)) }";
  private final SSLContext context;
  public WindowsProtocolProbe() { context=null; }
  WindowsProtocolProbe(SSLContext context) { this.context=context; }
  public boolean supports(String type) { return "SERVER".equalsIgnoreCase(type); }

  public List<ProbeCheck> check(Asset asset,ProbeSettings settings) {
    long started=System.nanoTime();
    try {
      validateSettings(asset.getEndpoint(),settings,true);
      URI endpoint=endpoint(asset.getEndpoint());
      String username=settings.text("username","").trim(),password=settings.secret("password");
      String auth=settings.text("winrmAuth","NTLM").toUpperCase(Locale.ROOT),domain=settings.text("domain","").trim();
      int timeout=settings.timeoutMillis();
      if("NTLM".equals(auth) && username.contains("\\")) {
        String[] parts=username.split("\\\\",2); if(domain.isEmpty()) domain=parts[0]; username=parts[1];
        if(username.isEmpty()) throw new IllegalArgumentException("Windows 用户名不能为空");
      }
      String output;
      try(WinrmTransport transport=new WinrmTransport(endpoint,username,password,domain,auth,timeout,context)) { output=transport.readDisks(); }
      long duration=elapsed(started); List<ProbeCheck> checks=new ArrayList<>();
      checks.add(new ProbeCheck("WINRM","Windows 登录认证","WinRM HTTPS",true,"TLS 证书校验与 Windows 登录认证通过",duration));
      checks.addAll(diskChecks(output,settings,duration)); return checks;
    } catch(IllegalArgumentException exception) { return failure("Windows 配置无效：请检查 HTTPS 地址、认证、凭据、盘符和阈值",started); }
    catch(Exception exception) {
      if(exception instanceof InterruptedException) Thread.currentThread().interrupt();
      return failure("WinRM 连接、TLS 校验、认证或磁盘命令执行失败",started);
    }
  }
  public static void validateSettings(String address,ProbeSettings settings,boolean enabled) {
    endpoint(address);
    String auth=settings.text("winrmAuth","NTLM"),username=settings.text("username","").trim();
    if(!"NTLM".equals(auth) && !"BASIC".equals(auth)) throw new IllegalArgumentException("Windows 认证方式必须为 NTLM 或 BASIC");
    int timeout=settings.timeoutMillis();
    if(timeout<1000 || timeout>10000) throw new IllegalArgumentException("检查超时必须为 1000 至 10000 毫秒");
    threshold(settings); drives(settings,"driveLetters"); drives(settings,"excludedDriveLetters");
    if(enabled && (username.isEmpty() || settings.secret("password").isEmpty())) throw new IllegalArgumentException("Windows 登录需要用户名和密码");
    if("NTLM".equals(auth) && username.contains("\\")) {
      String[] parts=username.split("\\\\",-1);
      if(parts.length!=2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) throw new IllegalArgumentException("NTLM 用户名格式应为 DOMAIN\\user");
      String domain=settings.text("domain","").trim();
      if(!domain.isEmpty() && !domain.equalsIgnoreCase(parts[0])) throw new IllegalArgumentException("NTLM 域名与用户名中的域名不一致");
    }
  }
  static URI endpoint(String value) {
    URI raw;
    try { raw=URI.create(value.contains("://")?value:"https://"+value); }
    catch(IllegalArgumentException exception) { throw new IllegalArgumentException("Windows 地址必须为有效的 WinRM HTTPS 地址"); }
    if(!"https".equalsIgnoreCase(raw.getScheme()) || raw.getHost()==null || raw.getRawUserInfo()!=null || raw.getRawQuery()!=null || raw.getRawFragment()!=null || (raw.getPort()!=-1 && (raw.getPort()<1 || raw.getPort()>65535))) throw new IllegalArgumentException("Windows 地址必须为有效的 WinRM HTTPS 地址");
    String path=raw.getPath(); if(path!=null && !path.isEmpty() && !"/".equals(path) && !"/wsman".equals(path)) throw new IllegalArgumentException("WinRM HTTPS 地址路径必须为 /wsman");
    try { return new URI("https",null,raw.getHost(),raw.getPort()==-1?5986:raw.getPort(),"/wsman",null,null); }
    catch(Exception exception) { throw new IllegalArgumentException("Windows 地址必须为有效的 WinRM HTTPS 地址"); }
  }
  static List<ProbeCheck> diskChecks(String output,ProbeSettings settings,long duration) {
    int threshold=threshold(settings); Map<String,DiskUsage> disks=new LinkedHashMap<>();
    for(String line:output.split("\\r?\\n")) {
      if(line.trim().isEmpty()) continue;
      String[] fields=line.trim().split("\\|",-1);
      if(fields.length!=3 || !fields[0].matches("[A-Za-z]:") || !fields[1].matches("[0-9]+") || !fields[2].matches("[0-9]+")) throw new IllegalArgumentException("Windows 磁盘响应格式无效");
      BigDecimal total=new BigDecimal(fields[1]),free=new BigDecimal(fields[2]);
      if(total.signum()<=0 || free.signum()<0 || free.compareTo(total)>0) continue;
      BigDecimal percent=total.subtract(free).multiply(BigDecimal.valueOf(100)).divide(total,2,RoundingMode.HALF_UP);
      disks.put(fields[0].toUpperCase(Locale.ROOT),new DiskUsage(total,free,percent));
    }
    Set<String> selected=drives(settings,"driveLetters"); if(selected.isEmpty()) selected.addAll(disks.keySet());
    selected.removeAll(drives(settings,"excludedDriveLetters")); List<ProbeCheck> checks=new ArrayList<>();
    for(String drive:selected) {
      DiskUsage disk=disks.get(drive);
      boolean passed=disk!=null && disk.total.subtract(disk.free).multiply(BigDecimal.valueOf(100)).compareTo(disk.total.multiply(BigDecimal.valueOf(threshold)))<0;
      checks.add(new ProbeCheck("DISK","Windows 磁盘容量",drive,passed,disk==null?"指定盘符不存在或容量无效":"总量 "+disk.total.toPlainString()+" 字节，可用 "+disk.free.toPlainString()+" 字节，使用率 "+disk.percent.stripTrailingZeros().toPlainString()+"%，阈值 "+threshold+"%",duration));
    }
    if(checks.isEmpty()) checks.add(new ProbeCheck("DISK","Windows 磁盘容量","磁盘",false,"未找到可检查的固定磁盘",duration));
    return checks;
  }
  private static Set<String> drives(ProbeSettings settings,String key) {
    Set<String> drives=new LinkedHashSet<>();
    for(String value:settings.list(key)) { if(!value.matches("[A-Za-z]:")) throw new IllegalArgumentException("Windows 盘符格式应为 C:，多个盘符用逗号分隔"); drives.add(value.toUpperCase(Locale.ROOT)); }
    return drives;
  }
  private static int threshold(ProbeSettings settings) { int value=settings.number("diskThreshold",80); if(value<1 || value>100) throw new IllegalArgumentException("磁盘使用率阈值必须为 1 至 100 的整数"); return value; }
  private static long elapsed(long start) { return (System.nanoTime()-start)/1000000L; }
  private static List<ProbeCheck> failure(String message,long start) { return Collections.singletonList(new ProbeCheck("WINRM","Windows 登录认证与磁盘命令","WinRM HTTPS",false,message,elapsed(start))); }
  private static final class DiskUsage {
    final BigDecimal total,free,percent;
    DiskUsage(BigDecimal total,BigDecimal free,BigDecimal percent) { this.total=total; this.free=free; this.percent=percent; }
  }
}
