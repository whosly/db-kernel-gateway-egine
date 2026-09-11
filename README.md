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
- 网关自身错误映射为协议原生错误：目标不可达时 MySQL 回 `ERR_Packet`（`1042` / `08S01`），
  PostgreSQL 回 `ErrorResponse`（`08006`）；目标数据库错误一律原样透传。
- 识别 `LOAD DATA LOCAL INFILE`（MySQL）与 `COPY`（PostgreSQL）的流式阶段，流式期间不解析命令。
- TLS、压缩、GSS 等不可明文解析的链路按 opaque tunnel 处理，优先保证转发正确性。
- 可通过 `ProtocolAdapter#getActiveSessions()` 查看当前连接观测到的协议状态，或通过
  `ProtocolAdapter#getActiveSessionSnapshots()` 获取可消费快照：协议名、连接状态、观测
  置信度、是否处于事务、客户端身份与默认库、会话脏度、连接与最近活动时间，并直接给出
  "观测是否可信"与"可否无重置复用"的判定（为后续连接池化预留）。

> 说明：网关是透明代理，不重写目标数据库的结果集与错误；也不对客户端虚报尚未实现的能力。
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

## 开发约定

开发规矩集中在 `AGENTS.md` 与 `docs/rules/`，本文件不重复维护：

- `AGENTS.md`：仓库级硬约束（分层、透明性不变量、单源规则、安全、工作约定）。
- `docs/rules/database-protocol-rules.md`：数据库协议规则。
- `docs/rules/ai-error-handling-rules.md`：失败处理流程。
- `docs/PROTOCOL_REFERENCE_TABLES.md`：协议参考表镜像（真源为代码枚举）。

提交前 `mvn clean test` 必须全绿。
