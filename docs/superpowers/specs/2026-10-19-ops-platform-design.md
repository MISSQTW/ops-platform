# 统一运维平台 MVP 设计

## 目标

构建一个可自部署的多项目统一运维平台，集中管理项目、环境、资产、巡检任务和告警事件，并为后续接入 HertzBeat 与 VictoriaMetrics 保留稳定边界。

## 架构

- `web`：Vue 3 + TypeScript 管理界面。
- `api`：Spring Boot 3 REST API，负责项目、资产、巡检和告警业务。
- `postgres`：保存平台业务数据。
- `redis`：为后续任务调度、缓存和事件队列提供基础设施。
- `hertzbeat`：负责具体监控采集和告警计算。
- `victoriametrics`：保存监控时序数据。

## MVP 范围

- 仪表盘：项目数、资产数、异常资产、未恢复告警、巡检通过率。
- 项目管理：项目、负责人、描述、运行状态。
- 环境管理：开发、测试、预发布、生产环境。
- 资产管理：服务器、数据库、Redis、消息队列、MinIO、HTTP 服务。
- 巡检中心：巡检记录、状态、耗时、结果摘要。
- 告警中心：级别、对象、告警内容、发生时间和恢复状态。
- 集成入口：展示 HertzBeat、VictoriaMetrics 服务地址。

## 数据流

平台 API 管理业务元数据。监控对象后续通过集成适配器同步至 HertzBeat；HertzBeat 将指标写入 VictoriaMetrics；平台读取监控状态并映射为资产状态、巡检结果和告警事件。

## 错误处理

API 对参数错误返回 `400`，资源不存在返回 `404`，内部错误返回统一 JSON 错误结构。前端在接口不可用时显示明确错误状态。

## 验证

- 后端使用 Spring Boot 集成测试验证仪表盘与项目列表接口。
- 前端执行 TypeScript 类型检查和生产构建。
- Docker Compose 健康检查验证 PostgreSQL、Redis、API、Web 与监控组件。
