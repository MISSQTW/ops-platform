# 观澜统一运维平台

面向多项目的免费开源运维平台 MVP，统一展示项目、服务器、数据库、Redis、消息队列、MinIO、健康接口、巡检和告警状态。

## 启动

```bash
cd api && mvn -DskipTests package && cd ..
docker compose up -d --build
```

后端 Dockerfile 使用 `api/target` 中已打包的 JAR，更新后端代码后须先重新打包。打包需要 Java 21。

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

1. 在“项目中心”维护被运维系统及负责人、联系方式、运维到期日，再在“服务管理”维护服务。
2. 在“巡检中心”选择项目和服务，设置固定监控间隔、连续失败次数与告警级别。同一频率的服务可放进同一计划，不同频率拆分计划；一个服务只能属于一个启用计划。
3. 保存计划后自动同步 HertzBeat Monitor 和告警规则；计划页提供单计划手动同步和“同步全部计划”。频率范围为 10 秒至 24 小时。
4. 右上角刷新图标重新读取平台已保存的数据。平台每 60 秒同步一次运行状态和告警，也可在告警页点击“同步监控数据”立即执行。
5. 服务列表展示 HertzBeat 主状态、所属计划和数据陈旧标识。
6. 点击服务操作栏中的监控图标，可查看可用性、响应时间和最近一小时趋势。
7. HertzBeat 不可用时平台保留最后有效数据，不影响项目、服务和巡检管理。

外部告警生命周期与平台处置状态分别保存。平台只在 HertzBeat 明确返回 `resolved` 时更新恢复状态，不根据活动列表缺席推断恢复。告警可确认、添加备注；外部恢复后才可关闭。同一告警的新故障周期会重新打开，处置历史保留。

### 存量监控迁移

- `GET /api/inspection-plans/migration-report` 只读分析旧计划、Monitor 唯一归属及双方频率，不修改外部配置。
- `POST /api/inspection-plans/migration-proposals` 提交 `assetId`、`projectId`，必要时明确选择 `intervalSeconds`。准备阶段创建真实的待确认计划和绑定，保留 Monitor ID，但不启动同步任务；频率冲突不会自动选择权威值。
- `GET /api/inspection-plans/migration-proposals` 查询持久化提案；`POST /api/inspection-plans/migration-proposals/{id}/confirm` 重验现场后激活同一计划，重复确认返回原计划。
- 多个启用旧计划或重复 Monitor 映射禁止迁移。现场变化后须先调用 `POST /api/inspection-plans/migration-proposals/{id}/cancel` 释放预留，再重新准备；取消不会删除外部 Monitor。
- 旧计划保留历史和本地兜底，只有服务对应 Monitor、规则及配置版本全部就绪后才逐服务交出执行权。旧计划仍启用时，迁移目标计划不会再启动第二份本地巡检；旧计划停用或删除后，新计划才承担必要兜底。待确认计划禁止手动执行。
- 准备、确认、取消使用正式 PostgreSQL 的事务级表锁保护服务、计划、绑定和项目现场，提交后才进行外部同步。迁移期间配置写入可能短暂等待，获取锁超过 5 秒可重试；不会阻塞普通读取。H2 仅用于测试，不提供等价的生产迁移锁保证。

迁移并发与故障注入回归：先准备独立临时 PostgreSQL 数据库，再设置 `OPS_MIGRATION_TEST_DB_URL` 并运行 `mvn -f api/pom.xml -Dtest=MonitoringMigrationPostgresTest test`。测试库名必须以 `ops_platform_migration_test_` 开头；该测试使用建表/删表配置，绝不能指向业务库。未提供测试库时该组用例跳过，不能将跳过视为 PostgreSQL 验证通过。

### 如何理解状态

| 页面状态 | 含义 |
| --- | --- |
| 可用（UP） | HertzBeat 最近一次检查通过；HTTP 检查或端口连通不代表全部业务功能正常 |
| 不可用（DOWN） | HertzBeat 已确认检查失败，计入异常服务 |
| 状态未知（UNKNOWN） | 当前无法判定可用性，例如监控停用或映射失效 |
| 暂无采样 | 没有可展示的指标样本，可用性仍以监控状态为准 |
| 数据已陈旧 | 指标采集时间超过 180 秒，或外部同步持续失败，需要检查采集和同步 |

异常数量与异常列表使用相同状态口径：`DOWN/CRITICAL/FAILED`；`UP` 不计为异常，未知状态也不代表已经确认故障。服务状态与告警事件分别同步：告警事件需要 HertzBeat 实际产生告警。可用性告警的频率、连续失败次数和级别由巡检计划配置；通知渠道在 HertzBeat 中配置。

### 配置同步与数据回流

计划同步负责下发 Monitor 频率、启停与告警规则，后台每 30 秒校验并补偿配置漂移；告警页“同步监控数据”负责拉取状态和告警，后台默认每 60 秒执行。页面刷新只读取平台已保存的数据。

计划显示未同步、同步中、部分成功或失败时可手动补偿；外部不可用时保留期望配置并重试。Monitor 和规则都同步成功后，该服务停止平台本地周期检查；迁移中或同步失败的服务保留原检查兜底。

旧计划显示“待迁移”。先查看迁移报告，再通过迁移提案准备与确认接口操作；旧 `POST /api/inspection-plans/migration-claims` 返回 410。普通计划创建、增加服务及重新获取所有权不能绕过存量迁移检查。

服务删除与计划获取服务使用共同的服务级事务锁，避免删除后产生无法清理的绑定；仍有未清理绑定时禁止删除服务。停用计划删除或移除服务时，只有已由其他计划接管的 Monitor 才保留，无人接管则预留所有权并完成外部清理，失败后保留重试载体。清理历史 Monitor 不会清空后来重建的 Monitor 映射。

当前平台只有管理员账号。组件入口通过 `canManageIntegrations` 会话能力控制显示，进入 HertzBeat 后仍使用 HertzBeat 自身账号认证，不提供自动登录或认证绕过。

## 默认账号

- 联乾：`admin / admin123`
- HertzBeat：`admin / hertzbeat`

生产部署必须通过环境变量修改默认密码。

## 真实闭环验证

本机安装 Bash、curl、jq、Docker，启动平台后执行 `scripts/verify-hertzbeat-loop.sh`。脚本创建唯一测试项目和独立 HTTP 容器，验证频率下发、真实故障告警、恢复、人工确认关闭、同步失败重试和外部资源删除。

默认会短暂暂停现有 HertzBeat 容器；共享环境可设置 `VERIFY_OUTAGE=0` 跳过该步骤。当前脚本面向本仓库默认 Compose 名称与网络，使用默认测试账号，可通过脚本顶部环境变量覆盖账号和组件地址。脚本退出时按本轮创建的 ID 清理测试历史，需要访问本机 Docker 与测试 PostgreSQL。
