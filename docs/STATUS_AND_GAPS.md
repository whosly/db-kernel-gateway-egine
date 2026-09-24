# 功能现状与缺口（本分支）

> **分支**：`future/database-wire-protocol-foundation`（对照 commit `7a1ab3b`）  
> **更新原则**：只写有代码/测试/配置证据的结论；「规划中」不得写成「已实现」。  
> **导航**：见 [README.md](README.md)。

## 1. 构建与测试基线

| 项 | 现状 | 证据 |
|---|---|---|
| 非集成 `@Test`/`@ParameterizedTest` 注解数 | **326**（历史基线 323 + `VirtualThreadExecutorsTest` 3） | `mvn test` Results；排除 `*IntegrationTest` |
| 集成测试 | 14 条注解；默认 surefire **排除** `*IntegrationTest` | `pom.xml` surefire excludes；`-Pintegration-test` 才跑 |
| 本环境 `mvn test`（`JAVA_HOME`=JDK 17） | **BUILD SUCCESS：Tests run 326, Failures 0, Errors 0, Skipped 0** | 日志 `/workspace/repos/mvn-test-jdk17-after-vt-fix.log`；VT 经反射，JDK 17 回退固定池 |
| `pom.xml` 编译目标 | `maven.compiler.source/target=17` | **保持 17**；不升到 21 |

**结论**：编译目标保持 17；VT 仅在 JDK 21+ 运行期启用。当前 `mvn test` 为 **326** 全绿（323 历史基线 + 3 条 VT helper 测试）。见 P0-1。

## 2. 能力总览（按主题）

| 主题 | 状态 | 摘要 |
|---|---|---|
| MySQL / PG 透明转发 | **done** | 阻塞 `ServerSocket` + 双工 `DuplexRelay` 逐字节转发 |
| 明文观测 / 状态机 | **partial** | 命令/消息抽取与会话状态齐全；TLS/压缩后 opaque |
| 结果集脱敏 | **partial** | MySQL 文本+二进制、PG 已接线；类型边界见 README；无规则时透明 |
| 审计 spool / JDBC ship | **partial** | 实现齐全；**缺专用单元/验收测试**；默认关闭 |
| 风控策略 | **partial** | 接口 + `allowAll()` 默认；**无内置规则、无配置装配** |
| 连接治理 | **partial** | `max-connections`、CIDR、idle timeout 有；配置键见 §3 |
| 多后端 | **partial** | 有序 failover；**无按库/用户/权重路由** |
| Cancel | **partial** | PG：识别+键索引关联，不代发；MySQL：`COM_PROCESS_KILL` 透传 |
| TLS 终止 / 明文强制 | **partial** | opaque tunnel；`require-cleartext-inspection` 可拒 TLS |
| NIO / 事件驱动 | **missing** | 仍为阻塞流 + 线程/虚拟线程 per connection |
| JDBC 旁路路径 | **missing/legacy** | `DatabaseConnectionService` 存在但 wire 路径未使用 |
| 运维产品化 | **partial** | 内存 `GatewayRuntimeMetrics`；无 Actuator 暴露；CLI 阻塞 `System.in` |

## 3. 配置键（以 `GatewayConfig` 绑定为准）

Spring 实际读取的键（`@Value`）与模板一致的是嵌套 `gateway.target.*`。  
`src/main/resources/application.yml` 里的扁平 `target-host` / `idle-timeout-millis` **不会**绑定到当前 `GatewayConfig`，属文档与默认文件漂移（P0-2）。

| 键 | 默认（代码） | 说明 |
|---|---|---|
| `gateway.proxy-db-type` | `mysql` | `mysql` \| `postgresql` |
| `gateway.proxy-port` | `3307` | 协议代理监听端口 |
| `gateway.target.host` / `.port` / `.username` / `.password` / `.database` | 见代码默认 | 单后端；模板见 `application-*-template.yml` |
| `gateway.backend-endpoints` | 空 | `host:port,...` 追加 failover 列表 |
| `gateway.max-connections` | `200`（yml 曾写 256，以代码为准） | `Semaphore` 限流 |
| `gateway.idle-timeout-seconds` | `0` | 映射为 `SoTimeout`；**不是** `idle-timeout-millis` |
| `gateway.allowed-client-cidrs` | 空 | 空=`allowAll`；否则 `CidrClientAddressPolicy` |
| `gateway.virtual-threads` | `true` | 请求 VT；JDK 17 反射不可用时回退固定池（见 P0-1） |
| `gateway.require-cleartext-inspection` | 未设时跟随 `audit.enabled` | TLS opaque 会话可拒绝 |
| `gateway.rewrite.max-message-bytes` / `max-hold-millis` | `1048576` / `1000` | 改写持有上界，超限 fail-closed |
| `gateway.audit.*` | 见 README 审计表 | 默认 `enabled=false` |

风控：**没有** `gateway.risk.*` 配置项；`GatewayConfig` **未**调用 `setDatabaseRiskPolicy`，保持 `DatabaseRiskPolicy.allowAll()`。

## 4. P0 / P1 / P2 缺口明细

### P0（阻塞正确构建或误导运维）

