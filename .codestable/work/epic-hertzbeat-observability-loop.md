---
epic: ../epics/hertzbeat-observability-loop.md
phase: executing
approved_revision: 7508fbab27ffeda65ac97ea9644b36207319a77eb2f69d7f03006564044a0667
current_item: ITEM-1
next_action: 实现 HertzBeat 外部契约、监控模式、映射生命周期和指标规范化模型
blocked_by: null
item_progression: continuous
milestone_commit: authorized
remote_publish: manual
---
## 子项进度
- [ ] ITEM-1
- [ ] ITEM-2
- [ ] ITEM-3
- [ ] ITEM-4
- [ ] ITEM-5
- [ ] ITEM-6
- [ ] ITEM-7

## 临时决策与证据
- 现有设计明确平台管理业务元数据、HertzBeat 负责采集和告警、平台读取并映射状态：`docs/superpowers/specs/2026-10-19-ops-platform-design.md`。
- 当前代码已实现资产推送和 monitorId 映射，但未实现状态、告警、指标回写。
- 本机 HertzBeat 版本为 2.0，已通过真实容器验证监控 CRUD API。
- 首轮独立设计审查要求补齐明确告警恢复证据、告警双状态、同步互斥与运行记录、stale 阈值、状态权威边界、指标契约、标签归属及异常验收矩阵；已在修订版 Epic 中收敛。
- 第二轮设计复审无 blocking，要求继续明确映射失效后的写入权、指标规范化单位和当前单管理员系统中的导航验收构造；修订版已增加监控模式/映射生命周期、固定单位、会话能力位 fixture，并补充全局锁键与外部清理重试语义。
- 第三轮同 lineage design review 已通过，无 blocking、important 或 nit；冻结 Epic SHA-256 为 `f8891f2a4363556635d21b5fd57abffa1ef109c6e0713e429085de8f63947ec3`。
- 当前目录不是 Git 仓库，首次 owner gate 的合法建议策略为 `item_progression: per-item`、`milestone_commit: manual`、`remote_publish: manual`。
