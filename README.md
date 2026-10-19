# 观澜统一运维平台

面向多项目的免费开源运维平台 MVP，统一展示项目、服务器、数据库、Redis、消息队列、MinIO、健康接口、巡检和告警状态。

## 启动

```bash
docker compose up -d --build
```

## 服务地址

- 运维平台：http://localhost:8088
- HertzBeat：http://localhost:1157
- VictoriaMetrics：http://localhost:8428/vmui

## 本地开发

后端需要 Java 21：

```bash
cd api
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home mvn spring-boot:run
```

前端：

```bash
cd web
npm install
npm run dev
```

当前版本内置演示项目和资产数据。下一阶段可增加登录权限、资产增删改、HertzBeat API 同步、巡检编排和告警通知。
