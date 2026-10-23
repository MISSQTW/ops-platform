# Windows 巡检与 Java 8 验证

日期：2026-10-22（Asia/Shanghai）。

## 交付范围

- 平台 API 编译、测试、Docker 运行均使用 Java 8；Spring Boot 2.7.18、Flyway 9.22.3，保留既有数据库结构和 API 字段。独立 HertzBeat 组件沿用其自带运行环境。
- 服务器深度巡检新增 WINDOWS，Linux 默认仍使用 SSH。Windows 采用 HTTPS WinRM、NTLM（兼容 Negotiate 与显式 NTLM 挑战）或 Basic，固定只读 PowerShell 查询固定逻辑磁盘；支持盘符选择、排除与阈值。
- 严格 TLS 证书与主机名校验，XML 禁止外部实体，响应/命令输出限长，完整请求预算、中断及远端 shell 尽力清理。未提供任意脚本执行入口。

## 验证结果

- 真实本机 Zulu JDK 8：`mvn -q -f api/pom.xml -Dhttp.nonProxyHosts='localhost|127.*|[::1]' -DsocksNonProxyHosts='localhost|127.*|[::1]' clean test package`，39 类、237 项，0 失败/错误/跳过。日志 `/tmp/ops-java8-migration/final-test.log`。应用 class major 为 52。
- Windows 协议 14 项：HTTPS Basic、NTLM/Negotiate Type1/2/3 生命周期、认证失败、TLS 不可信与主机名不匹配、SOAP fault、输出限长、超时/中断与清理、盘符和阈值。配置 API 3 项：加密保存/脱敏读回、拒绝不安全配置、保留 Linux SSH 校验。
- Negotiate-only 回归先红后绿：`/tmp/ops-java8-migration/negotiate-red.log` 和 `negotiate-green.log`。原实现不响应仅 Negotiate 挑战，修复后复用 Apache NTLM 引擎并适配认证头名称。
- 前端 `npm run build` 与 `node --test src/*.test.js src/*.test.mjs`：构建成功，14 项通过；保留现有大分包提示。隔离页面实际展示 Windows 系统/认证/域/盘符字段，并成功保存阈值。
- `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home bash scripts/verify-protocol-services.sh`：七种真实容器产品 12 项全部通过，本轮临时容器和网络清理后均为 0。日志 `/tmp/ops-java8-migration/products.log`。
- PostgreSQL 16.15 兼容性：仅从现有库导出表结构到独立测试库，无复制业务记录。Temurin 8 容器以 `ddl-auto=validate` 成功启动，通过合成数据验证登录、项目/服务创建、Windows 配置加密保存与脱敏读回。运行版本 `1.8.0_504`，容器健康。

## 边界与部署

未连接真实 Windows 主机；TLS WSMan 夹具验证协议行为，不能证明真实域账号密码、UAC/远程权限或组织证书策略。实际接入需按 README 配置 WinRM HTTPS、可信证书、凭据和盘符，再执行故障/恢复验收。NTLM 夹具验证握手，不替代 Windows 账户数据库认证。

深度配置仍要求部署环境持久保存 `OPS_INSPECTION_MASTER_KEY`。本轮隔离测试使用专用测试密钥；没有修改业务环境主密钥，也未向业务库写入测试服务器。Spring Boot 2.7 已结束官方开源维护。Flyway 在 PostgreSQL 16 上会提示其官方测试支持版本范围；本轮实际启动和结构校验通过。

## 最终部署核对

真实 HertzBeat 桥接集成测试另计 1 项通过，覆盖正常 → HTTP 断言失败 → 恢复及告警闭环；该测试不计入上述 237 项。测试期间出现后台调度的 H2 锁竞争日志，测试断言和清理均完成，结果为 0 失败/错误。

本地 API/Web 已重建并更新。运行容器报告 Temurin `1.8.0_504`，API 健康；网页 HTTP 200，登录和概览接口正常，原有 3 项服务仍在。两个隔离验证容器及仅含合成数据的测试库已删除。当前业务未配置主密钥，实际深度巡检仍需先完成部署配置。
