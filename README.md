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

### 如何理解状态

| 页面状态 | 含义 |
| --- | --- |
| 可用（UP） | HertzBeat 最近一次检查通过；HTTP 检查或端口连通不代表全部业务功能正常 |
| 不可用（DOWN） | HertzBeat 已确认检查失败，计入异常服务 |
| 状态未知（UNKNOWN） | 当前无法判定可用性，例如监控停用或映射失效 |
| 暂无采样 | 没有可展示的指标样本，可用性仍以监控状态为准 |
| 数据已陈旧 | 指标采集时间超过 180 秒，或外部同步持续失败，需要检查采集和同步 |

异常数量与异常列表使用相同状态口径：`DOWN/CRITICAL/FAILED`；`UP` 不计为异常，未知状态也不代表已经确认故障。资产状态与告警事件分别同步：告警事件需要 HertzBeat 实际产生告警，状态变为不可用不会由平台伪造一条外部告警。阈值和通知渠道在 HertzBeat 中配置。

当前平台只有管理员账号。组件入口通过 `canManageIntegrations` 会话能力控制显示，进入 HertzBeat 后仍使用 HertzBeat 自身账号认证，不提供自动登录或认证绕过。

## 默认账号

- 联乾：`admin / admin123`
- HertzBeat：`admin / hertzbeat`

生产部署必须通过环境变量修改默认密码。
