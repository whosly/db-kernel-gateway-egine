# 功能现状与缺口（本分支）

> **分支**：`future/database-wire-protocol-foundation`  
> **更新原则**：只写有代码/测试/配置证据的结论；「规划中」不得写成「已实现」。  
> **导航**：见 [README.md](README.md)。
>
> **近况**：Vue console Phase A/A+/B/B+ + **C partial / E lite** — 审计状态 · 进程内 metrics history · 风控规则热挂 · 连接池徽章 · 可选 read-token。

## 1. 构建与测试基线

| 项 | 现状 | 证据 |
|---|---|---|
| 非集成 `@Test`/`@ParameterizedTest` 注解数 | **458** | `mvn test` Results；排除 `*IntegrationTest` |
| 集成测试 | 14 条注解；默认 surefire **排除** `*IntegrationTest`；无 local props 时 `-Pintegration-test` **assumeTrue 跳过**；本机有库时可 14/14 绿 | `pom.xml` excludes；跳过策略见 `docs/OPS.md` / `integration-test.properties` |
| 本环境 `mvn test`（`JAVA_HOME`=JDK 17） | **BUILD SUCCESS：Tests run 501, Failures 0, Errors 0, Skipped 0** | surefire；含 SQL Server P0 + 管控台 Phase A–E lite |
| `pom.xml` 编译目标 | `maven.compiler.source/target=17` | **保持 17**；不升到 21 |

**结论**：编译目标保持 17；VT 仅在 JDK 21+ 运行期启用。管控台已完成 Phase B/B+/C partial/E lite，并完成本轮**实例编辑/克隆/导入/筛选/批量**与 **SQL 工作台 v1**；完整 SSO / 外部 Prometheus·Grafana / 完整 SQL IDE 仍规划。见 P0 / P1 / P2。

## 2. 能力总览（按主题）

| 主题 | 状态 | 摘要 |
|---|---|---|
| MySQL / PG 透明转发 | **done** | 阻塞 `ServerSocket` + 双工 `DuplexRelay` 逐字节转发 |
| SQL Server（TDS）透明转发 | **partial（P0）** | `SqlServerProtocolAdapter` + framing；无观测/脱敏；计划 [`SQLSERVER_TDS_PLAN.md`](SQLSERVER_TDS_PLAN.md) |
| 明文观测 / 状态机 | **partial** | 命令/消息抽取与会话状态齐全；TLS/压缩后 opaque |
| 结果集脱敏 | **partial** | MySQL 文本+二进制、PG 已接线；类型边界见 README；无规则时透明 |
| 审计 spool / JDBC ship | **partial** | 实现齐全；专用单元测试已补（P0-3）；默认关闭 |
| 风控策略 | **partial** | `DenyListDatabaseRiskPolicy` + `gateway.risk.*` 装配；空配置仍 `allowAll()` |
| 连接治理 | **partial** | `max-connections`、CIDR、idle timeout 有；配置键见 §3 |
| 多后端 | **partial（improved）** | 有序 failover + 冷却；**可选**按库名/用户/权重路由（`gateway.routing.*`，默认关） |
| Cancel | **done（设计如此）** | PG：识别+键索引关联，**不代发**（透明转发 CancelRequest）；MySQL：`COM_PROCESS_KILL` 透传 |
| TLS 终止 / 明文强制 | **partial（improved）** | 可选 `gateway.tls.*` 客户端 TLS 终止（共享基础设施）；未启用时仍 opaque / `require-cleartext-inspection` |
| NIO / 事件驱动 | **missing（刻意）** | 阻塞流 + 每连接线程/VT；**不以 NIO 重写为当前方向**（见 P2-1） |
| JDBC 旁路路径 | **legacy（已标注）** | `DatabaseConnectionService` `@Deprecated`；wire 路径未使用 |
| 运维产品化 | **partial（improved）** | 非交互默认启动；`/gateway/*` + `/actuator/gateway`；**管控台** `/console`（实例中心）；**同 JVM 多 listener**（`GatewayListenerRuntime`）；交互 CLI 默认关 |

## 3. 配置键（以 `GatewayConfig` 绑定为准）

Spring 实际读取的键（`@Value`）与默认 `application.yml`、模板一致：嵌套 `gateway.target.*` + `idle-timeout-seconds`（P0-2 已对齐）。

