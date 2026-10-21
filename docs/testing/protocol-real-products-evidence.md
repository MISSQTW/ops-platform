# 真实产品协议验收证据

日期：2026-10-21（Asia/Shanghai）。使用 Java 21、Maven，直接调用本轮生产协议探测器；不是客户端 mock 或简化协议 fixture。

## 重现

```bash
bash scripts/verify-protocol-services.sh
```

脚本仅使用本地镜像（`--pull never`），为本次运行生成唯一资源前缀和标签、独立 Docker 网络，服务仅发布到 `127.0.0.1` 随机端口。MinIO 桶只创建于本次新建实例。测试凭据仅用于临时容器，输出经过脱敏。

`RealProductProbeIT` 默认不会被 Maven Surefire 的普通测试匹配，且额外要求 `PROBE_IT_ENABLED=true`；脚本显式运行 `mvn -Dtest=RealProductProbeIT test`。服务未在预算内就绪时记录对应原因、跳过该服务测试，并使脚本整体返回失败，避免把部分验收当作全部通过。

## 最终运行

- 运行标识：`ops-protocol-it-20261021234052-74842-22513`。
- Maven：12 项测试，0 失败、0 错误、0 跳过，`BUILD SUCCESS`。
- 测试耗时：3.913 秒；Maven 总耗时：5.085 秒（不含容器初始化）。
- 日志目录：`/private/tmp/ops-protocol-products-20261021-verified`。
- Maven 详情：`maven.log`；启动、产品和清理日志同目录。
- `cleanup.log` 证据：本次标签剩余容器 0、剩余网络 0。

退出 trap 会先核验容器标签，再按不可变容器 ID 删除本次容器及其匿名卷；随后删除本次网络并检查标签下无残留。未连接、停止或修改任何已有业务容器。

| 产品镜像 | 实际验证 | 测试数 |
| --- | --- | ---: |
| `postgres:16-alpine` | 正确密码执行 SELECT 1；错误密码拒绝 | 2 |
| `mysql:5.7` | 正确密码执行 SELECT 1；错误密码拒绝；本机以 amd64 模拟运行 | 2 |
| `redis:latest` | 正确密码 AUTH/PING；错误密码拒绝 | 2 |
| `rabbitmq:3.13-management` | 正确密码完成 AMQP 连接与 channel；错误密码拒绝 | 2 |
| `minio/minio:latest`，`minio/mc:latest` | mc 在临时实例创建专用空桶；SDK bucketExists 成功；错误 secret key 拒绝 | 2 |
| `m.daocloud.io/docker.io/apache/kafka:3.9.1` | Admin describeCluster 返回实际 broker 元数据 | 1 |
| `m.daocloud.io/docker.io/apache/rocketmq:5.3.2` | 客户端读取实际 NameServer clusterInfo；无 broker 时返回空集群信息 | 1 |

## 发现及范围

首轮与后续诊断发现，本机 JVM 继承的 SOCKS 绕过配置使用 CIDR（如 `127.0.0.1/8`），而 PgJDBC 的 HostSpec 绕过匹配仅识别主机通配符，因此 PostgreSQL 回环连接报 `PSQLException / UnknownHostException`、SQLState `08001`。测试侧直接 JDBC 连接复现相同错误，排除了生产探测器的断言差异。脚本仅为验收 JVM 指定 `-DsocksNonProxyHosts=localhost|127.*|[::1]`；生产实现未修改。该修正后连续两轮全部通过。

本验收不覆盖真实 TLS 证书握手、Kafka SASL、RocketMQ ACL 服务端校验、生产消息收发或对象读写。RocketMQ 使用真实 NameServer，但未启动 broker。SSH 和平台到 HertzBeat 的桥接由主流程另行验收。
