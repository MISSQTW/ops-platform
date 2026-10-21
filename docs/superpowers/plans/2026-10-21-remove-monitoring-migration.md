# Remove Monitoring Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除旧巡检计划和已有 HertzBeat Monitor 的迁移兼容能力，让系统只保留“服务 → 巡检计划 → HertzBeat 同步”的单一主流程。

**Architecture:** 前端移除迁移入口、组件和 API；后端删除迁移提案、分析、确认及旧计划准入分支。原迁移拓扑锁缩减并改名为普通监控拓扑锁，仅保留正常计划与服务变更所需的服务级事务锁。新增 Flyway 迁移删除不再使用的提案表。

**Tech Stack:** Vue 3、TypeScript、Spring Boot、Spring Data JPA、Flyway、PostgreSQL、JUnit 5。

**Spec:** 用户于 2026-10-21 在当前会话确认删除全部存量监控迁移和旧数据兜底功能，同时精简服务页与巡检计划空状态。

## Global Constraints

- 所有用户可见文案和代码注释使用中文。
- 不修改当前工作区中与本需求无关的已有改动。
- 保留普通巡检计划创建、编辑、启停、删除、执行和 HertzBeat 同步能力。
- 服务页不显示未加入计划的顶部提示。
- 空巡检计划页面只保留标题和新增按钮。

---

### Task 1: 固化普通计划准入行为

**Files:**
- Modify: `api/src/test/java/com/lqbz/ops/service/MonitoringOrdinaryAdmissionTest.java`
- Modify: `api/src/main/java/com/lqbz/ops/service/InspectionPlanPolicyService.java`

- [ ] 添加测试：服务即使存在历史 Monitor 标识，也允许直接创建普通巡检计划。
- [ ] 运行目标测试并确认旧逻辑导致失败。
- [ ] 删除旧计划与旧 Monitor 的迁移准入限制及待确认状态分支。
- [ ] 运行目标测试确认通过。

### Task 2: 删除后端迁移模块

**Files:**
- Delete: `api/src/main/java/com/lqbz/ops/domain/MonitoringMigrationProposal.java`
- Delete: `api/src/main/java/com/lqbz/ops/domain/MonitoringMigrationProposalRepository.java`
- Delete: `api/src/main/java/com/lqbz/ops/service/MonitoringMigrationConfirmationService.java`
- Delete: `api/src/main/java/com/lqbz/ops/service/MonitoringPolicyMigrationService.java`
- Delete: `api/src/main/java/com/lqbz/ops/web/MonitoringMigrationController.java`
- Modify: `api/src/main/java/com/lqbz/ops/web/ResourceController.java`
- Modify: `api/src/main/java/com/lqbz/ops/service/InspectionExecutionService.java`
- Modify: `api/src/main/java/com/lqbz/ops/service/InspectionPlanMonitoringSyncService.java`
- Modify: `api/src/test/**`

- [ ] 删除迁移专用测试与生产类。
- [ ] 删除迁移报告、旧 claim 接口和待确认计划分支。
- [ ] 更新受影响测试，使其只覆盖普通计划行为。
- [ ] 运行 API 测试。

### Task 3: 保留并重命名普通并发锁

**Files:**
- Rename: `api/src/main/java/com/lqbz/ops/service/MonitoringMigrationTopologyLock.java` → `MonitoringTopologyLock.java`
- Modify: `api/src/main/java/com/lqbz/ops/service/InspectionPlanPolicyService.java`
- Modify: `api/src/main/java/com/lqbz/ops/web/ResourceController.java`
- Modify: related tests

- [ ] 保留 `acquireServices` 服务级锁。
- [ ] 删除迁移专用全表锁 `acquire`。
- [ ] 更新依赖注入与测试构造。
- [ ] 运行锁相关计划测试。

### Task 4: 删除前端迁移入口并精简文案

**Files:**
- Modify: `web/src/App.vue`
- Modify: `web/src/api.ts`
- Delete: `web/src/MonitoringMigrationPanel.vue`
- Modify: `web/src/styles.css`

- [ ] 删除迁移状态、组件、API 类型和操作入口。
- [ ] 服务所属计划空值改为“未加入计划”。
- [ ] 监控提示改为“新增巡检计划后开始监控”。
- [ ] 删除服务页顶部未配置计划提示。
- [ ] 巡检计划空状态只显示“暂无巡检计划”和“新增巡检计划”按钮。
- [ ] 构建前端。

### Task 5: 删除数据库迁移提案表并更新文档

**Files:**
- Create: `api/src/main/resources/db/migration/V6__drop_monitoring_migration_proposals.sql`
- Modify: `README.md`

- [ ] 新增幂等 SQL 删除 `ops_monitoring_migration_proposal`。
- [ ] 删除 README 中存量迁移说明和旧计划兼容描述。
- [ ] 运行 Flyway/API 测试与完整前端构建。