| ID | 项 | 状态 | 证据 | 建议下一步 |
|---|---|---|---|---|
| P0-1 | JDK 17 与虚拟线程 API | **fixed** | `VirtualThreadExecutors` 用 MethodHandles 反射调用 `ofVirtual` / `newThreadPerTaskExecutor`；`AbstractProtocolAdapter` / `DuplexRelay` 无直接符号；`pom` 保持 17；JDK 17 回退平台池 | 已落地反射方案；JDK 21+ 运行时仍可用 VT；勿把 `pom` 升到 21 |
| P0-2 | `application.yml` 与 `GatewayConfig` 键不一致 | **partial/bug** | yml：`target-host`、`idle-timeout-millis`；代码：`gateway.target.host`、`idle-timeout-seconds` | 统一为嵌套 `target.*` + `idle-timeout-seconds`（与模板一致），或改 `@Value` 兼容扁平键 |
| P0-3 | 文档宣称的审计验收测试缺失 | **missing** | README 提到 `AuditTrailAcceptanceTest` / `shipsEveryStatement...`；`src/test` **无** audit 测试类 | 补 `AuditSpool`/`AuditShipper`/`SpoolingTrafficObserver` 单元与并发验收，或从 README 删除虚假引用 |
| P0-4 | 风控默认全放行且无装配 | **partial** | `DatabaseRiskPolicy.allowAll()`；`GatewayConfig` 不注入策略 | 至少提供可配置拒绝清单（语句类型/关键字）或文档明确「默认无风控」并提供示例 `@Bean` |

### P1（协议完整度 / 安全边界）

| ID | 项 | 状态 | 证据 | 建议下一步 |
|---|---|---|---|---|
| P1-1 | TLS/压缩可观测性 | **partial** | extractor `opaqueTunnel`；`require-cleartext-inspection` | 明确产品策略：拒绝 TLS vs 未来终止 TLS；补充拒绝路径测试 |
| P1-2 | MySQL `COM_STMT_EXECUTE` 参数观测 | **partial** | 命令透传；SQL 抽取偏 `COM_QUERY`/`PREPARE` | 扩展参数/属性观测或标明「仅透传」 |
| P1-3 | PG Cancel 只关联不代发 | **done（设计如此）/partial（产品）** | `PostgreSQLCancelKeyRegistry`；adapter 注释 | 若需网关侧 cancel 编排，另开设计；现状保持透明转发 |
| P1-4 | 多后端仅 failover | **partial** | `FailoverBackendProvider` 顺序尝试 | 连接级路由（库名/用户/标签）、健康检查、半开熔断 |
| P1-5 | 结果集脱敏类型边界 | **partial** | README + `MySQLBinaryValues` / `PostgreSQLBinaryValues` | 扩展 decimal/时间类型或保持拒绝；补边界表到本文件 |
| P1-6 | 连接池化 | **missing** | `SessionSnapshot` / dirtiness 已预留；无池实现 | 仅在 `CONFIRMED`+非脏+非事务时复用；先单测再接线 |

### P2（并发模型 / 产品化 / 测试覆盖）

| ID | 项 | 状态 | 证据 | 建议下一步 |
|---|---|---|---|---|
| P2-1 | NIO / 少线程模型 | **missing** | `ServerSocket.accept` + `InputStream.read` 循环 | 评估虚拟线程是否足够；真 NIO 需重做 framing 边界 |
| P2-2 | JDBC vs 协议代理分裂 | **partial** | `DatabaseConnectionService`（DriverManager）字段存在但 wire 路径未调用 | 删除或隔离为管理面工具；避免与透明代理语义混淆 |
| P2-3 | HTTP 管控面 | **partial** | `GatewayController` / `CommandLineInterface` 启动代理；CLI 读 `System.in` | 非交互启动默认 `start`；Actuator 暴露 sessions/metrics |
| P2-4 | Metrics 出口 | **partial** | `GatewayRuntimeMetrics` 计数器在内存 | Micrometer/Actuator 绑定；拒绝连接/failover/opaque 告警 |
| P2-5 | 审计测试与运维手册 | **partial** | 实现在 `audit/*`；缺测试；README 运维段落较长 | 测试见 P0-3；运维手册可拆「开启清单 / 告警清单」 |
| P2-6 | 集成测试在 CI 可复现 | **partial** | 依赖本机 `integration-test-local.properties` | Testcontainers 或文档化跳过策略 |
| P2-7 | Oracle / SQL Server 等 | **missing** | 规则文档允许扩展；无 adapter | 不在本阶段范围 |

## 5. 测试覆盖缺口（相对实现）

| 区域 | 实现 | 默认 `mvn test` 覆盖 |
|---|---|---|
| MySQL/PG framing、session、masking interceptor | 有 | 有（大量单元） |
| DuplexRelay / RewriteLimits / Inspector 并发 | 有 | 有 |
| Failover / CIDR / GatewayConfig | 有 | 有 |
| Audit spool / shipper / JDBC destination | **有** | **基本无**（P0-3） |
| 内置 RiskPolicy 实现 | **无** | N/A |
| 虚拟线程回退路径 | `VirtualThreadExecutors` + 固定池回退 | `VirtualThreadExecutorsTest` 在 JDK 17 验证不抛并执行任务 |
| 真库集成 + 脱敏 | 有 | 需 `-Pintegration-test` + 本地库 |

## 6. JDBC 路径 vs 协议代理（说明）

- **主路径**：客户端 wire → `AbstractProtocolAdapter` → `BackendProvider.acquire()` → `DuplexRelay` → 目标库。认证与结果由目标库完成。
- **JDBC 服务**：`DatabaseConnectionService` 用 `DriverManager` 建连，**不参与**上述转发；属遗留/旁路，勿当作网关数据平面。
- **审计 JDBC**：`gateway.audit.destination=jdbc` 只把审计记录搬到**独立**库（`JdbcAuditDestination`），与被代理库分离。

## 7. 维护

- 合并改变能力边界的 PR 时，同步更新本文件对应行的「状态 / 证据」。
- README「能力总览」只保留摘要，细节与缺口以本文件为准，避免两处互相漂移。
