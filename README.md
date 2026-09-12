# 数据库内核网关引擎

一个基于 Java 17 和 Spring Boot 的数据库协议网关。客户端连接网关端口，网关把
MySQL 或 PostgreSQL wire protocol 流量透明转发到真实数据库，同时为后续 SQL 审计、
风控和观测能力预留协议层扩展点。

## 当前能力

- 支持 MySQL、PostgreSQL 透明代理转发；真实认证由目标数据库完成，网关不保存、不校验、
  不记录明文密码或认证载荷。
- 明文阶段可观测 SQL 流量：MySQL `COM_QUERY` / `COM_STMT_PREPARE`，PostgreSQL
  `Query` / `Parse` / `Bind` / `Execute`，用于审计与风控。
- 按连接维护协议状态机：连接、协商、认证、就绪、执行、流式、关闭。
  - MySQL：命令周期（`READY` → `EXECUTING` → `READY`）、事务状态（`OK`/`EOF` 状态位）、
    `affected rows`、`warning count`、当前库、错误 SQLSTATE、结果集列数与行数、客户端
    身份（含 `COM_CHANGE_USER`）、`COM_RESET_CONNECTION` 状态复位、大包（`>= 2^24-1`）分片重组。
  - PostgreSQL：事务状态（`ReadyForQuery`）、`ParameterStatus` 会话参数、`CommandComplete`
    命令标签、`RowDescription` 字段数与 `DataRow` 行数、`ErrorResponse` severity/SQLSTATE、
    `BackendKeyData`、扩展查询（`ParseComplete` / `BindComplete` / `CloseComplete` /
    `NoData` / `PortalSuspended` / `Sync` 同步点）、`NotificationResponse` 通道、COPY 数据计数。
- 识别 `CancelRequest` 并通过取消键索引与所属会话关联；网关只做关联，不代发 cancel。
- 观测按命令声明的响应形态分支（`OK` / `RESULTSET` / `COLUMN_LIST` / `PREPARE` / `EOF_ONLY`
  / `RAW_STRING` / `STREAM` / `NO_RESPONSE` / `UNKNOWN`），不再用首字节猜测；无法解释响应时
  暂停观测并在下一个客户端命令处再同步，会话状态带观测置信度
  （`CONFIRMED` / `UNCERTAIN` / `SUSPENDED`）。
- 审计出口可按需启用 SQL 字面量脱敏：`DatabaseTrafficObserver.masking(sink)` 在事件进入审计
  存储前把字符串与数值字面量替换为 `?`，**转发给数据库的字节完全不受影响**。
- 结果集脱敏按规则注入、**默认关闭（原始值可见）**：实现 `MaskingRule` 注册为 bean 即生效，
  内置置 NULL、固定值、部分保留、SHA-256 哈希与 AES-GCM 可逆加密；同优先级冲突、binary
  格式与不兼容类别一律 fail-closed。
- 需要整条消息的改写由**协议层给出消息边界**（MySQL 逻辑包、PostgreSQL typed/启动家族/
  SSL 回应），数据路径不重复实现分帧；会话进入 TLS/压缩后立即停止持有。
- 改写只在需要整条消息时才持有字节，且受两个**可配置**上界约束：
  `gateway.rewrite.max-message-bytes`（默认 1048576，即 1 MiB）与
  `gateway.rewrite.max-hold-millis`（默认 1000）。超过任一上界直接拒绝并返回协议原生错误，
  不做"原样放行"。
- 每连接的协议状态由**单一监视器**串行化：两个方向共享同一个观测器，临界区只覆盖协议
  状态，审计与风控在临界区之外执行，因此慢 sink 不会拖住另一方向；会话状态通过同一
  监视器下的不可变快照发布（`getActiveSessionSnapshots()`），供审计、风控与后续池化消费。
- 网关自身错误映射为协议原生错误：目标不可达时 MySQL 回 `ERR_Packet`（`1042` / `08S01`），
  PostgreSQL 回 `ErrorResponse`（`08006`）；目标数据库错误一律原样透传。
- 识别 `LOAD DATA LOCAL INFILE`（MySQL）与 `COPY`（PostgreSQL）的流式阶段，流式期间不解析命令。
- TLS、压缩、GSS 等不可明文解析的链路按 opaque tunnel 处理，优先保证转发正确性。
- 可通过 `ProtocolAdapter#getActiveSessions()` 查看当前连接观测到的协议状态，或通过
  `ProtocolAdapter#getActiveSessionSnapshots()` 获取可消费快照：协议名、连接状态、观测
  置信度、是否处于事务、客户端身份与默认库、会话脏度、连接与最近活动时间，并直接给出
  "观测是否可信"与"可否无重置复用"的判定（为后续连接池化预留）。

