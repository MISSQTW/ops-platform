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

## 监控闭环

联乾是日常运维入口，HertzBeat 是后台采集与告警引擎：

1. 在“服务管理”维护资产，点击“同步资产”创建或更新 HertzBeat 监控任务。
2. 平台每 60 秒同步一次运行状态和告警，也可点击“同步监控数据”立即执行。
3. 资产列表展示 HertzBeat 主状态、最近成功同步时间和数据陈旧标识。
4. 点击资产操作栏中的监控图标，可查看可用性、响应时间和最近一小时趋势。
5. HertzBeat 不可用时平台保留最后有效数据，不影响项目、资产和巡检管理。

外部告警生命周期与平台处置状态分别保存。平台只在 HertzBeat 明确返回 `resolved` 时更新恢复状态，不根据活动列表缺席推断恢复。

当前平台只有管理员账号。组件入口通过 `canManageIntegrations` 会话能力控制显示，进入 HertzBeat 后仍使用 HertzBeat 自身账号认证，不提供自动登录或认证绕过。

## 默认账号

- 联乾：`admin / admin123`
- HertzBeat：`admin / hertzbeat`

生产部署必须通过环境变量修改默认密码。