| 键 | 默认（代码） | 说明 |
|---|---|---|
| `gateway.proxy-db-type` | `mysql` | `mysql` \| `postgresql` \| `sqlserver`/`mssql`（经 `ProtocolAdapterRegistry`）；`oracle` 仍为 stub |
| `gateway.proxy-port` | `3307` | 协议代理监听端口 |
| `gateway.target.host` / `.port` / `.username` / `.password` / `.database` | 见代码默认 | 单后端；模板见 `application-*-template.yml` |
| `gateway.backend-endpoints` | 空 | `host:port,...` 追加 failover 列表 |
| `gateway.routing.enabled` | `false` | 按库名/用户路由；false 时行为与仅 failover 相同 |
| `gateway.routing.rules[]` | 空 | `match-database` / `match-username` + `endpoints`（`host:port` 或 `host:port:weight`） |
| `gateway.max-connections` | `200` | `Semaphore` 限流 |
| `gateway.idle-timeout-seconds` | `0` | 映射为 `SoTimeout`（秒，非 millis） |
| `gateway.allowed-client-cidrs` | 空 | 空=`allowAll`；否则 `CidrClientAddressPolicy` |
| `gateway.virtual-threads` | `true` | 请求 VT；JDK 17 反射不可用时回退固定池（见 P0-1） |
| `gateway.require-cleartext-inspection` | 未设时跟随 `audit.enabled` | TLS opaque 会话可拒绝 |
| `gateway.rewrite.max-message-bytes` / `max-hold-millis` | `1048576` / `1000` | 改写持有上界，超限 fail-closed |
| `gateway.audit.*` | 见 README 审计表 | 默认 `enabled=false` |
| `gateway.pool.enabled` | `false` | 协议无关后端池；仅 CONFIRMED+clean+非事务复用 |
| `gateway.pool.max-idle` | `8` | 空闲上限 |
| `gateway.pool.reset-mode` | `none` | `none`（默认，close-if-unsafe）\| `protocol`（注册表 SPI：MySQL `COM_RESET_CONNECTION`、PG `DISCARD ALL`） |
| `gateway.tls.*` | 见代码 | 可选客户端 TLS 终止 |
| `gateway.risk.denied-operations` | 空 | 逗号分隔操作名；空则不按操作拒绝 |
| `gateway.risk.denied-statement-keywords` | 空 | 逗号分隔语句子串；空则不按关键字拒绝 |
| `gateway.cli.interactive` | `false` | true 时才读 `System.in` CLI；默认非交互 |
| `gateway.catalog.databases[]` | 见 application.yml | 支持库**类型**目录（管控台）；与实例注册表分离 |
| `gateway.instances[]` | 空→合成 default | 协议无关**实例**注册表（多类型可混）；非空时每个 enabled+creatable 条目独立 listener（`GatewayListenerRuntime`） |


风控：`GatewayConfig` 调用 `setDatabaseRiskPolicy(DenyListDatabaseRiskPolicy.of(...))`；两份清单皆空时退回 `DatabaseRiskPolicy.allowAll()`（向后兼容）。

## 4. P0 / P1 / P2 缺口明细

### P0（阻塞正确构建或误导运维）

| ID | 项 | 状态 | 证据 | 建议下一步 |
|---|---|---|---|---|
| P0-1 | JDK 17 与虚拟线程 API | **fixed** | `VirtualThreadExecutors` 用 MethodHandles 反射调用 `ofVirtual` / `newThreadPerTaskExecutor`；`AbstractProtocolAdapter` / `DuplexRelay` 无直接符号；`pom` 保持 17；JDK 17 回退平台池 | 已落地反射方案；JDK 21+ 运行时仍可用 VT；勿把 `pom` 升到 21 |
| P0-2 | `application.yml` 与 `GatewayConfig` 键不一致 | **fixed** | 默认 yml 改为嵌套 `gateway.target.*` + `idle-timeout-seconds`；模板补充同键；README 移除扁平键警告 | 勿再引入扁平别名；环境变量用 `GATEWAY_IDLE_TIMEOUT_SECONDS` |
| P0-3 | 审计专用验收测试缺失 | **fixed** | `src/test/.../audit/{AuditSpool,AuditShipper,SpoolingTrafficObserver}Test`；覆盖启用 spool/ship 与禁用 noop | JDBC destination 真库验收仍属集成范围 |
| P0-4 | 风控默认全放行且无装配 | **fixed** | `DenyListDatabaseRiskPolicy` + `gateway.risk.denied-operations` / `denied-statement-keywords`；`GatewayConfig` 注入；空配置 allow-all | 可按需扩展 allow-list / 分级策略 |

### P1（协议完整度 / 安全边界）

