# 观澜统一运维平台

面向多项目的免费开源运维平台 MVP，统一展示项目、服务器、数据库、Redis、消息队列、MinIO、健康接口、巡检和告警状态。

## 启动

```bash
cd api && mvn -DskipTests package && cd ..
docker compose up -d --build
```

后端 Dockerfile 使用 `api/target` 中已打包的 JAR，更新后端代码后须先重新打包。打包和运行均使用 Java 8（建议使用仍提供安全更新的发行版）。

## 服务地址

- 运维平台：http://localhost:8088
- HertzBeat：http://localhost:1157
- VictoriaMetrics：http://localhost:8428/vmui

## 本地开发

后端使用 Java 8：

```bash
cd api
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home mvn spring-boot:run
```

前端：

```bash
cd web
npm install
npm run dev
```

## 监控闭环

联乾是日常运维入口，HertzBeat 是后台采集与告警引擎：

1. 在“项目中心”维护被运维系统及负责人、联系方式、运维到期日，再在“服务管理”维护服务。
2. 在“巡检中心”选择项目和服务，设置固定监控间隔、连续失败次数与告警级别。同一频率的服务可放进同一计划，不同频率拆分计划；一个服务只能属于一个启用计划。
3. 保存计划后自动同步 HertzBeat Monitor 和告警规则；计划页提供单计划手动同步和“同步全部计划”。频率范围为 10 秒至 24 小时。
4. 右上角刷新图标重新读取平台已保存的数据。平台每 60 秒同步一次运行状态和告警，也可在告警页点击“同步监控数据”立即执行。
5. 服务列表展示 HertzBeat 主状态、所属计划和数据陈旧标识。
6. 点击服务操作栏中的监控图标，可查看可用性、响应时间和最近一小时趋势。
7. HertzBeat 不可用时平台保留最后有效数据，不影响项目、服务和巡检管理。

外部告警生命周期与平台处置状态分别保存。平台只在 HertzBeat 明确返回 `resolved` 时更新恢复状态，不根据活动列表缺席推断恢复。告警可确认、添加备注；外部恢复后才可关闭。同一告警的新故障周期会重新打开，处置历史保留。

### 如何理解状态

| 页面状态 | 含义 |
| --- | --- |
| 可用（UP） | HertzBeat 最近一次检查通过；HTTP 检查或端口连通不代表全部业务功能正常 |
| 不可用（DOWN） | HertzBeat 已确认检查失败，计入异常服务 |
| 状态未知（UNKNOWN） | 当前无法判定可用性，例如监控停用或映射失效 |
| 暂无采样 | 没有可展示的指标样本，可用性仍以监控状态为准 |
| 数据已陈旧 | 指标采集时间超过 180 秒，或外部同步持续失败，需要检查采集和同步 |

异常数量与异常列表使用相同状态口径：`DOWN/CRITICAL/FAILED`；`UP` 不计为异常，未知状态也不代表已经确认故障。服务状态与告警事件分别同步：告警事件需要 HertzBeat 实际产生告警。可用性告警的频率、连续失败次数和级别由巡检计划配置；通知渠道在 HertzBeat 中配置。

### 配置同步与数据回流

计划同步负责下发 Monitor 频率、启停与告警规则，后台每 30 秒校验并补偿配置漂移；告警页“同步监控数据”负责拉取状态和告警，后台默认每 60 秒执行。页面刷新只读取平台已保存的数据。

计划显示未同步、同步中、部分成功或失败时可手动补偿；外部不可用时保留期望配置并重试。Monitor 和规则都同步成功后，该服务停止平台本地周期检查；同步失败的服务保留本地检查兜底。

服务删除与计划获取服务使用共同的服务级事务锁，避免删除后产生无法清理的绑定；仍有未清理绑定时禁止删除服务。停用计划删除或移除服务时，只有已由其他计划接管的 Monitor 才保留，无人接管则预留所有权并完成外部清理，失败后保留重试载体。清理历史 Monitor 不会清空后来重建的 Monitor 映射。

