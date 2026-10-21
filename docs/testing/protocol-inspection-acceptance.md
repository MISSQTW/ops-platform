# 协议巡检交付验证

范围：SSH登录、磁盘阈值、MySQL/PostgreSQL SELECT 1、Redis AUTH/PING、Kafka/RabbitMQ/RocketMQ访问、MinIO桶访问、HTTP状态及文本断言；配置加密、脱敏编辑、手测、计划与HertzBeat告警桥接。

## 自动验证

- Java 21：`mvn -q -f api/pom.xml clean test`，218项，0失败、0错误、0跳过。执行时补本机代理绕过参数 `-Dhttp.nonProxyHosts='localhost|127.*|[::1]' -DsocksNonProxyHosts='localhost|127.*|[::1]'`；日志 `/tmp/ops-protocol-clean-final.log`。
- 普通测试统计来自清理后的Surefire报告；此前247/250是遗留报告累计值，不作为最终本轮统计。
- 前端 `npm run build` 和 `node --test src/*.test.js src/*.test.mjs`：构建通过、13项测试通过。构建仍有现存大分包提示。
- 回归覆盖：同名服务状态隔离、待释放绑定跳过、真实计划入口慢服务并行、旧revision认证拒绝、30秒总预算后丢弃结果且真实worker退出前不归还互斥、手动测试有计划的LOCAL服务只写明细、凭据漂移补偿及未漂移不重置Monitor。

## 真实协议与监控

- 七产品12项真实容器测试全部通过，详见[产品验证](protocol-real-products-evidence.md)。测试容器/网络按本轮标签和ID清理，剩余0。
- SSH使用真实MINA服务握手，验证正确/错误密码、严格主机指纹及私钥签名；`df`响应为受控输出，不代表宿主机磁盘实测。
- 显式执行 `HertzBeatProtocolBridgeIT`：独立H2平台18889、现有HertzBeat1157、独立HTTP目标；验证UP→HTTP200但文本错误DOWN→恢复UP，以及firing→resolved。额外人为仅改本轮Monitor密码，确认漂移检测失败、同步后原Monitor恢复有效凭据。最终日志 `/tmp/ops-protocol-real-hb-final-acceptance.log`。
- HertzBeat官方单Monitor导出的密码是稳定密文；平台仅保留“期望凭据＋已成功写入密文”的散列指纹。首次校准/平台重启后写入一次，后续读取指纹检测漂移，避免健康Monitor周期重置。导出失败明确报同步失败，异常不携带导出原文。
- 早期失败的3个临时Monitor已按精确名称与assetId标签核对后删除。测试Monitor/规则退出时清理；HertzBeat中可保留测试告警历史。未部署或重建正在运行的平台镜像。

## 部署与验证边界

部署前设置并持久保存 `OPS_INSPECTION_MASTER_KEY`，确保 `OPS_INSPECTION_BRIDGE_BASE_URL` 对HertzBeat可达；详见根README。配置桥接需在可信网络传输，跨不可信网络使用HTTPS。磁盘适用于Linux/POSIX。TLS、Kafka SASL、RocketMQ ACL的真实服务端组合尚需目标环境验收；RocketMQ真实测试为无broker的NameServer读取，不执行消息收发或对象写入。

## 独立审查

变更审查第3轮、独立Epic技术验收第2轮均通过，未解决blocking/important为0。审查冻结目标SHA-256：`c941fa5ce77224b81b70d37a2fcace3945e8543cdb5b32c6054e3e7821a514c3`。技术验收不代替负责人整体验收。
