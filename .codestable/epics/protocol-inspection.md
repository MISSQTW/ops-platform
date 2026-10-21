---
status: active
created: 2026-10-21
work: ../work/epic-protocol-inspection.md
---
# 七项协议巡检

## 起点、目标、范围和验收

用户指令：先提交当前节点，再实现上一轮建议。节点已保存为1cf6eae。
完整设计与验收：[设计](../../docs/superpowers/specs/2026-10-21-protocol-inspection-design.md)。
目标：完成服务器登录、磁盘阈值、数据库、Redis、消息队列、MinIO和HTTP实际访问检查。基础协议访问为本轮验收边界，生产写入不在范围。

## 子项契约

- ITEM-1：cs-feat；配置加密、脱敏API、检查结果模型；测试证明凭据保留/删除和失效边界。
- ITEM-2：cs-feat；SSH/磁盘/JDBC/Redis/HTTP探测；依赖ITEM-1接口；正常/失败和阈值测试。
- ITEM-3：cs-feat；Kafka/RabbitMQ/RocketMQ/MinIO探测；依赖ITEM-1接口；协议适配、超时与认证失败测试。
- ITEM-4：cs-feat；计划调度、HertzBeat聚合桥接及前端配置/结果；依赖ITEM-1/2/3；接口认证、并发/过载、配置失效、原流程回归及构建。

## 关键决策

统一协议探测器保证手动与计划语义一致；HertzBeat每周期触发独立真实协议探测并继续承担告警，避免Kafka JMX与客户端语义错配和MinIO缺模板。

## 最终交付索引

实现、配置界面、协议测试与真实HertzBeat桥接已交付；证据见[验证记录](../../docs/testing/protocol-inspection-acceptance.md)。

## 整体验收

自动与真实协议验证完成，技术复审后交负责人整体验收，不代负责人接受。

## 遗留风险

部署必须设置加密主密钥及HertzBeat可达的内部平台地址；外部协议实例和监控权限决定真实环境可验证范围。
