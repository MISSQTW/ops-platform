# 统一运维平台 MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供一个一条命令启动、可管理多项目运维资产并展示巡检与告警状态的 MVP。

**Architecture:** Vue 管理端调用 Spring Boot REST API；PostgreSQL 保存业务数据，Redis 提供缓存基础设施；HertzBeat 和 VictoriaMetrics 作为独立监控组件编排。

**Tech Stack:** Java 21、Spring Boot 3、PostgreSQL、Redis、Vue 3、TypeScript、Vite、Nginx、Docker Compose、HertzBeat、VictoriaMetrics

**Spec:** `docs/superpowers/specs/2026-10-19-ops-platform-design.md`

## Global Constraints

- 所有组件必须支持 Docker Compose 启动。
- 页面文案和代码注释使用中文。
- MVP 不实现生产级认证，但保留后续权限接入边界。
- 平台业务数据和监控时序数据分离存储。

---

### Task 1: 平台后端

**Files:**
- Create: `api/pom.xml`
- Create: `api/src/main/java/com/lqbz/ops/OpsPlatformApplication.java`
- Create: `api/src/main/java/com/lqbz/ops/domain/*`
- Create: `api/src/main/java/com/lqbz/ops/web/*`
- Test: `api/src/test/java/com/lqbz/ops/web/DashboardControllerTest.java`

**Interfaces:**
- Produces: `GET /api/dashboard/summary`、`GET /api/projects`、`GET /api/assets`、`GET /api/inspections`、`GET /api/alerts`

- [ ] 编写仪表盘和项目接口失败测试。
- [ ] 运行测试并确认接口尚不存在。
- [ ] 实现实体、仓储、演示数据和 REST API。
- [ ] 运行测试并确认通过。

### Task 2: 管理前端

**Files:**
- Create: `web/package.json`
- Create: `web/src/App.vue`
- Create: `web/src/api.ts`
- Create: `web/src/styles.css`

**Interfaces:**
- Consumes: Task 1 提供的 REST API。
- Produces: 仪表盘、项目、资产、巡检和告警五个视图。

- [ ] 定义 API 类型和请求客户端。
- [ ] 实现导航、指标卡片和数据表格。
- [ ] 增加加载、空数据和失败状态。
- [ ] 执行类型检查和生产构建。

### Task 3: 容器编排

**Files:**
- Create: `compose.yaml`
- Create: `api/Dockerfile`
- Create: `web/Dockerfile`
- Create: `web/nginx.conf`
- Create: `.env.example`
- Create: `README.md`

**Interfaces:**
- Produces: `http://localhost:8088` 管理端、`http://localhost:1157` HertzBeat、`http://localhost:8428` VictoriaMetrics。

- [ ] 配置 PostgreSQL、Redis、API 和 Web 服务。
- [ ] 加入 HertzBeat 与 VictoriaMetrics 服务。
- [ ] 配置健康检查与持久化卷。
- [ ] 构建并启动全部容器，验证页面和 API。
