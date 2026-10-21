---
epic: ../epics/inspection-policy-hertzbeat-loop.md
phase: executing
approved_revision: dc3ee008133559e332fa9bfbab9366fd5f68bcd31f907a61152b15408853b1a8
current_item: ITEM-7
next_action: 等待owner核验界面交互与视觉并最终接受；代码及真实集成验收已通过，不再新增业务修复
blocked_by: owner_ui_acceptance_evidence_and_final_acceptance
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
- [ ] ITEM-7

## 最终验收证据映射（待审）

本表对应永久 Epic 的16条验收标准；最新候选已独立复审通过并本地提交部署068fcf02f170ef4c89f477e7b7152120ac5fc3ba。表内旧版待办由下方最新证据覆盖，界面证据缺口仍保留。自动测试及打包覆盖当前工作树，保留用户既有修改，不声称是剥离用户修改的纯HEAD验证。

最新统一证据：33 suites / 209 tests / 0 failure/error/skipped（session32886，/tmp/ops-final-068fcf0-full.log）；新清理阶段Arendt第2轮通过，冻结SHAd72dcb2c28dbd65928b6866542ec82e0b961daf2b016898a40b2883376ce4ffd；HTTP真实闭环session40902（告警9、故障补偿、频率及清理，/tmp/ops-068fcf0-http-e2e.log）、REDIS阈值2闭环session68263（告警10及清理，/tmp/ops-068fcf0-port-e2e.log）均退出0；六行真实迁移session46376退出0（/tmp/ops-068fcf0-migration-matrix.log，精确fixture /tmp/ops-migration-matrix-20261021110621.json），本轮资源清理成功，审计历史保留。上述覆盖表内最新候选复审/部署/闭环待办，但不替代新界面交互、视觉及owner整体验收。

| 标准 | 已有权威证据 | 尚需完成 |
| --- | --- | --- |
| 1 服务页入口与配置跳转 | Fermat第二轮静态核验，服务页无配置同步入口，项目/服务携参进入计划或迁移 | 新界面交互/视觉证据受URL政策阻断 |
| 2 持久任务、失败保留、恢复及防旧写 | InspectionPlanMonitoringSyncTest、Monitor标签恢复及版本测试；真实HTTP暂停/恢复闭环 | 最新清理锁候选复审、部署验证 |
| 3 频率转换、更新原Monitor | InspectionPlanScheduleTest 2项；HTTP/port真实10→20秒，迁移120→30秒复用ID | 最新候选部署后闭环 |
| 4 唯一所有权、项目隔离及停用释放 | 数据库唯一binding模型测试、策略API13项、PG13项与同步34项 | 最新共同锁及清理复审 |
| 5 完整操作矩阵与可补偿清理 | 同步34项包含停用删除/移除、往返接管、失败重试；PG双向接管、删除竞争与最后引用清理 | 清理专项三轮后owner裁决与修复复审，不可声明通过 |
| 6 全部清理后才DELETED | 当前owner先删/历史最后删及并发最后引用测试；真实HTTP/port删除与精确清理 | 当前最后修复未经复审、部署 |
| 7 聚合和分资源状态 | 同步34项的多服务PARTIAL、Monitor/规则分阶段失败、验证时间不误推进 | 随最新候选验收 |
| 8 九类服务采集/告警契约 | 类型白名单与架构文档；website和共用port真实闭环 | 无需重复九次相同port实现；保留最终验收核对 |
| 9 阈值告警、恢复和去重 | /tmp/ops-ssl-http-e2e.log、/tmp/ops-ssl-port-e2e.log（REDIS阈值2）；投影8项 | 最新候选部署后闭环 |
| 10 外部生命周期与人工处置分离 | AlertLifecycleTest 5项、AlertManagementApiTest 5项；真实告警7/8确认、恢复、关闭 | 随最新候选验收 |
| 11 同ID新周期与历史保留 | AlertCyclePersistenceTest 1项，实际数据库/API CLOSED→重开→重复同步，旧事件ID/备注保留且一次REOPENED | 测试已纳入208项全量；随候选复审 |
| 12 七维筛选与处置信息 | Fermat第二轮静态核验及告警管理API测试 | 新UI交互证据同第1项 |
| 13 项目详情聚合 | Fermat第二轮静态实现核验 | 新UI视觉/交互证据缺失 |
| 14 外部不可用与陈旧提示 | 投影8项；真实HertzBeat暂停、失败保留与补偿恢复；页面静态实现 | 最新候选部署后闭环，UI证据同第1项 |
| 15 存量迁移与历史兼容 | 六行真实矩阵session74941通过，/tmp/ops-real-migration-matrix.log；准入10场景、提案与PG持久/并发测试 | 最新准入/清理候选部署后核验；不能将旧运行证据冒充新版本 |
| 16 测试、构建与真实集成 | 当前工作树33 suites/208 tests零失败错误跳过；历史前端构建通过；HTTP/port及六行迁移真实通过 | 最新候选独立复审、打包部署、闭环及Fermat第三轮最终验收 |

