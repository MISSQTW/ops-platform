# 联乾运维平台升级 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有演示型运维平台升级为具备清晰品牌、执行记录、灵活周期、真实登录退出和 HertzBeat 同步基础能力的联乾运维平台。

**Architecture:** 保留 Spring Boot、Vue 和 PostgreSQL 架构。巡检周期改为结构化字段并由后端统一计算；认证使用后端签发的随机会话令牌；HertzBeat 通过独立客户端和同步接口隔离，外部组件不可用时不影响平台核心功能。

**Tech Stack:** Java 21、Spring Boot 3.4、Spring Data JPA、Vue 3、Element Plus、PostgreSQL、Docker Compose。

**Spec:** 本对话中用户于 2026-10-19 确认的品牌、执行记录、灵活周期、登录退出和 HertzBeat 接入需求。

## Global Constraints

- 所有用户界面使用中文，品牌统一为“联乾运维平台”。
- 保留现有项目、资产、巡检计划和执行记录。
- 生产环境默认启用认证，测试环境显式关闭认证。
- 外部 HertzBeat 不可用时返回明确错误，不阻塞项目、资产和巡检功能。
- 自动调度和立即执行继续共用同一巡检执行器。

---

### Task 1: 品牌与执行记录语义

**Files:**
- Modify: `web/src/App.vue`
- Modify: `web/src/styles.css`

**Interfaces:**
- Produces: “联乾运维平台”品牌、执行记录列表和“巡检详细结果”抽屉。

- [ ] 将侧栏品牌从“观澜”改为“联乾”，副标题改为“OPS PLATFORM”。
- [ ] 将“执行结果”统一改为“执行记录”。
- [ ] 在记录卡片明确展示执行时间、通过数、失败数、总数和耗时。
- [ ] 将详情抽屉标题改为“巡检详细结果”，保留逐项结果。
- [ ] 运行 `npm run build` 验证。

### Task 2: 结构化灵活周期

**Files:**
- Modify: `api/src/main/java/com/lqbz/ops/domain/InspectionPlan.java`
- Modify: `api/src/main/java/com/lqbz/ops/web/InspectionPlanRequest.java`
- Modify: `api/src/main/java/com/lqbz/ops/service/InspectionExecutionService.java`
- Modify: `api/src/test/java/com/lqbz/ops/web/InspectionCenterTest.java`
- Modify: `web/src/api.ts`
- Modify: `web/src/App.vue`

**Interfaces:**
- Produces: `scheduleType`、`intervalValue`、`intervalUnit`、`dailyTime`、`cronExpression` 字段。
- Produces: `calculateNextRun(InspectionPlan, LocalDateTime)`。

- [ ] 添加失败测试，覆盖每 N 分钟、每 N 小时、每天定时和 Cron 请求校验。
- [ ] 扩展巡检计划实体和请求对象，保留 `scheduleText` 作为展示字段和旧数据兼容字段。
- [ ] 实现结构化下次执行时间计算；Cron 使用 Spring `CronExpression`。
- [ ] 更新新增和编辑表单，使用模式选择、数字输入、单位选择、时间选择和 Cron 输入。
- [ ] 将现有中文周期在读取时兼容映射为结构化配置。
- [ ] 运行后端测试和前端构建。

### Task 3: 登录与退出

**Files:**
- Create: `api/src/main/java/com/lqbz/ops/auth/AuthController.java`
- Create: `api/src/main/java/com/lqbz/ops/auth/AuthFilter.java`
- Create: `api/src/main/java/com/lqbz/ops/auth/SessionService.java`
- Create: `api/src/main/java/com/lqbz/ops/config/WebConfiguration.java`
- Create: `api/src/test/java/com/lqbz/ops/auth/AuthControllerTest.java`
- Modify: `api/src/test/resources/application-test.yml`
- Modify: `web/src/api.ts`
- Modify: `web/src/App.vue`
- Modify: `compose.yaml`

**Interfaces:**
- Produces: `POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me`。
- Consumes: `Authorization: Bearer <token>`。

- [ ] 添加登录成功、错误密码、受保护接口和退出失效测试。
- [ ] 从环境变量读取管理员用户名、密码和显示名称，签发不可预测的随机会话令牌。
- [ ] 添加认证过滤器，放行登录接口和健康检查，保护其他 API。
- [ ] 新增登录视图、本地令牌存储、自动附加请求头和 401 自动回登录。
- [ ] 将右下角管理员区域改为菜单并提供退出登录。
- [ ] 在 Compose 中配置默认本地管理员凭据并允许环境变量覆盖。
- [ ] 运行后端测试和前端构建。

### Task 4: HertzBeat 同步基础能力

**Files:**
- Create: `api/src/main/java/com/lqbz/ops/integration/HertzBeatClient.java`
- Create: `api/src/main/java/com/lqbz/ops/integration/MonitoringIntegrationController.java`
- Create: `api/src/main/java/com/lqbz/ops/integration/MonitoringSyncResult.java`
- Create: `api/src/test/java/com/lqbz/ops/integration/MonitoringIntegrationControllerTest.java`
- Modify: `web/src/api.ts`
- Modify: `web/src/App.vue`
- Modify: `compose.yaml`

**Interfaces:**
- Produces: `GET /api/integrations/hertzbeat/status`。
- Produces: `POST /api/integrations/hertzbeat/assets/{id}/sync`。

- [ ] 基于 HertzBeat 官方 REST API 确认认证、监控类型和创建监控端点。
- [ ] 添加状态检测和资产同步失败测试。
- [ ] 实现类型映射与同步客户端，HTTP、PostgreSQL、Redis、Kafka、RabbitMQ、MinIO 使用对应监控类型。
- [ ] 在资产操作区增加“同步到 HertzBeat”，并展示连接状态和同步结果。
- [ ] 运行后端测试、前端构建和本地 HertzBeat 连通性验证。

### Task 5: 发布与端到端验证

**Files:**
- Modify: `README.md`（若存在）

**Interfaces:**
- Consumes: Tasks 1-4 的全部接口。

- [ ] 执行 `mvn test`，要求全部测试通过。
- [ ] 执行 `npm run build`，要求构建成功。
- [ ] 重建 API 和 Web 容器。
- [ ] 验证登录、退出、周期配置、立即执行、自动执行和执行记录详情。
- [ ] 验证现有“礼事随记”项目、3 个资产和巡检历史仍存在。
- [ ] 验证 HertzBeat 不可用时平台核心页面仍可使用。