当前平台只有管理员账号。组件入口通过 `canManageIntegrations` 会话能力控制显示，进入 HertzBeat 后仍使用 HertzBeat 自身账号认证，不提供自动登录或认证绕过。

## 默认账号

- 联乾：`admin / admin123`
- HertzBeat：`admin / hertzbeat`

生产部署必须通过环境变量修改默认密码。

## 真实闭环验证

本机安装 Bash、curl、jq、Docker，启动平台后执行 `scripts/verify-hertzbeat-loop.sh`。脚本创建唯一测试项目和独立 HTTP 容器，验证频率下发、真实故障告警、恢复、人工确认关闭、同步失败重试和外部资源删除。

默认会短暂暂停现有 HertzBeat 容器；共享环境可设置 `VERIFY_OUTAGE=0` 跳过该步骤。当前脚本面向本仓库默认 Compose 名称与网络，使用默认测试账号，可通过脚本顶部环境变量覆盖账号和组件地址。脚本退出时按本轮创建的 ID 清理测试历史，需要访问本机 Docker 与测试 PostgreSQL。

## Java 8 兼容

平台 API 使用 Spring Boot 2.7.18，源码和产物均面向 Java 8，Docker 运行镜像为 Temurin 8。此版本已结束 Spring 官方开源维护；部署方需安排持续安全更新。HertzBeat、数据库等独立组件使用各自镜像内的运行环境，不要求宿主机安装较新 JDK。

## 深度协议巡检

默认服务仍执行基础 TCP/HTTP 可用性检查。在服务详情的“深度巡检”中保存并启用配置后，手动测试、计划兜底与 HertzBeat 周期采集使用相同协议探测器：

| 类型 | 实际检查 | 必需配置 |
| --- | --- | --- |
| 服务器 | Linux：SSH 登录和 `df -Pk`；Windows：WinRM HTTPS 登录及只读 PowerShell 固定磁盘检查 | 选择操作系统；Linux 使用可信 SSH 指纹和密码或私钥，Windows 使用 NTLM 或 Basic 用户密码；可指定/排除挂载点或盘符与磁盘阈值 |
| MySQL / PostgreSQL | 登录指定数据库并执行 `SELECT 1` | 用户名、密码、数据库 |
| Redis | 可选 AUTH 后执行 PING | 需要认证时填写用户名/密码 |
| Kafka | Admin 查询集群元数据 | 可选 TLS、SASL 用户名/密码与机制 |
| RabbitMQ | AMQP 建立连接和 channel | 用户名、密码、vhost |
| RocketMQ | Nameserver 查询集群信息 | 可选 ACL accessKey/secretKey |
| MinIO | S3 检查指定桶是否存在且可访问 | accessKey、secretKey、桶名 |
| HTTP | 方法、状态码和响应文本断言 | 可选请求头与请求体；不跟随重定向 |

部署前生成并设置主密钥，例如 `openssl rand -base64 32`，将输出保存为部署环境中的 `OPS_INSPECTION_MASTER_KEY`。密钥必须持久保管，丢失或替换后已有加密配置无法解密；不要将真实密钥提交到仓库。未设置时原有功能可用，但不能保存深度巡检配置。

Compose 已将内部桥接地址设为 `http://api:8080`；其他部署方式需设置 `OPS_INSPECTION_BRIDGE_BASE_URL`，确保 HertzBeat 能访问平台 API。平台每服务、每配置版本派生独立 Basic 凭据，桥接仅返回检查是否通过。凭据与配置均需在可信网络传输，跨不可信网络时为桥接配置 HTTPS。

敏感配置加密存储，接口只返回已配置字段名；界面留空保留原值，使用“清除”才删除。SSH 指纹应从可信控制台获取并核对；磁盘使用率达到阈值即失败。协议超时可配置为 1000–10000 毫秒，单次检查总预算 30 秒。每服务同时最多一个检查，全局最多 16 个；手动测试不直接计告警次数，但会与采集竞争。忙碌、过载及超时均作为采集链路失败采样计入计划连续失败次数，建议周期长于检查耗时。