> 说明：网关是透明代理，不重写目标数据库的结果集与错误；也不对客户端虚报尚未实现的能力。
> 主流数据库代理（PgBouncer、MySQL Router、Vitess、Envoy 的协议过滤器）在数据平面上都不靠"跨线程共享可变状态 + 加锁"来保证正确，而是让每条连接的协议状态在任一时刻只属于一个执行体。
> 协议参考表（能力位、状态位、命令码、错误码映射、消息类型、类型 OID）见
> `docs/PROTOCOL_REFERENCE_TABLES.md`。

## 环境要求

- JDK 17+
- Maven 3.6+
- 本地或远端 MySQL / PostgreSQL
- 可选：`mysql` 或 `psql` 命令行客户端，用于手工验证代理链路

## 配置说明

通用默认配置在：

```text
src/main/resources/application.yml
```

本地开发配置建议放在：

```text
src/main/resources/application-dev.yml
```

`application-dev.yml` 已在 `.gitignore` 中忽略，适合放本机数据库地址和密码。

端口含义：

- `server.port`：Spring Boot HTTP 端口，用于 Web/Actuator 等入口。
- `gateway.proxy-port`：数据库协议代理端口，数据库客户端连接这个端口。
- `gateway.target.port`：真实后端数据库端口。

## MySQL 网关

复制 MySQL 配置模板：

```bash
cp src/main/resources/application-mysql-template.yml src/main/resources/application-dev.yml
```

按本地 MySQL 信息修改 `application-dev.yml`：

```yaml
server:
  port: 8080

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

启动服务：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

启动成功后会看到类似日志：

```text
Starting MySQL protocol adapter on port 33307
MySQL protocol adapter started successfully
```

另开一个终端，通过网关端口连接：

```bash
mysql --protocol=tcp -h 127.0.0.1 -P 33307 -uroot -p
```

进入 MySQL 后执行：

```sql
select 1;
```

能正常返回结果，说明链路已经打通：

```text
mysql client -> gateway:33307 -> target mysql:13308
```

效果示意：

![MySQL gateway flow](assets/mysql-gateway-flow.gif)

## PostgreSQL 网关

复制 PostgreSQL 配置模板：

```bash
cp src/main/resources/application-postgresql-template.yml src/main/resources/application-dev.yml
```

按本地 PostgreSQL 信息修改 `application-dev.yml`：

```yaml
server:
  port: 8080

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

启动服务：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

启动成功后会看到类似日志：

```text
Starting POSTGRESQL protocol adapter on port 35433
PostgreSQL protocol adapter started successfully
```

另开一个终端，通过网关端口连接：

```bash
psql -h 127.0.0.1 -p 35433 -U postgres -d postgres
```

进入 PostgreSQL 后执行：

```sql
select 1;
```

能正常返回结果，说明链路已经打通：

```text
psql client -> gateway:35433 -> target postgresql:5432
```

效果示意：

![PostgreSQL gateway flow](assets/postgresql-gateway-flow.gif)

## 测试

运行单元测试：

```bash
mvn test
```

运行真实数据库集成测试：

```bash
mvn -Pintegration-test test
```

集成测试的本地连接信息放在：

```text
src/test/resources/integration-test-local.properties
```

该文件同样已被忽略，不应提交真实密码。

## 审计留痕（可选，默认关闭）

启用后链路为：`观测事件 → MaskingTrafficObserver（先脱敏） → SpoolingTrafficObserver → AuditSpool`。

- **默认关闭**：不创建文件、不引入持久化依赖，也没有任何路径会因审计而拒绝流量。开启即表示
  接受「审计不可用则拒绝操作」这一语义。
- **必达语义（fail-closed）**：审计 sink 投递失败会拒绝该操作并返回协议原生错误，而不是放行
  一条没被记录的操作。对照：普通观测（指标、看板）仍然是 fail-open。
- **先脱敏后落盘**：语句在进入 spool 之前已按脱敏策略处理，审计存储不保留策略本应隐藏的值。
- **按语句分级**：开启 `grade-writes` 时，只有被证明「只读」的语句才走窗口档；写语句、DDL、
  无法解析的语句一律严格——分级只在能被证明的方向上放宽。

按规则 §8.5 实现的存储保证：

- **append-only + 校验和**：每条记录携带会话标识、会话内单调序号（幂等键）与 CRC32。
  长度前缀用于识别"崩溃留下的半条"，校验和用于识别"写坏了"——两者不会被混为一谈。