| ID | 项 | 状态 | 证据 | 建议下一步 |
|---|---|---|---|---|
| P1-1 | TLS/压缩可观测性 | **partial（improved）** | extractor `opaqueTunnel`；`require-cleartext-inspection`；**可选 TLS 终止**（`ClientTlsTerminator` + `gateway.tls.*`，协议无关 accept 路径）；单测用测试 keystore | 压缩后仍 opaque；协议内建 SSL 协商（MySQL capability / PG SSLRequest）仍非终止路径；后端 mTLS 未做 |
| P1-2 | MySQL `COM_STMT_EXECUTE` 参数观测 | **partial（improved）** | PREPARE 登记 `statement_id→param_count`；EXECUTE 发出事件（statement id + 可解析时的 param types）；**不**把绑定值写入 statement 文本；单测覆盖 | 可选：审计侧对 string 类型参数做脱敏摘要；仍无改写 EXECUTE |
| P1-3 | PG Cancel 只关联不代发 | **done（设计如此）** | `PostgreSQLCancelKeyRegistry` 仅索引；adapter / 单测明确「associate-only」；CancelRequest 仍由客户端短连接透明转发 | 若需网关代发 cancel，需 session→backend socket 映射，另开设计 |
| P1-4 | 多后端仅 failover | **partial（improved）** | failover + 冷却；**`RoutingBackendProvider`** + **`RoutingHandshakeProbe` / `ProbedHandshake`**：PG 在 `acquire` 前 peek StartupMessage 填充 `RoutingContext`（user/database）并透明 replay；MySQL **server-first** 仍 `acquire(empty)`（禁止伪造 greeting）；`MySqlHandshakeResponseRouting` 解析身份供观测/日后扩展；Oracle/SQL Server 复用同一 SPI | 半开熔断可再增强；MySQL 身份路由需终结认证或延后选路（见 §4.3） |
| P1-5 | 结果集脱敏类型边界 | **partial（improved）** | MySQL：`bit` 按长度前缀可读可改写；PG：`int2/4/8`、`bool`、`float4/8` 二进制改写；decimal/时间/uuid/geometry 等仍 fail-closed；边界表见 §4.2 | decimal/时间编码若要做需独立设计 |
| P1-6 | 连接池化 | **partial（improved）** | `PooledBackendProvider` + `gateway.pool.enabled`（默认 false）；`gateway.pool.reset-mode=none\|protocol`（默认 none）；`protocol` 时经 registry SPI：MySQL `MySqlBackendSessionReset`（`COM_RESET_CONNECTION`）、PG `PostgreSQLBackendSessionReset`（`DISCARD ALL`）；失败/拒绝关闭 socket；单测覆盖成功/失败 | 按身份/库名分池未做；reset 仍仅在已确认可复用 socket 上执行 |

### P2（并发模型 / 产品化 / 测试覆盖）

| ID | 项 | 状态 | 证据 | 建议下一步 |
|---|---|---|---|---|
| P2-1 | NIO / 少线程模型 | **missing（deferred）** | 仍 `ServerSocket.accept` + 阻塞读；并发模型选定为 **每连接线程 / 可选 VT**（`VirtualThreadExecutors`） | **不做 NIO 重写**；若 JDK 21+ VT 不足再开专项 |
| P2-2 | JDBC vs 协议代理分裂 | **partial（improved）** | `DatabaseConnectionService` / adapter 字段 `@Deprecated` + javadoc；STATUS §6；wire 仍走 `BackendProvider` | 无调用方后可删类；勿接入 DuplexRelay |
| P2-3 | HTTP 管控面 / 管控台 | **partial（improved）** | Vue3+TS+Vite；H2 CRUD；脱敏热挂；B/B+/C partial/E lite/风控；**本轮完备**：`PUT` 编辑、`clone`、`import`、列表筛选、`bulk` 启停、**SQL 工作台** `…/sql/execute`（JDBC 目标库）。设计 [`CONSOLE_ARCHITECTURE.md`](CONSOLE_ARCHITECTURE.md) §11–§15。**本轮不做** Prometheus 出口 | 完整 SSO/HTTPS/审计 spool 内容 UI / SQL IDE / 经代理口执行 未做 | 完整鉴权/HTTPS 仍规划；Compose 仅 lab；外部 Micrometer 见 P2-4 |
| P2-4 | Metrics 出口 | **partial（improved）** | 每 listener 独立 metrics；overview 求和 + `legacyMetrics`；**E lite**：`MetricsHistorySampler` + `GET …/metrics/history` + Overview SVG 火花图（内存环） | 外部 Prometheus/Grafana 非必需 | 可选后续接 Micrometer；告警阈值见 `docs/OPS.md` |
| P2-5 | 审计测试与运维手册 | **partial（improved）** | P0-3 单测已有；**`docs/OPS.md`** 开启清单 / 告警清单；README 运维段改为索引 | JDBC 审计真库验收仍缺 |
| P2-6 | 集成测试在 CI 可复现 | **partial（improved）** | 跳过策略写入 `integration-test.properties` + OPS；`-Pintegration-test` 无 props → `assumeTrue` skip；`-Pintegration-testcontainers` **stub only** | 真 Testcontainers 接线另开；默认 `mvn test` 仍不需 Docker |
| P2-7 | 多库扩展点 / Oracle·SQL Server | **in-progress / partial（SQL Server P0）** | 第三库定为 **SQL Server TDS**（非 Oracle）。`SqlServerProtocolAdapter` + `sqlserver`/`mssql` 注册 + TDS framing + 透明 `DuplexRelay`；模板 `application-sqlserver-template.yml`（31433→1433）；计划见 [`SQLSERVER_TDS_PLAN.md`](SQLSERVER_TDS_PLAN.md)。Oracle 仍 stub。P0 **无** Login7 观测/脱敏/协议 reset | P1 观测与 Docker 冒烟；P2 深消息/脱敏/cancel；Oracle 另开 |