Windows 服务器需安装 Windows PowerShell 3.0 或以上（支持 `Get-CimInstance`），启用 WinRM 服务、远程 shell 权限及 HTTPS 监听。为目标主机安装有效、受信任且包含目标 DNS 名的服务器身份认证证书，管理员执行 `winrm quickconfig -transport:https`，仅向巡检 API 所在网络放行 TCP 5986。资产地址填写 `https://server.example.com:5986/wsman`，或 `server.example.com:5986`；不要填写 RDP 3389 端口。配置页面选择 WINDOWS，填写用户名、密码及需要时的域/计算机名。默认 NTLM（兼容服务端 Negotiate 认证头承载的 NTLM；服务端需启用 Negotiate）；本地账号权限受 Windows UAC 与远程 shell 策略约束，应由管理员授予必要权限，不建议全局关闭 UAC。需要 Basic 时，管理员应按组织策略仅在 HTTPS 服务启用 Basic，保持 `AllowUnencrypted=false`。客户端不提供跳过证书或主机名校验选项；企业 CA 需导入部署 JVM 的信任库，并通过 `-Djavax.net.ssl.trustStore=...`、`-Djavax.net.ssl.trustStorePassword=...` 指定（信任库密码使用部署密钥管理，不提交仓库）。

Windows 仅执行内置的 `Get-CimInstance -ClassName Win32_LogicalDisk -Filter 'DriveType=3'` 只读采集，总量与可用量来自本机固定逻辑磁盘，盘符筛选在 API 本地执行，不拼入远程脚本。盘符格式如 `C:,D:`，排除项优先；指定盘符不存在、容量无效、没有可检查磁盘、认证/命令失败或使用率达到阈值均失败。WinRM 请求总预算包含连接、认证、命令、接收及预留的 shell 清理时间，响应和命令输出均有大小限制。网络中断时远程删除为尽力清理，Windows 管理员应保留适当的 WinRM 空闲 shell 超时。

Windows 自动测试使用本地 HTTPS WSMan 协议夹具，覆盖 Basic、显式 NTLM 和仅 Negotiate 挑战的认证、TLS 拒绝、shell 生命周期、清理及磁盘阈值；不等同于真实 Windows 验收。上线前需在实际域账号/本地账号与组织证书策略下，验证 NTLM 或 Basic 登录、C:/D: 输出、阈值和排除、错误密码、证书不匹配与超时清理。部署参考：[Microsoft WinRM HTTPS 配置](https://learn.microsoft.com/en-us/troubleshoot/windows-client/system-management-components/configure-winrm-for-https)、[WinRM 远程认证](https://learn.microsoft.com/en-us/windows/win32/winrm/authentication-for-remote-connections)、[Apache HttpClient NTLM 支持](https://hc.apache.org/httpcomponents-client-4.5.x/ntlm.html)。

HertzBeat 每周期调用平台并执行一次真实探测，按计划产生故障与恢复告警。保存配置后可在计划页手动同步，后台也会补偿；同步前旧版凭据会被拒绝。HertzBeat 将密码读回为遮罩，配置校验对比版本用户名及其他参数，并通过官方单 Monitor JSON 导出核对成功写入后的凭据指纹；仅密码漂移也会补偿，正常配置不会重置采集。指纹只在内存保存，平台重启后首次校准会重新写入一次凭据。导出权限缺失或不兼容会明确显示同步失败，导出内容不落盘、不回传界面。配置同步成功并不代表已经采集成功。

本轮不执行生产消息收发或对象写入。`scripts/verify-protocol-services.sh` 可启动独立临时容器验证七种产品，退出时只清理本轮资源；结果见 [真实产品验证](docs/testing/protocol-real-products-evidence.md)。TLS、Kafka SASL、RocketMQ ACL 的真实部署组合仍需在目标环境验收。
