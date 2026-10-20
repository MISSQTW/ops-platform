---
epic: ../epics/inspection-policy-hertzbeat-loop.md
phase: executing
approved_revision: dc3ee008133559e332fa9bfbab9366fd5f68bcd31f907a61152b15408853b1a8
current_item: ITEM-6
next_action: 完善项目档案与服务操作面，移除服务页同步入口并补齐计划同步状态展示
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
- ITEM-2 真实 HertzBeat 2.0 契约探测：当前 Monitor 列表回读字段包含 `id/jobId/name/app/instance/intervals/scheduleType/status/type/labels/description`；Monitor 实体校验注解确认固定周期最小值为 10 秒，状态 0=Paused、1=Up、2=Down；`port` 与 `website` 的 `summary` 均为 priority 0 可用性采集。
- ITEM-2 告警规则真实 CRUD：临时禁用规则 `ops-contract-probe-20261020` 使用 `type=realtime`、`expr=availability == 0`、`period/times`、labels/annotations/template、`datasource=hertzbeat` 成功 POST；PUT 将 period 1→5、times 2→3 并回读一致；DELETE 成功，删除后 GET 返回业务码 3 `AlertDefine not exist.`，临时规则已清理。
- ITEM-2 TDD：新增 `syncMonitor(asset, intervalSeconds, enabled)`，旧 `sync(asset)` 保留 60 秒启用兼容；新增 Monitor 实际配置读取、最小周期校验和外部删除异常；新增告警规则创建后按名称回查 ID、原 ID 更新、未知字段兼容、连续失败参数、读取和幂等删除。`HertzBeatClientTest` 与完整 `mvn -q test` 均通过。
- ITEM-2 首轮 change review：Godel 审查 staged SHA-256 `7f19ad984fcbbebaffec1feea62839ccfc997c75d490e43967682371f9c6122b`，指出创建规则前缺少按受管身份查重这一 blocking，以及 Monitor 回读缺 `instance/scheduleType/params/labels`、告警规则回读缺 `type/datasource/annotations`、Monitor 业务码 3 未映射为删除三个 important。
- ITEM-2 首轮修复：创建前按 `managedBy + planId + assetId` 查询唯一受管规则，已有规则复用 ID 执行 PUT，多个匹配规则拒绝同步；补齐 Monitor 与告警规则实际配置字段；Monitor 业务码 3 映射为 `MonitorNotFoundException`。创建测试按“首次查询为空、POST、再次查询出现规则”模拟真实流程，并暴露及修复 nullable `Long` 条件表达式自动拆箱问题。
- ITEM-2 修复后验证：沙箱外 `mvn -q -Dtest=HertzBeatClientTest test` 18 项通过；沙箱外完整 `mvn -q test` 退出码 0；`git diff --check` 通过。
- ITEM-2 第二轮 change review：同 lineage reviewer Godel 审查 staged SHA-256 `43451f60ce5285a9cc7274ec34d58177694eb717d692de5776062567ffb60954`；Monitor 与告警规则回读字段已 resolved，但规则查重只读第一页、`monitorStatus()` 未统一业务码 3 删除语义仍 unresolved，无新增 finding。
- ITEM-2 第二轮修复：受管规则查询遍历 HertzBeat 分页，并对缺少 `content` 的异常响应失败关闭；`monitorStatus()` 复用 `getMonitorDetails`，统一 HTTP 404、业务码 3 和 not-exist 文本的外部删除语义。新增第二页规则复用 PUT 和纯业务码 3 状态查询测试。
- ITEM-2 第二轮修复后验证：沙箱外 `mvn -q -Dtest=HertzBeatClientTest test` 20 项通过；沙箱外完整 `mvn -q test` 退出码 0；`git diff --check` 通过。
- ITEM-2 第三轮 change review：同 lineage reviewer Godel 审查 staged SHA-256 `50f2e429d1468a0a4aa8f50f2efe2a2977baad8519ae9c7931b27deb6ada147f`，确认分页查重、异常响应失败关闭和 Monitor 删除语义全部 resolved，无新增 blocking、important 或 nit；ITEM-2 gate 通过。
- ITEM-3 TDD：新增计划保存提交后自动同步、单服务失败不回滚其他服务、手动补偿、旧版本任务作废、外部漂移读取后修复、删除成功后释放 binding、崩溃 RUNNING 任务恢复和外部规则删除后重建测试；首轮因编排服务与任务查询缺失编译失败，补实现后 8 项定向测试通过。
- ITEM-3 实现：计划创建、更新、启停、移除服务、逻辑删除和迁移认领均写入版本化持久任务，并在事务提交后自动执行；新增单计划/全部计划手动同步接口、30 秒周期 reconciliation、指数退避重试、超时 RUNNING 恢复、旧版本作废、binding 悲观锁串行化、Monitor/规则先读后修和删除补偿。
- ITEM-3 兼容调整：自动同步成功后待释放 binding 会立即保留为 `RELEASED` 历史记录，既有 API 测试改为同时断言历史释放记录与新 `OWNED` 当前记录，不再假设保存后尚未执行外部清理。
- ITEM-3 当前验证：`InspectionPlanMonitoringSyncTest` 8 项通过；`InspectionPlanMonitoringSyncTest,InspectionPlanPolicyApiTest,InspectionPolicyModelTest,MonitoringPolicyMigrationServiceTest` 相关回归通过；沙箱外完整 `mvn -q test` 退出码 0；`git diff --check` 通过。
- ITEM-3 首轮 change review：fresh reviewer Ptolemy 审查 staged SHA-256 `ec87db62d5e1ff7b037abe9d2bda127696d8f5fa2ddab780501402ff02e08169`，指出停用后未释放与重启未原子抢占、批量任务同事务、服务资料未进入版本链三个 blocking，以及实际配置未比较/写后验证、任务缺数据库防重、删除未进入 `DELETED` 三个 important。
- ITEM-3 首轮修复：停用同步成功后 binding 转为 `DISABLED_RELEASED` 并释放 active owner，重新启用先检查全部冲突再统一获取所有权；任务通过 self proxy 使用 `REQUIRES_NEW` 独立提交；服务更新锁定关联 binding、递增计划期望版本并自动生成任务；Monitor/规则按真实字段比较，漂移时下发且 Monitor 写后回读验证；任务增加四字段数据库唯一约束并复用同版本任务；删除完成后计划进入 `DELETED`。
- ITEM-3 首轮修复测试：新增停用释放与重启冲突、服务资料版本、同版本任务防重和删除终态断言；测试桩按下发参数回显真实配置，确保写后验证不是形式断言。`InspectionPlanMonitoringSyncTest` 11 项、相关计划/模型/迁移回归及完整 `mvn -q test` 均通过，`git diff --check` 通过。
- ITEM-3 第二轮 change review：同 lineage reviewer Ptolemy 审查 staged SHA-256 `721fe0be8cc5c6a1983a94ed4f27545a6ef2e8f328fdf110323f7cef5b7a7d89`，确认独立事务、任务防重和删除终态 resolved；指出停用释放后旧计划仍参与 reconciliation、服务更新与任务执行锁顺序相反两个 blocking，以及 Monitor/规则部分成功状态被统一覆盖一个 important。
- ITEM-3 第二轮修复：`DISABLED_RELEASED` binding 不再自动或手动入队，避免与新 owner 争抢外部配置；服务更新收敛到策略服务，先锁 binding 再读取/修改 asset，与任务执行统一 `binding -> asset` 顺序；Monitor 与规则同步和删除按阶段分别写入实际状态，后续资源失败不再抹掉前序资源成功事实。
- ITEM-3 第二轮修复测试：新增新计划接管后旧停用计划 reconciliation 不下发禁用配置、Monitor 一致但规则失败、规则删除成功但 Monitor 删除失败三项测试。`InspectionPlanMonitoringSyncTest` 14 项和完整 `mvn -q test` 通过；`git diff --check` 通过。
- ITEM-3 PostgreSQL V3 验证：临时库 `ops_platform_v3_check_20261020` 执行 V2 后插入两条相同目标任务，V3 成功删除 1 条重复记录并建立 `uk_ops_monitoring_task_target` 唯一索引。
- ITEM-3 第三轮 change review：同 lineage reviewer Ptolemy 审查 staged SHA-256 `9196d7980f3ff96651030f74876c4dcc98a2ba5d41201ce121102d5b0e02ea1b`；分阶段状态 resolved，但事务内入队仍包含 `DISABLED_RELEASED`、控制器在 binding 锁前预读 asset 两项未完全解决，并新增旧计划清理可能删除新 owner 共享 Monitor 的 blocking。原审查阶段达到三轮上限。
- ITEM-3 三轮后修复：策略事务入队和服务资料变更均跳过 `DISABLED_RELEASED`；服务更新默认值解析移入 binding 锁之后且控制器取消外层事务与预读；失去所有权的旧 binding 移除/删除时 Monitor 目标改为 `DETACHED`，只清理旧计划专属规则，不删除或解绑新 owner 共用 Monitor。
- ITEM-3 三轮后测试：新增服务资料变化后旧停用计划不下发、接管后删除旧计划不删 Monitor、接管后从旧计划移除服务不删 Monitor；`InspectionPlanMonitoringSyncTest` 17 项和完整 `mvn -q test` 通过，`git diff --check` 通过。沿用 owner 在 ITEM-1 同类三轮上限时确认的方案 2，开启新的独立 change review 阶段。
- ITEM-3 新独立审查阶段首轮：fresh reviewer Nietzsche 审查 staged SHA-256 `10a6fff82babb21ed478a3b6bb803e3b010caf81a109d5a8f9b934a4199aac01`，确认三轮后共享 Monitor、锁前快照和旧计划入队修复方向正确；新增计划缺并发控制、任务使用旧计划实体更新汇总两个 blocking，以及事务内入队提交期冲突和部分成功推进整体 `lastVerifiedAt` 两个 important。
- ITEM-3 新阶段首轮修复：计划实体增加 `@Version`，V3 补 `version` 列，计划更新/删除/迁移认领/任务处理使用计划悲观锁；并发计划更新串行递增期望版本，乐观冲突统一映射 409；自动入队在计划锁内 `saveAndFlush`；阶段成功不再推进整体 `lastVerifiedAt`，仅双资源全部达到目标后更新。
- ITEM-3 新阶段首轮修复验证：新增并发计划更新测试和部分失败验证时间不前移断言；`InspectionPlanMonitoringSyncTest` 18 项、完整 103 项后端测试通过。更新后的 V3 在临时 PostgreSQL 库成功增加计划 `version` 列、清理重复任务并建立唯一索引；`git diff --check` 通过。
- ITEM-3 新独立审查阶段第二轮：同 lineage reviewer Nietzsche 审查 staged SHA-256 `c8cf200b5f21c9aa38d448c55b1adf1eff4431b0efab1e65e3faacd5e63c9f08`，确认计划并发、旧写回、事务内入队和 `lastVerifiedAt` 四项 findings 全部 resolved，无新增 blocking、important 或 nit；ITEM-3 gate 通过。
- ITEM-4 实现：服务 binding 仅在当前期望版本、所有权有效且 Monitor/告警规则均实际启用并具备外部 ID 时切换到 HertzBeat；同步失败、部分成功或计划版本漂移继续本地周期检查。新监控计划立即进入兜底调度，本地扫描默认 10 秒并支持秒级计划续跑；全部服务完成切换后仅推进计划时间，不再生成正式巡检记录。
- ITEM-4 地址测试：新增 `POST /api/assets/{id}/test-address`，复用 HTTP/TCP 检查器返回即时结果，不保存巡检记录、不产生告警、不写入资产状态；旧巡检计划及既有历史记录结构保持兼容。
- ITEM-4 验证与 change review：新增同步完成跳过、同步失败兜底、期望版本漂移兜底、地址测试不覆盖 HertzBeat 状态、首次到期、秒级续跑和 10 秒扫描配置测试；沙箱外完整 111 项后端测试通过，`git diff --cached --check` 通过。fresh reviewer Carson 三轮审查 staged SHA-256 `38bc01d75eb0bae352b716f51ebbe95224b155a0ba2bc52567a41a8f5a01602c`，确认首次调度、秒级续跑和最小扫描周期 findings 全部 resolved，无新增 blocking、important 或 nit；ITEM-4 gate 通过。
- ITEM-5 实现：告警建立外部生命周期与平台处置双状态，支持筛选、详情、确认、备注、关闭和处置事件历史；外部 `RESOLVED -> FIRING` 新周期重开为 `OPEN`，重复 firing 保持幂等；归属快照展示项目、巡检计划和服务，关闭仅允许外部已恢复告警。
- ITEM-5 迁移与兼容：V4 创建处置事件表和索引，补齐历史告警双状态、首次/最近发生、恢复时间和乐观锁版本回填；临时 PostgreSQL 库 `ops_platform_v4_check_20261020_1820` 验证四类历史状态、事件表/索引及 `version NOT NULL DEFAULT 0` 全部正确。
- ITEM-5 验证与 change review：沙箱外完整 120 项后端测试通过，前端构建通过；390×844 视口实测告警详情抽屉 `x=0`、宽 390px。fresh reviewer Mendel 首轮指出备注覆盖状态转换信息及事件备注未规范化，修复为备注仅写事件历史并统一 trim；第二轮审查 staged SHA-256 `6d13e6900e64cf07bd4b546daf531ac6ac398b39fa80b77718c358f28fbab249`，无 blocking、important 或 nit；ITEM-5 gate 通过。