### 4.1 TLS / 明文强制（产品策略，P1-1）

池化与 TLS 终止均为 **协议无关共享基础设施**（`AbstractProtocolAdapter` / `BackendProvider` 装饰器）。新库（Oracle / SQL Server）只需新增 `ProtocolAdapter` + 可选 `BackendSessionReset`，不必重做池/TLS。

| 模式 | 行为 |
|---|---|
| 默认（`gateway.tls.enabled=false`） | 透明代理：客户端协商 SSL/压缩后进入 **opaque tunnel**（字节转发，不解析） |
| `gateway.require-cleartext-inspection=true`（未显式设置时跟随 `audit.enabled`） | opaque tunnel → **拒绝会话**（DENY） |
| `gateway.tls.enabled=true` + keystore | **stunnel 式客户端 TLS 终止**：accept 后 `SSLSocket` 服务端握手，之后走既有明文 framing/inspection；opaque-tunnel-from-client-SSL **不再适用**于客户端腿 |
| 后端 TLS | 仍走既有 `BackendProvider`（通常明文连本地 Docker）；后端 mTLS **未做** |

**安全注意**：无证书则终止保持关闭；勿把生产 keystore 密码写入仓库（用环境变量）；测试 keystore 仅在 `src/test/resources/tls/`。


### 4.3 握手身份与路由（P1-4 follow-up）

| 协议 | `acquire(RoutingContext)` 时身份 | 行为 | 原因 |
|---|---|---|---|
| **PostgreSQL** | **有**（cleartext StartupMessage 的 `user` / `database`） | `PostgreSQLStartupRouting` peek 首包 → `acquire(context)` → `DuplexRelay` 前缀 replay 原字节 | 客户端先发；SSLRequest/CancelRequest 无身份 → empty + replay |
| **MySQL** | **无**（恒 `RoutingContext.empty()`） | 先连 fallback/默认后端发 greeting，再中继；Handshake Response 仅观测（`MySqlHandshakeResponseRouting`） | server-first；伪造 Initial Handshake 违反透明规则；延后重连会破坏基于 scramble 的认证 |
| **SQL Server（TDS）** | P0 透明中继；身份 **无**（空 `RoutingContext`） | `SqlServerProtocolAdapter` 先 `acquire(empty)` 再双工；PreLogin 无 user/db；Login7 观测属 P1 | 见 [`SQLSERVER_TDS_PLAN.md`](SQLSERVER_TDS_PLAN.md) |
| **Oracle** | 未实现 wire | stub；应实现同一 `RoutingHandshakeProbe` | 扩展点已预留 |

**配置含义**：`gateway.routing.rules` 的 `match-database` / `match-username` 对 **PostgreSQL cleartext startup** 在首连时生效；对 **MySQL** 首连仍走 fallback（规则不参与初始选路）。SSL/加密协商后的 PG opaque 路径同样看不到 StartupMessage 参数，走 fallback。

### 4.4 同 JVM 多 listener（P2-3）