- **group commit**：单写者线程把队列中已积累的记录合成一批，一次 `fdatasync` 覆盖整批。
  吞吐由「批大小 × 刷盘速率」决定，而单条记录最多等一次刷盘。
- **严格 / 窗口两档**（`AuditDurability`）：`STRICT` 下 `append` 只在落盘后返回
  （审计先于执行）；`WINDOW` 下入队即返回，崩溃最多丢一个未刷盘批次。
- **有界 + 拒绝而非丢弃**：队列有上限（满则先等待、再拒绝），磁盘有配额（触顶即拒绝）。
  任何"记不下来"的情况都以 `AuditSpoolException` 上报，绝不静默丢弃。
- **崩溃恢复**：打开时截断不完整或损坏的尾部，文件始终是一段合法记录序列；关闭时先把
  已接收的记录刷完再退出。
- **I/O 失败即熔断**：一旦刷盘发生 I/O 错误，后续 `append` 全部拒绝直到人工介入——继续
  接受记录等于在审计链上留空洞。

配置项（`gateway.audit.*`，均可用 `GATEWAY_AUDIT_*` 环境变量覆盖）：

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `false` | 是否启用审计留痕 |
| `spool-dir` / `file-name` | `./audit` / `audit.spool` | spool 位置；生产建议放**独立磁盘**，避免与被代理库争 IO |
| `durability` | `strict` | `strict` \| `window`；非法值启动即失败 |
| `batch-size` / `max-batch-delay-millis` | `32` / `1` | group commit 的两个触发阈值 |
| `max-pending-records` / `max-enqueue-wait-millis` | `4096` / `10` | 队列上界与入队等待，超时即拒绝 |
| `max-spool-bytes` | `1073741824` | 磁盘配额，触顶即拒绝 |
| `grade-writes` | `true` | 按语句分级（写严格、只读走窗口） |

验收（`AuditTrailAcceptanceTest`）：并发写入下「每条语句**恰好一次**、按会话保序」；开启审计
**不改变**转发给数据库的字节（规则 2.10）；组提交把刷盘摊薄到整批，且单条 append 延迟被
「批窗口 + 一次刷盘」界定。

### 运维要点

- **监控 `AuditSpool.failure()`**：一旦刷盘发生 I/O 错误，spool 会熔断并拒绝后续记录（相应操作
  被拒绝）。这是必须告警的状态，恢复需要人工介入。
- **严格档的延迟 = 一次 fsync**，所以每条**写**语句要等一次刷盘。本仓库 CI 环境（虚拟化存储）
  实测：800 条记录 / 99 次刷盘，append `p50 ≈ 6 ms`、`p99 ≈ 19 ms`——瓶颈是该环境
  **fsync ≈ 5 ms**，不是分帧或队列。上线前请在目标硬件上复测，若 p99 不可接受，按此顺序处理：
  ① spool 放快盘（并独立于目标库）→ ② 提高并发写者/批窗口以增大批 → ③ 保持 `grade-writes`
  （默认已开，只读流量不付这个代价）→ ④ 最后才考虑整体改 `window` 档，并显式记录该窗口大小。
- **spool 放独立磁盘**：审计 fsync 与目标库自身 WAL 的 fsync 会争抢 IO，同盘部署会同时拉低两者。
- **配额与队列是硬边界**：触顶即拒绝（fail-closed）。长期触顶说明投递能力不足，应扩容或接入
  异步投递，而不是放宽上界——放宽只是把「拒绝」推迟成「内存耗尽」。
- **停机刷盘**：优雅停机先把已接收的记录刷完；非优雅停机在 `strict` 档不会丢已确认的语句。
- **审计目录不进版本控制**（`.gitignore` 已忽略 `audit/`、`*.spool`）：其中是真实业务语句。

> 尚未完成：最终 sink 投递。`spool → 数据库` 的搬运需要一个**可去重的目的端**（见运维要点里的
> 幂等要求），在确定目标表与独立连接配置之前不实现，避免先写出一段无法保证语义的搬运代码。

## 开发约定

开发规矩集中在 `AGENTS.md` 与 `docs/rules/`，本文件不重复维护：

- `AGENTS.md`：仓库级硬约束（分层、透明性不变量、单源规则、安全、工作约定）。
- `docs/rules/database-protocol-rules.md`：数据库协议规则。
- `docs/rules/ai-error-handling-rules.md`：失败处理流程。
- `docs/PROTOCOL_REFERENCE_TABLES.md`：协议参考表镜像（真源为代码枚举）。

提交前 `mvn clean test` 必须全绿。
