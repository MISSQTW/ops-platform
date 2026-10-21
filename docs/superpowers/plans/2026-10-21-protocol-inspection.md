# 七项协议巡检实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 补齐七项真实协议巡检并保留既有监控与告警管理。
**Architecture:** Java协议适配器返回统一检查明细；独立加密配置服务；现有巡检调度保存明细，HertzBeat每周期通过认证接口触发一次独立真实探测并触发原有可用性告警。
**Tech Stack:** Java21、Spring Boot/JPA、AES-GCM、SSH/JDBC/Redis/MQ/S3客户端、Vue3/Element Plus。
**Spec:** docs/superpowers/specs/2026-10-21-protocol-inspection-design.md

## Global Constraints

- 中文回复与代码注释。
- 旧服务未启用深度配置时保留原行为并显示基础检查。
- 主密钥由OPS_INSPECTION_MASTER_KEY提供，Base64编码32字节；不可在仓库保存实际密钥。
- 协议超时1至10秒、磁盘使用率阈值1至100；SSH必须核验主机公钥。
- 内部接口只返回聚合是否通过，所有配置/结果详情需要平台登录。
- 不执行生产消息收发及对象写入。

### Task 1：配置模型与密文存储

Files: inspection/ProbeSettings.java、ProbeCheck.java、ProtocolProbe.java、InspectionProfile.java、InspectionProfileRepository.java、InspectionProfileService.java、InspectionProfileController.java、SecretCipher.java；V7迁移；Profile API/Cipher测试。
Interfaces: ProbeSettings(Map<String,String> options, Map<String,String> secrets)，text(key,fallback)、secret(key)、number(key,fallback)、flag(key)、list(key)；ProtocolProbe.supports(String)，check(Asset,ProbeSettings)返回List<ProbeCheck>；ProbeCheck(category,name,target,passed,message,durationMillis)。
- [ ] 首先添加加密往返/篡改、缺失主密钥、脱敏读取、secret保留/清空、地址变更失效测试并观察失败。
- [ ] 最小实现AES-GCM加密，版本保护，服务事务锁与清理；配置使用options/secrets两个映射，所有请求头和请求体放secrets。
- [ ] 运行Java21定向测试并修复至通过。

### Task 2：服务器、磁盘、数据库、Redis与HTTP

Files: inspection/probes/SystemProtocolProbe.java、HttpProtocolProbe.java、DatabaseProtocolProbe.java、RedisProtocolProbe.java，独立测试；依赖归主流程统一维护pom。
Interfaces: 消费Task1三种公共类型。SSH settings: username/password/privateKey/privateKeyPassphrase/hostKey；diskThreshold、mountPoints、excludedMountPoints；数据库database/username/password；Redis tls/username/password；HTTP httpMethod/expectedStatus/responseContains，secrets.headers(JSON字符串)、body。
- [ ] 测试真实回环HTTP状态/响应断言/不跟随重定向、Redis AUTH/PING、磁盘df解析/挂载点选择/超限/缺失目标，先观察失败。
- [ ] 使用成熟客户端，认证、读取、超时和关闭资源；返回固定失败分类避免泄露第三方错误内容。
- [ ] 执行定向测试并补边界用例。

### Task 3：MQ与MinIO

Files: inspection/probes/MessagingProtocolProbe.java、MinioProtocolProbe.java及独立测试。
Interfaces: Kafka securityProtocol/saslMechanism、username/password，RabbitMQ vhost/tls/username/password，RocketMQ accessKey/secretKey，MinIO bucket/tls/accessKey/secretKey。地址由Asset.endpoint提供。
- [ ] 首先用可控协议fixture或客户端边界测试认证/超时/关闭路径，观察缺能力失败。
- [ ] Kafka Admin describeCluster、RabbitMQ AMQP connection/channel、RocketMQ NameServer cluster info、MinIO bucketExists；禁止写生产资源。
- [ ] 定向测试，记录真实实例验收与模拟验证的区别。

### Task 4：调度、HertzBeat桥接、界面及总验收

Files: InspectionExecutionService、HertzBeatClient、ResourceController、内部聚合Controller、web/src/InspectionProfilePanel.vue、App.vue、README、compose。
- [ ] 添加端到端API测试：启用深度配置后每次计划执行生成多项结果；HB未同步时本地兜底，同步后由HB触发；错误Basic凭据401，停用/旧配置/忙碌拒绝，本次全通过200。
- [ ] 接入Registry与ProfileService，持久化结果按配置版本/地址校验；website目标触发内部协议探测接口，旧服务目标不变。
- [ ] 表单支持所有服务类型参数，敏感字段留空保留/显式清除；配置读取不回显敏感值；服务详情显示检查模式和最新明细。
- [ ] 执行全量mvn test、node测试、npm run build；使用独立容器或现有只读接口验证协议故障与恢复，清理本轮测试资源。
- [ ] 新fresh reviewer审查当前完整diff，解决blocking/important后重新验证；更新Epic证据，不声称未经真实环境验证的项目已通过实际验收。
