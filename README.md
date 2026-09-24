# 数据库内核网关引擎

一个基于 Java 17 和 Spring Boot 的数据库协议网关。客户端连接网关端口，网关把
MySQL 或 PostgreSQL wire protocol 流量透明转发到真实数据库，并在明文阶段提供
SQL 观测、可选审计留痕与结果集脱敏等扩展点。

## 目录

- [分支与构建状态](#分支与构建状态)
- [能力总览](#能力总览)
- [环境要求](#环境要求)
- [配置说明](#配置说明)
- [MySQL 网关](#mysql-网关)
- [PostgreSQL 网关](#postgresql-网关)
- [测试](#测试)
- [审计与脱敏](#审计与脱敏)
- [文档索引](#文档索引)
- [开发约定](#开发约定)

## 分支与构建状态

| 项 | 本分支（`future/database-wire-protocol-foundation` / `7a1ab3b`） |
|---|---|
| 默认单元测试规模 | **326** 条（历史 323 + VT helper 3；`pom` 排除 `*IntegrationTest`） |
| JDK / 编译 | `pom` 目标保持 **17**；虚拟线程经 `VirtualThreadExecutors` **反射**在 JDK 21+ 启用，JDK 17 回退平台线程池（见 [`docs/STATUS_AND_GAPS.md`](docs/STATUS_AND_GAPS.md) P0-1） |
| JDK 17 `mvn test` | **326 / 0 failures**（见 STATUS P0-1）；历史「323」为修复前数量基线 |

详细缺口与证据表：**[`docs/STATUS_AND_GAPS.md`](docs/STATUS_AND_GAPS.md)**。

## 能力总览

下表区分「已接线」与「接口/默认占位」。完整协议枚举见
[`docs/PROTOCOL_REFERENCE_TABLES.md`](docs/PROTOCOL_REFERENCE_TABLES.md)。

| 能力 | 状态 | 说明 |
|---|---|---|
| MySQL / PostgreSQL 透明代理 | **已实现** | 真实认证在目标库；网关不保存、不校验、不记录明文密码 |
| 明文 SQL 观测 | **已实现** | MySQL `COM_QUERY` / `COM_STMT_PREPARE`；PG `Query` / `Parse` / `Bind` / `Execute` 等 |
| 协议状态机 + 置信度 | **已实现** | 连接→协商→认证→就绪→执行→流式→关闭；`CONFIRMED` / `UNCERTAIN` / `SUSPENDED` |
| 大包重组 / 流式识别 | **已实现** | MySQL 大包；`LOAD DATA LOCAL` / PG `COPY` 期间不解析命令 |
| PG Cancel 关联 | **已实现（仅关联）** | `CancelRequest` 与 `BackendKeyData` 键索引；**不代发** cancel |
| 网关自身错误 → 协议原生包 | **已实现** | 如目标不可达：MySQL `1042/08S01`，PG `08006`；目标错误原样透传 |
| 结果集脱敏 | **已接线** | 无 `MaskingRule` 时路径关闭、逐字节透明；有规则时 fail-closed 改写 |
| 审计 spool / JDBC 搬运 | **已实现，默认关** | fail-closed；见下文；**专用验收测试仍缺**（STATUS P0-3） |
| 连接上限 / CIDR / idle | **已实现** | `max-connections`、`allowed-client-cidrs`、`idle-timeout-seconds` |
| 后端 failover 列表 | **已实现** | `backend-endpoints` 顺序尝试；非智能路由 |
| 风控策略 | **接口就绪，默认全放行** | `DatabaseRiskPolicy.allowAll()`；无内置规则配置 |
| TLS / 压缩可解析 | **未做终止** | 接受后变 opaque tunnel；可选 `require-cleartext-inspection` 拒绝 |
| NIO 事件驱动 | **未实现** | 阻塞 socket + 每连接线程（可选虚拟线程，见构建状态） |
| 连接池化 | **未实现** | `SessionSnapshot` / 脏度已预留 |

> 网关是透明代理：不伪造握手能力，不对客户端虚报未实现能力。  
> 主流代理在数据平面上也不靠「跨线程共享可变协议状态」保证正确——本仓库同样让
> 每条连接的协议状态在任一时刻只属于一个执行体（单连接监视器）。

### 结果集脱敏边界（摘要）

- **一列一规则**；未命中列保持原值；元数据不全或无法表示改写值 → **拒绝结果集**。
- MySQL：文本行 + 预处理二进制行（长度前缀类型与常见整数/浮点）；`decimal`/时间/`bit`/`geometry`/未知类型的非空改写拒绝。
- PostgreSQL：文本格式 + 可复现二进制（如 `text`/`bytea`/`jsonb` 等）；定长数值/时间/`uuid`/未知 OID 的非空改写拒绝；置 NULL 对任意类型可用。
- `NullingRule` **只匹配可空列**：MySQL 字面量列常为 `NOT NULL`，置 NULL 规则不会生效——需固定值/哈希/加密等。

细节与类型清单以代码及历史 README 说明为准；排期缺口见 STATUS。

## 环境要求

- JDK 17+（编译目标 17；JDK 21+ 运行时可选用虚拟线程，见构建状态）
- Maven 3.6+
- 本地或远端 MySQL / PostgreSQL
- 可选：`mysql` / `psql` 客户端做手工验证

## 配置说明

通用默认：`src/main/resources/application.yml`  
本地开发（已 gitignore）：`src/main/resources/application-dev.yml`  
模板：`application-mysql-template.yml` / `application-postgresql-template.yml`

**以 `GatewayConfig` 的 `@Value` 为准**（模板使用嵌套 `gateway.target.*`）。  
若直接改默认 `application.yml`，请使用下列键，勿混用未绑定的扁平别名：

| 键 | 含义 |
|---|---|
| `server.port` | Spring HTTP（Web / Actuator） |
| `gateway.proxy-port` | 数据库协议代理端口（客户端连这里） |
| `gateway.proxy-db-type` | `mysql` \| `postgresql` |
| `gateway.target.host` / `port` / `username` / `password` / `database` | 主后端 |
| `gateway.backend-endpoints` | 可选 `host:port,host:port` failover |
| `gateway.max-connections` | 并发连接上限（默认 200） |
| `gateway.idle-timeout-seconds` | 客户端 `SoTimeout`；`0` 关闭 |
| `gateway.allowed-client-cidrs` | 可选 CIDR 白名单 |
| `gateway.virtual-threads` | 是否尝试虚拟线程执行器 |
| `gateway.require-cleartext-inspection` | 拒绝 TLS opaque（未设时随审计开关） |
| `gateway.rewrite.max-message-bytes` / `max-hold-millis` | 改写持有上界 |
| `gateway.audit.*` | 见 [审计与脱敏](#审计与脱敏) |

更多键与漂移说明：[`docs/STATUS_AND_GAPS.md`](docs/STATUS_AND_GAPS.md) §3。

## MySQL 网关

```bash
cp src/main/resources/application-mysql-template.yml src/main/resources/application-dev.yml
# 编辑 application-dev.yml 中的 target 与端口
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

模板要点：

```yaml
gateway:
  proxy-db-type: mysql
  proxy-port: 33307
  target:
    host: localhost
    port: 13308
    username: root
    password: change-me
    database: mysql
```

客户端：

```bash
mysql --protocol=tcp -h 127.0.0.1 -P 33307 -uroot -p
```

```text
mysql client -> gateway:33307 -> target mysql
```

![MySQL gateway flow](assets/mysql-gateway-flow.gif)

## PostgreSQL 网关

```bash
cp src/main/resources/application-postgresql-template.yml src/main/resources/application-dev.yml
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

```yaml
gateway:
  proxy-db-type: postgresql
  proxy-port: 35433
  target:
    host: localhost
    port: 5432
    username: postgres
    password: change-me
    database: postgres
```

```bash
psql -h 127.0.0.1 -p 35433 -U postgres -d postgres
```

```text
psql client -> gateway:35433 -> target postgresql
```

![PostgreSQL gateway flow](assets/postgresql-gateway-flow.gif)

## 测试

```bash
mvn test                          # 默认：非集成单元测试
mvn -Pintegration-test test       # 真库集成（需本地配置）
```

集成连接信息：`src/test/resources/integration-test-local.properties`（已忽略，勿提交密码）。

集成测试覆盖转发与结果集脱敏（MySQL 文本/二进制、PostgreSQL 置 NULL/固定值，以及无规则时透明对照）。  
审计子系统的专用验收测试尚未合入本分支，勿假定 README 旧文中的类名仍存在——以 `src/test` 为准（STATUS P0-3）。

提交前：`mvn clean test` 必须在本机 JDK 上全绿。

## 审计与脱敏

启用后链路：`观测事件 → MaskingTrafficObserver（可选） → SpoolingTrafficObserver → AuditSpool`。

| 原则 | 行为 |
|---|---|
| 默认关闭 | 不建文件、不因审计拒流量 |
| 开启后 fail-closed | sink 失败 → 协议原生错误拒绝操作（观测指标仍可 fail-open） |
| 先脱敏后落盘 | `mask-statements` 默认 true |
| 分级耐久 | `grade-writes`：可证明只读走窗口档，写/DDL/不可解析走严格 |

存储保证（规则 §8.5）：append-only + CRC32、group commit、`STRICT`/`WINDOW`、有界队列与磁盘配额、崩溃截断半条、I/O 失败熔断。

### `gateway.audit.*` 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `false` | 是否启用 |
| `spool-dir` / `file-name` | `./audit` / `audit.spool` | 生产建议独立磁盘 |
| `durability` | `strict` | `strict` \| `window` |
| `batch-size` / `max-batch-delay-millis` | `32` / `1` | group commit |
| `max-pending-records` / `max-enqueue-wait-millis` | `4096` / `10` | 队列上界 |
| `max-spool-bytes` | `1073741824` | 磁盘配额 |
| `segment-bytes` | `67108864` | 段轮转 |
| `grade-writes` | `true` | 按语句分级 |
| `mask-statements` | `true` | 落盘前脱敏语句 |
| `destination` | `spool` | `spool` \| `jdbc` |
| `ship-interval-millis` / `ship-batch-size` | `1000` / `500` | JDBC 搬运 |
| `jdbc.url` / `username` / `password` / `table` | 空 / `gateway_audit_record` | 须独立于被代理库；主键见 `docs/sql/audit-sink-schema.sql` |

### 运维要点（摘要）

- 监控 spool 熔断（I/O 失败后拒绝写入）。
- 严格档延迟≈一次 fsync；spool 与目标库 WAL 分盘。
- 配额触顶即拒绝；`destination=jdbc` 时按目的端最长停机定容量。
- 只归档/删除检查点之前的段；活动段勿动。
- 优雅停机刷完已接收记录；`audit/`、`*.spool` 已在 `.gitignore`。

`destination=jdbc`：至少一次投递 + 目的端主键去重收敛为恰好一次；检查点仅在整批成功后推进。建表脚本：`docs/sql/audit-sink-schema.sql`。

### 脱敏开关

| 层 | 默认 | 如何关闭 |
|---|---|---|
| 审计语句脱敏 | 开（若启用审计） | `gateway.audit.mask-statements=false` |
| 结果集脱敏 | 关（无规则即不启用） | 不注册 `MaskingRule` bean |
| 转发字节 | 从不因观测改写 | — |

结果集脱敏与 `mask-statements` 应使用同一套策略，避免审计留下规则想隐藏的原文（规则 §8.2）。

## 文档索引

完整导航见 [`docs/README.md`](docs/README.md)。

| 文档 | 内容 |
|---|---|
| [`AGENTS.md`](AGENTS.md) | 分层、透明性不变量、安全与工作约定 |
| [`docs/STATUS_AND_GAPS.md`](docs/STATUS_AND_GAPS.md) | **本分支现状 / P0–P2 缺口** |
| [`docs/PROTOCOL_REFERENCE_TABLES.md`](docs/PROTOCOL_REFERENCE_TABLES.md) | 协议表镜像（真源为代码枚举） |
| [`docs/rules/database-protocol-rules.md`](docs/rules/database-protocol-rules.md) | 协议规则（应当怎样） |
| [`docs/rules/ai-error-handling-rules.md`](docs/rules/ai-error-handling-rules.md) | 失败处理流程 |

## 开发约定

开发规矩以 `AGENTS.md` 与 `docs/rules/` 为准，本文件不重复维护细则。  
协议改动完成前：`mvn clean test` 全绿；参考表变更先改枚举再改镜像文档。