| 项 | 现状 |
|---|---|
| 配置 | `gateway.instances[]` 非空 → 每条 enabled+creatable 建独立 `ProtocolAdapter`；空 → 合成 `id=default` |
| 可创建类型 | MySQL / PostgreSQL / SQL Server（P0）；Oracle → `UNSUPPORTED`，不建 adapter |
| 端口冲突 | 同 listenPort 的两条 enabled 实例 → 启动期 `IllegalStateException` fail-fast |
| legacy `/gateway/*` | 映射 runtime legacy adapter（proxy-* 匹配 → `default` → 第一个 bound） |
| 指标 | 实例 API = 该 listener 计数；`/gateway/metrics` = legacy 计数（**不是**全实例求和） |
| UNBOUND | 多 listener 落地后 creatable 槽位不再标 UNBOUND；剩余仅枚举兼容。DISABLED=未启用；UNSUPPORTED=不可创建；STOPPED/RUNNING=已绑定 |

### 4.2 结果集脱敏类型边界（P1-5）

| 协议 | 可非空改写（二进制） | 可读但非空改写拒绝 / 未知则整行不脱敏 |
|---|---|---|
| MySQL | 整数/浮点、长度前缀字符串族、**bit**（长度前缀） | `decimal`（packed）、date/time/datetime/timestamp、`geometry`、未知 type |
| PostgreSQL | text 族/bytea/json(b)、**int2/4/8**、**bool**、**float4/8** | `numeric`、时间类型、uuid、未知 OID |

无规则时两条路径均保持透明。

## 5. 测试覆盖缺口（相对实现）

| 区域 | 实现 | 默认 `mvn test` 覆盖 |
|---|---|---|
| MySQL/PG framing、session、masking interceptor | 有 | 有（大量单元） |
| DuplexRelay / RewriteLimits / Inspector 并发 | 有 | 有 |
| Failover（含冷却跳过）/ Routing（库/用户/权重）/ CIDR / GatewayConfig | 有 | 有 |
| Audit spool / shipper / JDBC destination | **有** | **有**（P0-3 专用单测；`GatewayConfigTest` 亦覆盖开关） |
| 内置 RiskPolicy 实现 | `DenyListDatabaseRiskPolicy` | `DenyListDatabaseRiskPolicyTest` + `GatewayConfigTest` 装配 |
| 虚拟线程回退路径 | `VirtualThreadExecutors` + 固定池回退 | `VirtualThreadExecutorsTest` 在 JDK 17 验证不抛并执行任务 |
| `require-cleartext-inspection` 拒绝路径 | `DatabaseTrafficInspector` | 有（P1-1） |
| MySQL `COM_STMT_EXECUTE` 观测 | extractor | 有（P1-2） |
| `ConfirmedReuseBackendPool` / `PooledBackendProvider` / `ClientTlsTerminator` | 已接线（默认关） | 有单测；`GatewayConfigTest` 装配 |
| `MySqlBackendSessionReset` / `PostgreSQLBackendSessionReset` | `reset-mode=protocol` | 有单测（OK/ERR、池关闭） |
| `ProtocolAdapterRegistry` | 内置 + stub | `ProtocolAdapterRegistryTest` |
| `RoutingBackendProvider` / `WeightedEndpointSelector` | `gateway.routing.*`（默认关） | `RoutingBackendProviderTest` + `GatewayConfigTest` 装配 |
| 握手填充 `RoutingContext` | PG peek；MySQL empty + parser | `PostgreSQLStartupRoutingTest` / `PostgreSQLProtocolAdapterRoutingTest`；`MySqlHandshakeResponseRoutingTest` / `MySqlProtocolAdapterRoutingTest` |
| 真库集成 + 脱敏 | 有 | 需 `-Pintegration-test` + 本地库；无 props 则 assumeTrue 跳过（P2-6） |
| `/gateway/*` + `/actuator/gateway` | 有 | `GatewayOpsSurfaceTest` + `CommandLineInterfaceTest`（非交互） |

## 6. JDBC 路径 vs 协议代理（说明）

- **主路径**：客户端 wire → `AbstractProtocolAdapter` → `BackendProvider.acquire()` → `DuplexRelay` → 目标库。认证与结果由目标库完成。
- **JDBC 服务**：`DatabaseConnectionService` 用 `DriverManager` 建连，**不参与**上述转发；已 `@Deprecated`（P2-2），勿当作网关数据平面。
- **审计 JDBC**：`gateway.audit.destination=jdbc` 只把审计记录搬到**独立**库（`JdbcAuditDestination`），与被代理库分离。

## 7. 维护

- 合并改变能力边界的 PR 时，同步更新本文件对应行的「状态 / 证据」。
- README「能力总览」只保留摘要，细节与缺口以本文件为准，避免两处互相漂移。
