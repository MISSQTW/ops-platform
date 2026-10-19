---
epic: ../epics/hertzbeat-observability-loop.md
phase: acceptance
approved_revision: 7508fbab27ffeda65ac97ea9644b36207319a77eb2f69d7f03006564044a0667
current_item: null
next_action: 验证数字监控状态修复后的线上结果，保留 owner 验收驳回状态
blocked_by: null
item_progression: continuous
milestone_commit: authorized
remote_publish: manual
---
## 子项进度
- [x] ITEM-1
- [x] ITEM-2
- [x] ITEM-3
- [x] ITEM-4
- [x] ITEM-5
- [x] ITEM-6
- [x] ITEM-7

## 临时决策与证据
- 现有设计明确平台管理业务元数据、HertzBeat 负责采集和告警、平台读取并映射状态：`docs/superpowers/specs/2026-10-19-ops-platform-design.md`。
- 当前代码已实现资产推送和 monitorId 映射，但未实现状态、告警、指标回写。
- 本机 HertzBeat 版本为 2.0，已通过真实容器验证监控 CRUD API。
- 首轮独立设计审查要求补齐明确告警恢复证据、告警双状态、同步互斥与运行记录、stale 阈值、状态权威边界、指标契约、标签归属及异常验收矩阵；已在修订版 Epic 中收敛。
- 第二轮设计复审无 blocking，要求继续明确映射失效后的写入权、指标规范化单位和当前单管理员系统中的导航验收构造；修订版已增加监控模式/映射生命周期、固定单位、会话能力位 fixture，并补充全局锁键与外部清理重试语义。
- 第三轮同 lineage design review 已通过，无 blocking、important 或 nit；冻结 Epic SHA-256 为 `f8891f2a4363556635d21b5fd57abffa1ef109c6e0713e429085de8f63947ec3`。
- 当前目录不是 Git 仓库，首次 owner gate 的合法建议策略为 `item_progression: per-item`、`milestone_commit: manual`、`remote_publish: manual`。
- Owner 要求先初始化 Git 后连续实现；仓库已在 `main` 初始化，执行策略更新为 `continuous + authorized + remote manual`，基线提交为 `bcf966f`。
- 后端全量 41 个测试通过，包含真实 Spring 事务回滚测试：投影事务失败后，`REQUIRES_NEW` 运行记录仍保留并落为 `FAILED`。
- 前端 `npm run build` 通过；仅保留 Vite 现有大 chunk 警告，无构建错误。
- 最新 JAR 已重建并更新 Docker API 镜像；`api`、`web`、PostgreSQL、Redis、HertzBeat、VictoriaMetrics 均处于运行状态，API 健康检查通过。
- 在线验收：平台与 HertzBeat 首页均返回 200；认证连通状态为正常；手动同步运行 `id=47` 为 `SUCCESS`，更新 3 个资产；最近定时运行连续成功。
- 在线验收：3 个资产均为 `HERTZBEAT + MANAGED`；PostgreSQL 为 `UP` 且指标新鲜，两个当前无新样本的 HTTP 资产为 `UNKNOWN + stale`，未误判为 `DOWN`。
- 在线验收：PostgreSQL 详情返回 availability、responseTime（`ms`）和最近一小时 59 个趋势点；管理员会话返回 `canManageIntegrations=true`。
- HertzBeat 停机降级、最后有效指标缓存、重复同步、重启补偿、监控删除幂等和外部清理重试已在前序实现验证及自动化测试中覆盖。
- 首轮 final acceptance review 对提交 `6d02cf9` 给出 1 个 blocking、3 个 important：外部监控 404 未失效映射、最新指标缺少单项错误隔离、指标级 stale 未输出、清理失败未进入同步运行记录。
- 验收修复已增加可识别的 `MonitorNotFoundException` 并将资产置为 `INVALIDATED + UNKNOWN`；当前指标逐项解析并保留其他有效值，同时返回解析错误摘要。
- 监控详情现独立返回 `metricsCached`、`metricsCollectedAt`、`metricsStale`；前端按指标采集时间展示 180 秒陈旧提示。
- 同步协调器现重试到期清理任务，并将全部仍为 `PENDING` 的清理失败写入本次运行错误摘要，直到清理成功。
- 修复后后端全量 45 个测试通过，前端生产构建通过；最新 Docker 在线运行 `id=64` 为 `SUCCESS`，指标详情返回 `metricsStale=false`、59 个趋势点。
- final acceptance reviewer 对完整范围 `bcf966f..7e3b558` 复审通过：无 blocking、important、nit；前轮 1 个 blocking 和 3 个 important 均 resolved，可进入 owner 最终接受 gate。
- Owner 验收未通过：`UP` 被旧逻辑当作异常，`UNKNOWN + 数据已陈旧` 文案无法说明无采样与明确故障的区别。
- 根因是仪表盘和前端异常筛选仍沿用 `status != HEALTHY`，与 HertzBeat 的 `UP/DOWN/UNKNOWN` 状态模型不一致；因此 1 个 `UP` 和 2 个 `UNKNOWN` 被全部计为异常。
- 修复后统一语义：`UP/HEALTHY` 为可用，只有 `DOWN/CRITICAL/FAILED` 为明确异常，`UNKNOWN` 为待采样；无采集时间时显示“暂无采样”，已有旧采样时才显示“数据已陈旧”。
- 新增异常状态语义回归测试；后端全量 46 个测试通过，前端生产构建通过；Docker 在线汇总由异常服务 `3` 修正为 `0`。
- 当前两个 HTTP 资产 `host.docker.internal:7788/4173` 的 HertzBeat 原始指标响应为 `query metrics data is empty`，所以保持 `UNKNOWN/待采样`；PostgreSQL 有近期采样，保持 `UP/可用`。
- 状态语义复审指出详情抽屉仍把从未采样描述为“超过 180 秒未更新”；现已按 `metricsCollectedAt` 分支，无采样显示“暂无指标采样，当前状态为待采样”，有旧采样且超时才显示“数据已陈旧”。
- 详情文案修复后前端生产构建和 Docker Web 重建通过。
- 进一步根因核查推翻此前“两个 HTTP 资产只是待采样”的结论：本机运行包 `CommonConstants` 明确定义 `MONITOR_UP_CODE=1`、`MONITOR_DOWN_CODE=2`、`MONITOR_PAUSED_CODE=0`。两个 HTTP 监控返回 status=2，适配器此前忽略此字段，错误输出 UNKNOWN。
- 已按本机实际常量补齐数字状态映射，保留指标时间读取且不让历史非空指标覆盖明确 DOWN；新增数字 UP/DOWN 回归测试，后端 48 项测试及打包通过。
- 从 HertzBeat 容器实际请求 `host.docker.internal:7788/health` 和 `host.docker.internal:4173/` 均返回 Connection refused。应显示两个不可用服务，不能将异常数量 0 作为验收成功证据；前面的 0 仅证明统计公式更改，未证明状态适配正确。
- 最新容器在线验证：手动同步 SUCCESS、更新 3 个资产；libo-backend/libo-h5 均为 DOWN，libo-postgresql 为 UP 且采样新鲜，异常数量为 2。详情无采样提示已移除“当前状态待采样”的推断，UNKNOWN 统一显示“状态未知”。Owner 未接受，Epic 保持 active。
