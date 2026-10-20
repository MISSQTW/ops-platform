---
epic: ../epics/inspection-policy-hertzbeat-loop.md
phase: executing
approved_revision: dc3ee008133559e332fa9bfbab9366fd5f68bcd31f907a61152b15408853b1a8
current_item: ITEM-2
next_action: 冻结 HertzBeat 2.0 Monitor 与告警规则真实配置契约，扩展客户端 CRUD 和契约测试
blocked_by: null
item_progression: continuous
milestone_commit: authorized
remote_publish: manual
---
## 子项进度
- [x] ITEM-1
- [ ] ITEM-2
- [ ] ITEM-3
- [ ] ITEM-4
- [ ] ITEM-5
- [ ] ITEM-6
- [ ] ITEM-7

## 临时决策与证据
- Owner 已确认一个项目对应一个被运维系统。
- Owner 已确认不同频率的服务应拆分到不同巡检计划。
- Owner 已纠正早期双执行器方案：巡检计划频率必须控制 HertzBeat Monitor 采集频率，异常服务由 HertzBeat 产生告警并回流平台。
- Owner 已确认服务页移除同步监控按钮，巡检计划保存后自动同步，并提供单计划手动同步补偿入口。
- 当前 `HertzBeatClient.sync` 将 `intervals` 固定为 60，尚未读取巡检计划。
- 当前 `InspectionExecutionService` 仍按平台计划周期执行本地 TCP/HTTP 检查，与新目标冲突。
- 现有 `.codestable/epics/hertzbeat-observability-loop.md` 已完成状态、指标和告警回流基础能力并处于 acceptance，本 Epic 作为后续能力，不修改其冻结契约。
- 首轮 design review 由 Wegener 执行，指出删除补偿、迁移矩阵、版本化持久任务、数据库唯一 binding、操作矩阵和 ITEM-7 owning skill 等 blocking；当前修订已逐项编码到永久 Epic，待同 lineage 复审。
- 第二轮复审确认所有 blocking 已解决，并新增三个 important：重新启用不得抢占 binding、同步需校准外部漂移、同 ID 告警重开必须有可证明的新 firing 周期；当前修订已逐项编码，待第三轮复审。
- 第三轮同 lineage design review 审查 SHA-256 `963bd876632f3badcedc30ca57cb007cdac6c655839448d5a55b66f904e9fa5f`，无 blocking、important 或 nit，proposed Epic 可交 owner 确认。
- 批准前 AFK 调查：本机 HertzBeat 2.0 正在运行；真实 API `GET /api/alert/defines` 返回规则分页，`GET /api/alert/define/{id}` 返回单条规则，`POST/PUT/DELETE /api/alert/define` 由 `AlertDefineController` 提供。
- 本机发行包 `AlertDefineDTO` 字段包含 `name`、`type`、`expr`、`period`、`times`、`labels`、`annotations`、`template`、`enable`、`datasource`、`queryLanguage`；ITEM-2 需通过真实创建和回读冻结可用性表达式、`period/times` 连续失败语义及启停行为。
- 当前仓库 `HertzBeatClient.sync` 仍固定 `monitor.intervals=60`，进一步证明 ITEM-2 必须把计划固定间隔纳入 Monitor 期望配置。
- Owner 于 2026-10-20 明确确认冻结契约及 `continuous + authorized + remote manual` 执行策略；永久 Epic 转为 active，批准 SHA-256 为 `dc3ee008133559e332fa9bfbab9366fd5f68bcd31f907a61152b15408853b1a8`。
- ITEM-1 TDD：`InspectionPolicyModelTest` 先因实体、仓储和构造器缺失编译失败，补实现后通过；`ProjectAssetCrudTest` 与 `InspectionPlanPolicyApiTest` 先因新字段、冲突映射和结构化计划请求缺失失败，补应用服务和兼容 DTO 后通过；`MonitoringPolicyMigrationServiceTest` 先因 dry-run 服务缺失失败，补实现后通过。
- ITEM-1 验证：沙箱外执行 `mvn -q test`，57 项测试全部通过；沙箱内同命令仅因 `HertzBeatClientTest` 无权监听本地端口失败，不是业务回归。
- ITEM-1 首轮 change review 的 blocking/important 已处理：结构化计划使用专用更新/逻辑删除接口；binding 以可释放的 `activeOwnerKey` 保证单一当前归属并保留历史；迁移报告不再按项目名猜测；增加分批认领入口、版本化持久任务骨架、项目关联删除保护、命名约束错误映射和不可变告警归属快照。
- ITEM-1 follow-up TDD：新增更新版本与移除服务、逻辑删除、固定迁移路由、项目关联删除、释放后跨计划重绑、同计划释放后再次加入测试；其中“同计划释放后再次加入”先复现未创建新 binding，再修正为仅按 `OWNED` 判断当前绑定和返回计划服务；迁移报告随后复现历史 binding 多结果异常，并改为按 `activeOwnerKey` 查询当前 owner。
- ITEM-1 PostgreSQL 迁移验证：从 `ops_platform_pre_document_20261020` 只读导出 schema 到唯一临时库 `ops_platform_migration_check_20261020_1552`，V2 成功补齐项目字段、binding/task 表和告警字段；全新临时库首轮暴露不存在 `ops_project` 时预检查仍解析查询，改为嵌套条件后 `ops_platform_fresh_check_20261020_1552` 成功执行。
- ITEM-1 最新完整验证：2026-10-20 沙箱外执行 `mvn -q test` 退出码 0；目标测试 `InspectionPolicyModelTest,ProjectAssetCrudTest,InspectionPlanPolicyApiTest,MonitoringPolicyMigrationServiceTest` 退出码 0。
- ITEM-1 第二轮 change review：同 lineage reviewer 对 staged SHA-256 `051bc428359b130191d3c3140695fda6f6d87937f7cc3f97f4b5701d7215780b` 复审，确认多数首轮 finding 已解决，并指出旧 PUT/DELETE 绕过、删除已绑定服务、重复 Monitor 认领、待释放撤销四个 blocking；新增项目删除竞态和迁移目标状态 important。
- ITEM-1 第二轮修复：旧接口按数据库中计划类型拒绝结构化计划；活动 binding 阻止服务物理删除；迁移认领事务内重验 Monitor 唯一性、当前 owner 和计划 ACTIVE/ENABLED；同计划重新加入时恢复 PENDING_RELEASE binding；项目删除事务提升为 SERIALIZABLE，降低检查后并发插入窗口；删除不安全的 `findByAssetId` 单结果查询。
- ITEM-1 第三轮 change review：同 lineage reviewer 对 staged SHA-256 `2d87690c69e245f84fbb88f07a1544c206ef19cc3e0f90df8e390e53c4a2d0ae` 完成最终轮复审；第二轮四个 blocking 均 resolved，但新增“停用计划与 binding 期望矛盾”“DELETING 计划仍可更新”两个 blocking。由于本阶段已达三轮上限，不再追加 reviewer 轮次，需 owner 裁决收口方式。
- ITEM-1 第三轮后修复：binding 新增按计划状态对齐 Monitor/规则期望，DISABLED 保留所有权但目标改为 DISABLED，重新启用恢复 ENABLED；更新接口拒绝非 ACTIVE 生命周期；告警首次关联时通过 active binding 和计划写入不可变项目/计划/服务快照；PostgreSQL 序列化冲突映射为可重试 409。新增启停、删除中保护、快照不可变和冲突映射测试。
- ITEM-1 第三轮后完整验证：沙箱外 `mvn -q test` 退出码 0；待 owner 决定是否接受修复后直接创建里程碑提交，或要求新的独立审查阶段。
- Owner 选择方案 2：第三轮后的新增修复开启新的独立 change review 阶段。fresh reviewer Pauli 审查 staged SHA-256 `587f08e4c2d4385d8680fa50068b9692288ae08448908cf238d6b8d2b9571f69`，确认启停、生命周期、快照和 409 映射正确，新增一个 blocking：有关联数据的项目改名会撕裂 `projectId/projectName` 归属；一个 important：结构化计划状态未限制枚举。
- 新审查阶段首轮修复：项目名称实际变化时，如旧名称仍有关联服务/计划或项目 ID 被结构化计划引用则返回 409；项目删除同时按 `projectId` 检查结构化计划；结构化计划创建和更新只接受 `ENABLED/DISABLED`。对应测试先复现 200/201 错误结果，修复后目标测试和完整 `mvn -q test` 均通过。
- 新审查阶段第二轮复审：Pauli 审查完整 staged SHA-256 `c78740f04d3c18aa8b2190c37733ba32ad17a15e2ef7fcf4764230461450be31`，首轮 blocking/important 均 resolved，无 unresolved、new blocking、important 或 nit；ITEM-1 change review gate 通过。
- ITEM-1 最终验证：沙箱外 `mvn -q test` 共 75 项，0 failures/errors/skipped；`git diff --cached --check` 通过；真实 PostgreSQL 已完成旧 schema 升级与 fresh schema 执行验证。
