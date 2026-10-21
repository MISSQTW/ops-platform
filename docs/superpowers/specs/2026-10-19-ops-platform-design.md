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

平台 API 管理项目、服务和巡检计划。一个项目对应一个被运维系统；不同频率服务通过不同计划管理。计划保存事务写入期望版本、服务 binding 和持久同步任务，提交后下发 HertzBeat Monitor 与告警规则。

同步器先读取实际配置，再按最新期望版本修复频率、启停、规则和外部删除造成的漂移。binding 的活动所有权唯一约束防止同一服务被多个启用计划占用；任务版本检查防止旧配置覆盖新配置。Monitor 和规则全部同步成功后，本地周期检查退出该服务；地址测试不覆盖正式监控状态。

HertzBeat 执行正式采集和告警计算。平台默认每 60 秒回流状态与告警，配置 reconciliation 默认每 30 秒运行，二者独立。可用性规则按监控类型和 Monitor ID 限定，使用本机执行器的 `__available__` 状态变量。规则 CRUD 成功只能证明配置持久化，仍须用实际故障和恢复验证执行契约。

外部告警生命周期 `FIRING/RESOLVED` 与平台处置 `OPEN/ACKNOWLEDGED/CLOSED` 分开保存。只在明确恢复后允许关闭；相同外部 ID 的新故障周期重新打开，处置事件保留历史。项目、计划和服务快照保留告警发生时的归属。

VictoriaMetrics 作为独立时序组件部署；当前 Compose 未配置 HertzBeat 到 VictoriaMetrics 的写入连接，不能仅凭容器启动推断指标已写入该组件。

## 服务采集与告警契约

| 服务类型 | HertzBeat app | 默认端口 | 可用性含义 |
| --- | --- | --- | --- |
| HTTP | website | HTTP 80 / HTTPS 443 | 配置的路径与查询参数可完成 HTTP 采集 |
| SERVER | port | 22 | TCP 端口可连接 |
| MYSQL | port | 3306 | TCP 端口可连接 |
| POSTGRESQL | port | 5432 | TCP 端口可连接 |
| REDIS | port | 6379 | TCP 端口可连接 |
| KAFKA | port | 9092 | TCP 端口可连接 |
| RABBITMQ | port | 5672 | TCP 端口可连接 |
| ROCKETMQ | port | 9876 | TCP 端口可连接 |
| MINIO | port | 9000 | TCP 端口可连接 |

显式端口覆盖默认值。未知类型禁止进入启用计划；HTTP 地址必须包含 http/https 协议。非 HTTP 类型的端口可用只证明连接建立，不证明数据库查询、缓存命令、消息收发或对象读写正常。

上述两种 app 的 summary 均是 priority 0 采集，可用性由本机 HertzBeat `MetricsRealTimeAlertCalculator` 将采集结果映射为 `__available__=up/down`。连接失败、HTTP 采集错误或 6000ms 超时会进入 down；恢复成功采集进入 up。未收到采集数据时没有新的计算事件，平台保留最后状态并展示陈旧提示，不据此伪造故障或恢复。连续失败次数由同一规则的 times 控制，成功采集清除待触发累计；Monitor 重建产生新实例 ID，规则改绑新实例，从新计数开始，旧告警仍等待外部明确恢复事件。

规则类型必须为 `realtime_metric`，表达式必须同时限定 app、Monitor 实例 ID 和 down。计划间隔控制 Monitor intervals；配置一致性比较 host、port、uri（含查询参数）、ssl、timeout，并在下发后回读确认。HTTP 和端口契约分别有真实故障/恢复闭环证据，所有 port 类型共用同一采集与告警实现。

## Monitor 创建恢复

持久化服务缺少本地 Monitor ID 时，创建前按外部稳定 `assetId` 标签遍历监控列表；唯一匹配优先认领并更新，不按名称猜测，兼容已有缺少 `managedBy` 的历史标签。新写入同时标记 `managedBy=ops-platform`。重复匹配、明确属于其他管理端、列表失败或分页无法证明完整时停止写入并保留任务重试。最多查询 100 页、每页 100 条，超过上限须先治理外部监控规模，不在未知状态下继续创建。创建响应缺少 ID 时同样按标签重新核实。该协议解决外部创建成功、本地提交前崩溃造成 ID 丢失的恢复，不承诺外部 API 对任意并发手工创建提供全局唯一约束。

## 错误处理

API 对参数错误返回 `400`，资源不存在返回 `404`，内部错误返回统一 JSON 错误结构。前端在接口不可用时显示明确错误状态。

## 验证

- 后端使用 Spring Boot 集成测试验证仪表盘与项目列表接口。
- 前端执行 TypeScript 类型检查和生产构建。
- Docker Compose 健康检查验证 PostgreSQL、Redis、API、Web 与监控组件。