## 临时决策与证据
- 界面实测与owner最终接受缺口已连续出现于交付轮及两次自动goal续接；当前HEAD仍为068fcf0，未收到人工验收结果，无活动验证任务可等待。代码、自动化和真实集成已完成，不能用重复测试或静态检查替代界面证据。按连续三轮同一阻碍审计将goal置blocked，保留ITEM-7未完成；收到界面核验反馈后继续，不将自动续接当作最终接受。
- Fermat最终验收原lineage第3轮终态：有条件可合，代码审查通过，B1/B2/B3及重开、六行真实迁移证据全部resolved，无新blocking/important代码问题。唯一unresolved important为界面实际交互/视觉证据：携参配置和迁移、计划启停补偿、告警筛选及历史、项目聚合、陈旧/失败呈现、抽屉布局。独立汇总33suite209项全绿并核对三组真实脚本终态。ITEM-7及Epic保持未完成，等待owner界面核验与最终接受；不再将已解决的三轮裁决当作阻碍，也不将缺证据当作产品失败。毕业指向：启动/迁移/清理操作契约已进README，监控与告警边界已进架构设计文档；无新增lesson，work按未验收状态保留。
- 最终209项及最新HTTP/port/六行迁移真实验证全部结束后，已移除独立tmpfs测试容器ops-migration-test-postgres-20261021-0920；未触碰业务数据库和卷。API健康状态healthy，HertzBeat Paused=false。Fermat原lineage第三轮最终验收已派发，显式保留UI证据缺口，未声明ITEM-7完成。
- Arendt新清理阶段第2轮通过，完整候选SHA d72dcb2c28dbd65928b6866542ec82e0b961daf2b016898a40b2883376ce4ffd，resolved首轮blocking、无unresolved/new findings。17文件已本地提交068fcf0，未push；最新JAR打包及docker compose重建API完成。显式本次JVM回环非代理设置后的最终全量session32886退出0，日志/tmp/ops-final-068fcf0-full.log。真实HTTP闭环session40902进行中，尚不计通过。
- 新清理阶段fresh Arendt（01a15c64-ddf3-70f0-9c49-858d39de1358）首轮发现binding Monitor ID为空、实际删除回退Asset映射时绕过共享引用检查；已新增首次接管同步失败回归先红复现误删，再以实际待删ID检查历史引用修复。新冻结17文件SHA d72dcb2c28dbd65928b6866542ec82e0b961daf2b016898a40b2883376ce4ffd交同reviewer第2轮。全量209项0failure，唯一HTTP测试出现Bad响应，补本次JVM http.nonProxyHosts后37项客户端测试通过；同步35项、真实PG13项通过，日志/tmp/ops-cleanup-null-monitor-full.log与/tmp/ops-cleanup-http-recheck.log。沙箱初次完整运行套接字拒绝不作为验证通过证据。
- owner明确回复“按方案 2，继续独立审查”，已解除旧阶段三轮上限裁决阻碍；新阶段使用fresh reviewer审查冻结SHA ae6f7d9842b4a09fa983732cbf33de9e245507134c673472a3f8bf77f6043836。ITEM-7保持未完成。
- 同一审查裁决阻碍已连续出现在原请求裁决轮、验收证据整理续接轮及本次核对轮。当前候选SHA仍为ae6f7d9842b4a09fa983732cbf33de9e245507134c673472a3f8bf77f6043836，全部已授权且不依赖裁决的修复、208项全量回归、真实迁移矩阵和验收证据映射已完成；未收到owner明确答复，不将自动goal续接当作授权。按goal blocked审计设置受阻以停止自动重复耗费，保留ITEM-7未完成及全部待审候选。owner确认方案2后恢复新阶段独立审查、最新候选部署闭环与最终验收。
- 续接核对：暂存候选SHA仍为ae6f7d9842b4a09fa983732cbf33de9e245507134c673472a3f8bf77f6043836，未发生新提交、部署或超限审查。独立读取Surefire当前报告，清理同步34项、PG13项、策略API13项、服务CRUD10项、告警周期持久1项均零失败/错误/跳过；详细验收映射见文末。上次向owner的审查裁决请求尚无明确答复，自动goal续接不是批准。
- 清理Einstein第三轮旧finding全部resolved，但新增持owner key的PENDING_RELEASE跳过共享引用检查blocking。本阶段已达3轮，未发第四轮；已修为所有PENDING_RELEASE均校验共享引用，并新增“当前owner先删除→停用历史最后删除”回归。最新Java21全量含独立PG session40560 exit0，33 suites/208 tests/0failures/errors/skipped，日志/tmp/ops-cleanup-post-review-full.log。完整17文件最新staged SHAae6f7d9842b4a09fa983732cbf33de9e245507134c673472a3f8bf77f6043836，未提交/未部署本批候选。Aristotle第二轮B1准入审查对上一候选SHA00785aca通过；最新清理两文件增量未经复审。已向owner异步询问是否沿之前方案2开新独立审查，等待裁决，不越过三轮上限。
- 六行真实HertzBeat迁移矩阵已在当前运行2728e0e版本通过，session74941 exit0，/tmp/ops-real-migration-matrix.log；脚本/tmp/verify-ops-migration-matrix.py，精确fixture与结果/tmp/ops-migration-matrix-20261021104848.json。覆盖唯一Monitor无旧计划复用ID/确认幂等、旧计划无Monitor待确认不创建/确认后同步、多旧计划拒绝、60与120冲突明确选择30且确认前不改、外部缺失保留映射至确认后新ID重建、共享Monitor拒绝且不改90秒。首次脚本未处理HB创建响应data为空，已按原服务assetId唯一认领恢复后续跑原fixture，未重复创建；这是测试脚本兼容问题，产品客户端原已兼容。成功后服务/旧计划/全部本轮Monitor均精确API清理，项目/提案/逻辑删除计划保留审计，无批量SQL删除。此运行不能冒充尚未部署最新准入/清理候选的最终E2E，review通过部署后仍需最新闭环核验。
- 准入Aristotle首轮发现同计划仍持有owner的PENDING_RELEASE恢复被误判，已区分restoring并保持Monitor唯一校验，新增规则/Monitor删除失败后原binding恢复两项。清理Einstein第二轮旧finding resolved，新增binding主键不代表往返交接顺序，现检查全部未释放共享引用并让processTask同持服务锁，最后引用负责删除，新增A→B→A往返与PG双cleanup最后引用实际删除。最新session29138八suite91tests/0failures/errors/skipped（PG13）通过，/tmp/ops-admission-cleanup-round3.log。CLOSED→同ID重开→重复同步→旧事件保留的数据库/API串联测试也已通过（session42480及29138）。完整17文件staged SHA00785acafc94252880da5b539adf4a46f4fe1884888335240d37e1e80f231bd5冻结，Einstein第三轮最后轮、Aristotle第二轮复审中，未经通过不提交。新增共同锁将PG旧“唯一冲突回滚”证据改为advisory串行后obsolete/只清理旧规则，保留数据库唯一约束及无误删除断言。
- 清理新阶段Einstein首轮补出历史501→新502误解绑与服务删除/新绑定竞态两个blocking。已修历史ID条件解绑、多轮停用引用保护，普通准入/删除/迁移准备共同服务事务锁（PG advisory、H2行锁），旧claims API410/service拒绝；B1普通create/update存量10场景先红10/10后绿。最新session77691定向7 suites/86 tests/0failures/errors/skipped（含PG12项）通过，日志/tmp/ops-admission-cleanup-final-targeted.log。初版SpyMANDATORY设置失败已修为ultimate target；并发测试隔离旧自动调度，最终无Unexpected error。完整16文件staged SHA013bf5f8547b2570dcd9222934ac0c9526f907888151695adec46b009d203db5已冻结未提交，Einstein同lineage清理第二轮与fresh B1准入review正在进行；期间不改候选，用户既有修改未暂存。剩余真实迁移矩阵、CLOSED重开串联、最新全量/构建/部署/闭环及Fermat最后一轮验收尚需完成。
- Fermat最终验收第二轮不通过：B1普通创建/增加服务及旧claims绕过迁移安全；B2停用无人接管后删除错误DETACHED遗留Monitor；B3停用释放后服务可物理删除导致规则清理任务失去Asset。迁移六分支真实执行及CLOSED→重开完整事件串联仍需补强，新UI视觉证据缺失。优先B3新增参数化API回归session58219先红（停用释放场景期待409实际204），控制器改为所有非RELEASED binding均阻止服务删除，绿色验证中。候选尚未独立review/提交，最终阶段累计2轮终态，剩余1轮须在全部finding完成后再发。
- 最新运行状态只读核实：API healthy，HertzBeat Paused=false。诊断plan12为DELETED/SYNCED，binding11为RELEASED且Monitor/规则均DELETED、外部ID已清空，asset82不存在；project12与逻辑删除历史保留，未执行批量SQL删除。独立PG测试容器仍保留供最终审查定向复测，不能误称所有临时资源已清理。
- 2026-10-21最新真实HTTP session99087、REDIS端口契约/阈值2 session51342均exit0，日志分别/tmp/ops-ssl-http-e2e.log、/tmp/ops-ssl-port-e2e.log。真实告警7/8完成FIRING→确认→RESOLVED→关闭，HTTP含HertzBeat暂停失败与恢复，均明确核验Monitor/规则不存在并精确清理本轮资源。SSL修复提交2728e0e（未push）。诊断plan12正常删除同步后asset82已API删除，专用容器已移除，历史plan/project保留。final acceptance原Fermat已resume，第二轮目标冻结HEAD2728e0e921fef60575ad132ab58bb58cc615829b，显式告知六行真实迁移脚本及新UI视觉证据尚缺，等待逐项审查。
- SSL协议修复fresh reviewer Kierkegaard（01a15c3c-264d-7642-8bf5-11e2a87f1080）只读复核三文件SHA18f71744d11ea670427d48847024404d63f2a39ab4d7450541fc494a687c6a82通过，无blocking/important/nit；前Hooke额度失败无报告，本次有界恢复成功。Java21全量含独立PG测试session34600退出0，31 suites/181 tests/0failures/0errors/0skipped。package session97426和Docker部署session48944退出0。诊断plan12手动同步返回SYNCED、processedTasks=1，asset82仍为原Monitor696610818197248，未重复创建。真实HTTP脚本session99087运行，日志/tmp/ops-ssl-http-e2e.log，已通过自动同步和20秒频率变更，告警等后续仍待终态。
- 最新真实HTTP脚本session48010终态失败：保存后同步超时，脚本精确清理本轮plan11资源，HertzBeat未暂停。另建受控诊断fixture project12/asset82/plan12、nginx ops-recovery-diagnostic-20261021095638（状态文件/tmp/ops-recovery-diag-state.json）保留RETRY载体，查得Monitor写后校验不一致。真实GET Monitor696610818197248除ssl外均一致，ssl为type2且paramValue=******。当前容器website定义ssl是boolean输入，但CommonConstants明确存储1=STRING、2=PASSWORD，MonitorParam.fromEntity对2脱敏。已先红HTTP/HTTPS两项再将ssl存储2改1，仍严格验证ssl值，不忽略掩码；session85800 client37+params3+sync25=65项通过。Hooke（01a15c30-82ce-7780-b648-838fff1c8c29）fresh协议修复review三文件diff SHA18f71744d11ea670427d48847024404d63f2a39ab4d7450541fc494a687c6a82，冻结未提交。此为新发现真实运行契约根因，之前179项全量不覆盖该真实掩码场景，不能作为本候选最终验证。修后须重新打包部署、用诊断fixture验证相同MonitorID修复、再完整E2E。
- 最终全量重跑session49538退出0，按当前测试源逐一匹配31份XML汇总179 tests/0failures/0errors/0skipped，包含PG8项。空执行契约API测试已提交e855af5。前端session30554构建通过，Java21打包session92156退出0；docker build/up session53658退出0，API healthy，业务库Flyway V5 success=true且prepared_plan_id存在。真实HTTP闭环脚本当前session48010运行，日志/tmp/ops-docker-e2e-final.log，必须先取得该handle终态，不能因观察超时重启；尚需最新port闭环和完整迁移矩阵真实证据，再沿Fermat final acceptance复审。现有/tmp/ops-platform-before-v5-20261021.dump备份已独立恢复核对通过。
- Monitor恢复Meitner第三轮通过，37客户端+1参数测试通过，三文件SHA8931494e1f3bd29d2cc8c8edf1e0d22a952777e3a9107201f81a9581fa7c4c37已本地提交9936e18；停用漂移Erdos第二轮四文件通过，PG8+H2同步25项通过，SHA4cc97f436ef368a6ee0263a27f1ea0b6af63522ba4099d0f2b651fe1f4d98156已提交d670d73，未push。
- 首次最终全量178项仅InspectionCenterTest旧空执行断言失败（仍期待201/空FAILED记录，实际409符合已确认新契约）。测试已改为验证409且不写空记录/执行审计，并新增真实非空服务逐项执行与审计正向API回归；session21342定向通过，最新全量重跑中。前端session30554构建通过，仍只有既有大chunk警告。
- V5部署前业务库备份/tmp/ops-platform-before-v5-20261021.dump（93K，SHA8fdcf3daf77a917b63bf6edb417bca086df943e79951abcf3623ba1484c4a7f4）已恢复到独立tmpfs容器ops_platform_restore_check_20261021库，projects/services/plans/alerts数量3/4/3/1与业务库一致。未覆盖业务数据，部署及最新真实E2E矩阵尚未执行。
- 停用漂移候选首轮Erdos指出after-commit新增任务缺独立事务与真实竞争证据不足。已改self proxy public REQUIRES_NEW入队、按planId锁定重读，原触发测试新增日志和任务实际成功断言；session92335 PG8项+H2同步25项通过，无afterCompletion/no transaction异常。新增PG竞争精确验证：旧校准先预留则新owner等待，成功/失败均释放后接管；新owner插入未提交而旧空读后预留时，观测唯一索引Lock，new commit后old flush失败事务回滚且从未外部禁用；已新owner提交则旧task直接obsolete。Erdos第二轮完整四文件diff SHA4cc97f436ef368a6ee0263a27f1ea0b6af63522ba4099d0f2b651fe1f4d98156，冻结未提交。
- Monitor恢复Meitner前两轮分别指出分页矛盾及后页新增元数据矛盾，均先红后修。现固定跨页元数据字段集合，验证页号/总页/数量/size/first/last/empty，拒绝超限、落后已读范围、异常空后页和计数变化；后页失败不提前认领。session70604客户端37项+参数1通过，第三轮最后一次完整review三文件diff SHA8931494e1f3bd29d2cc8c8edf1e0d22a952777e3a9107201f81a9581fa7c4c37已发，目标冻结未提交。
- 迁移Sartre第三轮通过，67项回归通过，staged20文件SHAa3376602ec093ca338ffa7af7d445da513f331f68e8d821570a392097e216b31已本地提交753b42f。前端Newton四文件review通过，npm build通过，精确排除用户原有App三处hunk，staged SHAb1d6d48dd3ed460634e564e6657220f9d11b315e022a71dc94c0a8ac07d8bad9已提交337be6f，未push。视觉验收仍受浏览器政策阻断，未绕过。
- Monitor创建恢复开始修复：真实HertzBeat只读核实列表分页/assetId标签；三项先红证明本地未提交重试POST两次、跨分页重复未拒绝、列表失败仍写入。现按稳定assetId创建前全分页唯一查询，恢复PUT；分页失败/冲突/上限拒写，持久服务创建响应无ID不再用名称猜。session30300客户端28项+参数测试1项通过。Meitner（01a15c17-8782-7993-96b6-8283f4bd9379）fresh只读review三文件diff SHA266bbf448ff178f64fa83e13e36a425710f8bae8a080a50141a3e92e48841a91，目标冻结未提交。停用无人接管漂移、最终真实矩阵、最终全量与Fermat final acceptance仍需完成。
- 迁移第二轮Sartre确认旧三项resolved，但新增blocking：规则失败时新旧计划同时本地调度。已PG先红复现并修复：有同项目启用legacy时结构化计划不再额外本地检查；旧计划停用/删除则恢复必要fallback，pending手动执行拒绝。session86112八组回归退出0，完整staged20文件SHA a3376602ec093ca338ffa7af7d445da513f331f68e8d821570a392097e216b31已沿Sartre发第三轮（最后一轮）review，冻结未commit，执行器用户末尾文案hunk精确排除。
- 前端新增MonitoringMigrationPanel、报告/prepare/list/confirm/cancel接口，服务归属仅找ENABLED+ACTIVE且项目相符的计划，配置跳转携具体项目服务；已有Monitor/旧计划或pending的服务进入迁移抽屉。pending计划禁用普通sync/edit/toggle/delete，提供确认入口；频率冲突要求显式选择。npm build session66613退出0（既有chunk警告）。UI浏览器访问localhost5173被URL安全策略拒绝，未绕过、未声称视觉验收。fresh Newton（01a15c14-11e5-7fe2-a851-fdde6e684078）独立审未暂存App/api.ts/api.js diff SHA078a12cc446d5793dd45d6fa2f829e4e505a1067b23c0159cc9ee1226daef49c及新增组件全文SHAad484f65d4fd2ee3698b38c597c35821dcd0c0de858d61d849cace6493fba2fb，四文件也冻结未commit。
- 迁移第二轮review已冻结完整staged十八文件，SHA88bc1573e305f0bb09d9dbc2bd43d1f1bee06e0f53c316814bd7150f13081cb1，沿Sartre同lineage复审。prepare/confirm/cancel在任何读取前获取PG事务表锁，protect资产/项目/计划/binding（含新增映射），5s获取超时映射409；inspect仅回读当前服务Monitor，避免锁内全量远程查询。新增取消恢复路径，待确认资产修改入队及processTask均防绕过。session75138七组回归退出0，含真实PG四项：独立事务并发确认同plan、pg_stat_activity观察并发writer等待Lock、提交前故障四实体/任务回滚、Monitor成功rule失败保持旧调度且重试不再建Monitor/双READY后交权。H2测试mock PG锁，不能替代PG证据。独立tmpfs容器ops-migration-test-postgres-20261021-0920（15439）仍保留待复审补测，业务卷未动；最新V5在该容器ops_platform_v5_schema_check重复两次执行及prepared_plan_id核验通过。本机SOCKS非代理CIDR导致PG JDBC shouldResolve=false，诊断后仅本次测试设置-DsocksNonProxyHosts=localhost|127.*|[::1]恢复直连，未修改全局代理。待review终态前不改十八个冻结文件，尚未commit。
- Sartre迁移首轮review不通过：proposal不能替代真实待确认计划、确认现场存在并发窗口、测试缺提交/并发/失败补偿。已修第一项：prepare创建真实PENDING_CONFIRMATION计划和MIGRATION_PENDING binding，保留现有Monitor ID、不创建同步任务，confirm激活同一计划，不新建第二个计划；普通编辑/删除阻止绕过确认，手动同步与reconcile跳过待确认计划。先红持久化断言，session68773五组回归退出0，新增待确认同步保护测试通过。并发现场锁和独立事务故障注入仍未完成，尚未发第二轮review或提交。V5新增prepared_plan_id，之前临时库证据只对应旧版本，需重新验证。
- 迁移prepare/list/confirm API候选已接入：提案确认前只持久化现场与选定频率，不创建执行计划/不下发Monitor；确认重验现场并与新计划、binding、持久同步任务同事务提交；重复确认返回原计划。四组定向测试session93246退出0（含flush/clear后重新确认、仅创建一个计划及旧计划仍ENABLED）。V5在独立PostgreSQL库ops_platform_v5_check_20261021_0918执行两次通过，插入结果version=0且事务回滚，索引正确。fresh reviewer Sartre（01a15c06-70ee-7843-ba46-b720f25627c2）审查冻结十文件，tracked2 SHA8df80333d9379a659d85e16e7f738df842a012dd13ed8262c19421f7755c9c74；尚未提交。已明确要求审查pending proposal是否满足“默认待确认计划”及并发一致性，不以较小范围替代矩阵。
- 迁移确认持久层开始实现：新增V5提案表、带悲观锁读取的repository与提案实体，记录不可变现场、频率、状态及确认目标计划；回归覆盖现场变化拒绝和重复确认禁止改写目标。当前尚未接入prepare/confirm API，也未完成PostgreSQL迁移或独立审查，不能据此声称迁移闭环完成。频率展示修复已本地提交36cf24d。
- 类型准入修复已通过Helmholtz第二轮七文件复审，提交d5b436f；迁移认领新增未知类型和无效HTTP地址拒绝回归，先红两项后绿。迁移报告新增六场景参数化验证，session25158退出0；报告仅做分类，持久确认批次仍未实现。频率显示新增回归复现修改300秒仍显示60秒，getter改为结构化间隔派生并保留旧计划文本，session26051定向回归退出0，尚未提交。
- 旧入口/执行切换修复已通过Avicenna三轮review，staged SHA68727e7a9aa1c3ac23cb6306993208fd4c50792d77bbd5e747ec28476bbcc583，10执行+2controller测试通过；类型契约/API拒绝未知类型定向session76012退出0，Helmholtz独立review中。
- 后续修复现场：HTTP参数一致性候选staged SHA e38bdc1a81e43579421993475b849b77ce5dede58731438931d557cc95fc5802，Popper第二轮复审中，新增写后参数仍漂移FAILED/RETRY断言与SSL/timeout/query边界。旧服务配置同步路由已返回410；旧计划执行按当前active binding排除已完整接管服务，并跳过空执行记录，回归先红，修复后验证进行中。上述旧入口/执行切换尚未独立审查提交。
- Final acceptance修复进展：多服务版本传播与启用/停用关联服务跨项目移动保护已通过20项策略测试及Darwin独立review，提交a493786。HTTP参数漂移修复进行中，新增路径变更、缺参数和query回归；旧入口、旧计划切换、类型契约和迁移矩阵仍未完成。
- Final acceptance 首轮（Fermat，冻结0f1a231）不通过：迁移六行矩阵未完整实现；服务更新仅推进单binding版本；服务跨项目编辑绕过；Monitor参数未比较；旧服务同步API仍可覆盖计划；旧计划未逐服务退出本地执行；类型准入与告警契约未冻结。Important另含Monitor创建崩溃幂等、停用无人接管漂移、服务当前owner展示/携参配置跳转、频率显示旧scheduleText。不得将本Epic标为完成，先按上述顺序修复再沿用同reviewer final acceptance复审。最新冻结后全量121项通过证据已补充，不能抵消功能缺口。
- 冻结提交0f1a231后重新运行后端全量测试（session8713）退出0，Surefire汇总121 tests、0 errors、0 failures；final acceptance reviewer Fermat（01a159aa-1aad-7d50-957d-a9cd4f5e66ce）仍在运行，期间不修改冻结实现。
- ITEM-7 最新严格脚本 session 32960 退出码 0，输出“真实 Docker 端到端闭环与清理验证通过”；包含 HTTP 故障/恢复/处置、暂停 HertzBeat/补偿、Monitor 与规则明确不存在检查和本轮历史事务清理。修复提交 fdc3268、界面补齐164e601、删除幂等166c766、验证与文档0f1a231；已创建 fresh Epic final acceptance reviewer，冻结commit 0f1a2318ff4ba8d78338e7208edc666cfd5aa895，待逐项验收结论。
- ITEM-7 严格清理版 session 91505 主体通过但 cleanup 退出 1，保留 plan=9/asset=79/project=9 重试载体；复现根因为第二次 DELETE 将 DELETED 计划回退 DELETING。新增回归先失败（expected DELETED but was DELETING），当前修复已删除状态直接返回，待定向测试与真实重跑。
- ITEM-7 脚本第三轮复审 SHA `fed09059f902039156fe7b5a976d0df0030de1ef371aab75f1d498b6d6423dcd` 代码 findings 清零；严格要求失败保留任务与外部ID、优先恢复暂停的HertzBeat、仅按ID事务清理及清理失败非零退出，待本版真实重跑。
- ITEM-7 session 25527 退出码 0：固定地址独立测试容器完成计划自动同步、10→20 秒频率、真实 FIRING、确认、RESOLVED、关闭、HertzBeat 暂停失败与恢复重试、迁移报告 ALREADY_MANAGED、计划删除和 Monitor 清理。运行后只读核对本轮项目/计划历史已清理、独立测试容器不存在、HertzBeat Paused=false；首次失败运行留下的历史项目与 DELETED 计划仍保留，未批量删除。
- ITEM-7 后续运行库核查确认规则类型必须为 `realtime_metric`；`realtime` 不进入实时指标计算缓存。已修复请求类型与漂移校验，策略 17 项、客户端 20 项定向测试通过；真实重跑进程 session 15635 待取得终态。
- ITEM-7 真实 Docker 验证：自动同步、Monitor 10→20 秒频率下发及规则回读通过；服务故障后 DOWN，但 180 秒内无 FIRING，整体验收未通过。
- ITEM-7 根因证据：本机 `hertzbeat-alerter-2.0-SNAPSHOT.jar` 的 `MetricsRealTimeAlertCalculator` 使用 `__available__=up/down`，规则筛选要求表达式包含 `equals(__app__,"...")`；原 `availability == 0` 虽可保存却不会匹配执行。当前修复同时限定 `__instance__` 为 Monitor ID，待真实重跑和独立审查。
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
- ITEM-6 完成项目档案与详情、服务所属计划/监控/告警展示、服务页同步入口移除、结构化巡检计划配置，以及单计划与全部计划手动同步；旧计划仅保留记录和删除兼容。
- ITEM-6 前端 `npm run build` 退出码 0；后端 `InspectionPlanPolicyApiTest,InspectionPlanMonitoringSyncTest` 定向测试退出码 0。
- ITEM-6 change review 第二轮冻结 SHA-256 `7c80d40878e6d682ae3e5ce743209f64d6bfea0d22f29e453ae11e0c09148821`，历史告警兼容与频率单位边界 finding 均 resolved，无 blocking、important 或 nit。
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
