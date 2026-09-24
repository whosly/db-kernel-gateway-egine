# 运维手册（开启清单 / 告警清单）

> 与 [`STATUS_AND_GAPS.md`](STATUS_AND_GAPS.md) 对齐：只写本分支已具备的运维面。  
> 网关默认 **自动启动协议代理**（`Application` 生命周期）；交互式 stdin CLI **默认关闭**。

## 开启清单

| 步骤 | 做什么 | 配置 / 命令 |
|---|---|---|
| 1 | JDK 17 + 复制模板 | `cp src/main/resources/application-*-template.yml src/main/resources/application-dev.yml` |
| 2 | 填目标库（勿提交密码） | `gateway.target.*`、`gateway.proxy-port`、`gateway.proxy-db-type` |
| 3 | 启动（非交互） | `mvn spring-boot:run -Dspring-boot.run.profiles=dev` 或 `java -jar …` |
| 4 | 确认代理在听 | 日志含 protocol adapter started；或 `GET /gateway/status` |
| 5 | （可选）审计 | `gateway.audit.enabled=true`；生产建议独立盘与 `destination` |
| 6 | （可选）明文强制 | 需审计时设 `gateway.require-cleartext-inspection=true`（或随审计默认） |
| 7 | （可选）风控拒绝清单 | `gateway.risk.denied-operations` / `denied-statement-keywords` |
| 8 | （可选）交互 CLI | 仅调试：`gateway.cli.interactive=true`（会读 `System.in`） |

### HTTP / Actuator 出口（P2-3 / P2-4）

| 路径 | 用途 |
|---|---|
| `GET /gateway/status` | 是否运行、协议、端口、会话数 |
| `GET /gateway/metrics` | 内存计数器（接受/拒绝/opaque/failover/policy） |
| `POST /gateway/start` / `POST /gateway/stop` | 启停代理（进程仍在） |
| `GET /actuator/gateway` | 同上摘要（嵌套 `metrics`） |
| `GET /actuator/health` | Spring Boot 健康检查 |

计数器进程内、非持久；重启清零。未接 Micrometer 远程后端。

## 告警清单（建议阈值）

| 信号 | 来源 | 建议 |
|---|---|---|
| `connectionsRejectedLimit` 持续上升 | `/gateway/metrics` | 调大 `max-connections` 或扩容；查客户端风暴 |
| `connectionsRejectedPolicy` 上升 | 同上 | 核对 `allowed-client-cidrs` |
| `opaqueTunnelsDenied` 上升 | 同上 | 客户端在走 TLS/压缩而 `require-cleartext-inspection` 开启 |
| `opaqueTunnelsEntered` 高且未 deny | 同上 | 明文观测/审计失效窗口——是否应强制明文 |
| `backendFailovers` 突发 | 同上 | 主后端不可达或冷却中；查目标与 `backend-endpoints` |
| `policyDenials` 上升 | 同上 | 风控命中；核对 deny 清单是否过宽 |
| 审计 spool 写失败 / 熔断 | 应用日志 | fail-closed 会拒流量；查磁盘配额与权限 |
| 代理端口起不来 | 启动日志 | 端口占用或配置错误 |

## 集成测试（P2-6 跳过策略）

| 命令 | 行为 |
|---|---|
| `mvn test` | surefire **排除** `*IntegrationTest`；默认单元全绿，**不需要 Docker** |
| `mvn -Pintegration-test test` 且无 local props | 跑 IntegrationTest，但 `integration.enabled=false` → **`assumeTrue` 跳过**（Skipped，不失败） |
| 真跑集成 | 建 `src/test/resources/integration-test-local.properties`（gitignore）：`integration.enabled=true` + 可达 MySQL/PG |
| `-Pintegration-testcontainers` | **占位 stub**（pom 属性）；尚未接线 Testcontainers，勿当已实现 |

细节见 `src/test/resources/integration-test.properties` 注释。

## JDBC 旁路说明（P2-2）

- **数据平面**：客户端 wire → adapter → `BackendProvider` → 目标库 socket。
- `DatabaseConnectionService`（DriverManager）已 **@Deprecated**，**不参与**转发；勿与透明代理混淆。
- 审计 `destination=jdbc` 是独立目的库，与被代理库分离。
