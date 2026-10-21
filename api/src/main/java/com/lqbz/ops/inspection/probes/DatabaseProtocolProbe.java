package com.lqbz.ops.inspection.probes;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.inspection.*;
import java.net.URI;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class DatabaseProtocolProbe implements ProtocolProbe {
  public boolean supports(String type) { return "MYSQL".equalsIgnoreCase(type) || "POSTGRESQL".equalsIgnoreCase(type); }
  public List<ProbeCheck> check(Asset asset, ProbeSettings settings) {
    long start=System.nanoTime();
    try {
      int timeout=RedisProtocolProbe.timeout(settings); boolean mysql="MYSQL".equalsIgnoreCase(asset.getType());
      if(!supports(asset.getType())) throw new IllegalArgumentException();
      URI endpoint=RedisProtocolProbe.endpoint(asset.getEndpoint(),mysql?3306:5432);
      String database=settings.text("database",mysql?"mysql":"postgres"),username=settings.text("username","");
      if(username.isBlank() || !database.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException();
      Properties props=new Properties(); props.setProperty("user",username); props.setProperty("password",settings.secret("password"));
      int seconds=(timeout+999)/1000;
      if(mysql) {
        props.setProperty("connectTimeout",String.valueOf(timeout)); props.setProperty("socketTimeout",String.valueOf(timeout));
        props.setProperty("sslMode",settings.flag("tls")?"VERIFY_IDENTITY":"DISABLED");
        props.setProperty("allowPublicKeyRetrieval","false");
      } else {
        props.setProperty("connectTimeout",String.valueOf(seconds)); props.setProperty("socketTimeout",String.valueOf(seconds));
        props.setProperty("sslmode",settings.flag("tls")?"verify-full":"disable");
      }
      String host=endpoint.getHost(); if(host.contains(":") && !host.startsWith("[")) host="["+host+"]";
      String url="jdbc:"+(mysql?"mysql":"postgresql")+"://"+host+":"+endpoint.getPort()+"/"+database;
      try(Connection connection=DriverManager.getConnection(url,props); Statement statement=connection.createStatement()) {
        statement.setQueryTimeout(seconds);
        try(ResultSet rows=statement.executeQuery("SELECT 1")) { boolean passed=rows.next() && rows.getInt(1)==1; return result(passed,passed?"数据库 SELECT 1 通过":"数据库查询结果异常",start); }
      }
    } catch(IllegalArgumentException e) { return result(false,"数据库配置无效",start); }
    catch(Exception e) { return result(false,"数据库连接、认证或查询失败",start); }
  }
  private List<ProbeCheck> result(boolean passed,String message,long start) { return List.of(new ProbeCheck("DATABASE","数据库 SELECT 1","数据库",passed,message,RedisProtocolProbe.elapsed(start))); }
}
