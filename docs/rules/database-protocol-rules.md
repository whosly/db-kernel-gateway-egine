# 数据库协议规则

本文档定义数据库网关在实现数据库 wire protocol 时必须遵守的规则。
它描述的是数据库协议层规则，不是当前代码已经实现了什么。

当前第一阶段目标是 MySQL Client/Server Protocol 和 PostgreSQL
Frontend/Backend Protocol。后续 Oracle、SQL Server 等协议必须按同一套
协议抽象扩展，不能把某一种数据库协议当成通用协议。

## 1. 官方协议基线

实现协议前必须先对照官方协议文档，不允许只根据抓包或当前代码猜测协议。

- MySQL Client/Server Protocol:
  https://dev.mysql.com/doc/dev/mysql-server/latest/PAGE_PROTOCOL.html
- MySQL Connection Phase:
  https://dev.mysql.com/doc/dev/mysql-server/latest/page_protocol_connection_phase.html
- MySQL Packets:
  https://dev.mysql.com/doc/dev/mysql-server/latest/page_protocol_basic_packets.html
- PostgreSQL Frontend/Backend Protocol:
  https://www.postgresql.org/docs/current/protocol.html
- PostgreSQL Message Flow:
  https://www.postgresql.org/docs/current/protocol-flow.html
- PostgreSQL Message Formats:
  https://www.postgresql.org/docs/current/protocol-message-formats.html

规则优先级：

1. 官方协议文档。
2. 本规则文档。
3. 项目设计文档。
4. 当前代码实现。

如果当前代码与协议文档冲突，按协议文档修正。

## 2. 数据库协议通用规则

### 2.1 协议必须是状态机

每个数据库协议都必须显式建模连接状态，不能只按 socket 字节流顺序临时
判断。

通用状态至少包括：

- `CONNECTED`: TCP 连接已建立，协议尚未完成启动。
- `NEGOTIATING`: 版本、能力、TLS/GSS/SSL、压缩等能力协商中。
- `AUTHENTICATING`: 认证挑战、认证响应、认证切换中。
- `READY`: 客户端可以发送查询或命令。
- `EXECUTING`: 一个命令、查询或扩展查询周期正在执行。
- `STREAMING`: COPY、LOAD DATA、批量导入导出等子协议进行中。
- `CLOSING`: 收到终止命令或发生 fatal 错误，准备关闭。
- `CLOSED`: 连接已关闭。

协议处理器只能接受当前状态允许的消息。非法状态下收到的消息必须映射为
协议级错误或关闭连接，不能继续污染会话状态。

### 2.2 帧格式必须独立实现

每个协议都必须有自己的 frame/message/packet codec。

codec 必须负责：

- 读取完整帧。
- 校验长度字段。
- 校验消息类型或命令类型。
- 处理大包、分片或连续消息。
- 维护或校验序列号、消息顺序、同步点。
- 将 payload 暴露给上层前完成边界检查。

禁止事项：

- 禁止上层 handler 直接从 socket 中随意读字段。
- 禁止用字符串拼接构造二进制协议包。
- 禁止跳过长度、序列号、消息类型校验。
- 禁止把 MySQL packet 格式复用为 PostgreSQL message 格式，反之亦然。

### 2.3 启动和能力协商规则

数据库协议启动阶段必须独立建模，不得与 SQL 执行阶段混在一起。

启动阶段必须明确处理：

- 协议版本。
- 服务端版本展示值。
- 客户端能力或启动参数。
- TLS/SSL/GSSAPI/压缩请求。
- 字符集、编码、时区、日期格式等会话参数。
- 默认数据库或 schema。
- 认证方式选择。
- 不支持能力的显式响应。

如果网关暂不支持 TLS、压缩、GSSAPI 或某个认证插件，必须按目标协议给出
明确响应，不能静默降级。

### 2.4 认证规则

认证必须被建模为协议层流程，而不是简单读取用户名密码。

认证模块必须支持：

- 认证请求。
- 认证响应。
- 认证成功。
- 认证失败。
- 认证方式切换。
- 认证继续消息。
- 认证阶段 fatal 错误关闭连接。

认证数据规则：

- 禁止记录明文密码、认证响应、salt、token、scram proof。
- 日志中只能记录用户名、认证方式、是否成功、失败原因分类。
- 客户端认证身份与后端数据库连接身份必须在模型上区分。
- 即使第一阶段采用配置中的后端凭据，也必须保留未来 passthrough、
  password、MD5、SCRAM、plugin auth 的扩展点。

### 2.5 命令和查询周期规则

协议进入 `READY` 后才能处理命令或查询。

每个命令周期必须定义：

- 客户端请求消息。
- 后端执行请求。
- 成功响应。
- 失败响应。
- 是否保持连接。
- 命令结束标记。
- 下一个 `READY` 或关闭状态。

查询周期不能只以 SQL 字符串为中心。数据库客户端会发送非 SQL 命令、元数据
命令、prepared statement 命令、cancel、sync、flush、copy/load 等协议消息。

### 2.6 结果集协议规则

结果集必须按目标客户端协议返回，而不是按 JDBC 或内部对象直接序列化。

结果集协议至少包括：

- 字段数量。
- 字段名称。
- 字段类型。
- 字段长度或类型修饰符。
- 字段所属表信息，若协议支持且可获得。
- 文本或二进制格式标记。
- NULL 值编码。
- 行数据编码。
- 空结果集。
- update count。
- warning count 或 notice。
- command tag 或 affected rows。
- 结果结束消息。

数据类型映射必须集中维护。不能在 handler 中散落 `java.sql.Types` 到协议类型
的临时判断。

### 2.7 事务状态规则

协议层必须维护客户端可见的事务状态。

至少需要表达：

- idle / autocommit。
- transaction open。
- failed transaction。
- implicit transaction。
- explicit transaction。
- commit / rollback 后状态。

协议要求返回事务状态时，必须返回真实状态或可解释的保守状态，不能固定写死。

### 2.8 错误和 Notice 规则

错误必须映射为协议原生错误消息。

错误映射至少包括：

- 协议错误。
- 认证错误。
- 权限错误。
- SQL 语法错误。
- 后端连接错误。
- 后端执行错误。
- 不支持的协议能力。
- 不支持的命令或子协议。

错误响应必须尽量保留：

- SQLSTATE。
- vendor error code。
- severity。
- human-readable message。
- fatal/non-fatal 分类。

禁止向客户端返回：

- Java stack trace。
- 本地文件路径。
- 明文密码。
- 带凭据的 JDBC URL。
- 内部类名作为错误主体。

### 2.9 取消、关闭和异常断开规则

协议实现必须区分：

- 客户端正常关闭。
- 协议终止命令。
- socket 异常断开。
- 后端连接断开。
- cancel request。
- 服务端主动 fatal 关闭。

关闭路径必须释放：

- 客户端 socket。
- 后端 connection。
- prepared statement / portal / cursor。
- session-local buffer。
- streaming state。

## 3. MySQL 协议规则

### 3.1 协议阶段

MySQL 协议必须按阶段实现：

1. TCP connected。
2. Initial Handshake。
3. Handshake Response 或 SSLRequest。
4. Authentication exchange。
5. OK_Packet 或 ERR_Packet。
6. Command Phase。
7. COM_QUIT 或 fatal close。

Connection Phase 不得直接进入查询执行。只有认证成功并发送 OK 后，才能进入
Command Phase。

### 3.2 MySQL Packet 规则

MySQL packet header 为：

- `payload_length`: 3 字节 little-endian。
- `sequence_id`: 1 字节。
- `payload`: `payload_length` 字节。

规则：

- 单包 payload 最大值按 `2^24 - 1` 处理。
- 大于等于 `2^24 - 1` 的 payload 必须按 MySQL 分片规则处理。
- 每个新 command 的客户端 packet sequence id 从 0 开始。
- 服务端响应 sequence id 必须按协议递增并允许 wrap。
- 读取 payload 前必须先验证长度。
- 对短包、截断包、错误 sequence 的处理必须明确。

### 3.3 MySQL 基础数据类型规则

MySQL codec 必须提供专用读写方法：

- fixed-length integer: `int<1>`, `int<2>`, `int<3>`, `int<4>`, `int<6>`,
  `int<8>`，little-endian。
- length-encoded integer。
- fixed-length string。
- null-terminated string。
- length-encoded string。
- rest-of-packet string。
- EOF-sensitive length-encoded integer 判断。

业务 handler 不允许自己手写这些编码细节。

### 3.4 MySQL Handshake 规则

Initial Handshake 必须包含或预留：

- protocol version。
- server version。
- connection id。
- auth-plugin-data-part-1。
- capability flags lower 2 bytes。
- character set。
- status flags。
- capability flags upper 2 bytes。
- auth plugin data length。
- auth-plugin-data-part-2。
- auth plugin name。

Handshake Response 必须解析：

- client capability flags。
- max packet size。
- character set。
- username。
- auth response。
- requested database，若 `CLIENT_CONNECT_WITH_DB` 开启。
- auth plugin name，若 `CLIENT_PLUGIN_AUTH` 开启。
- connection attributes，若客户端发送。

SSLRequest 规则：

- 如果客户端设置 SSL 能力并发送 SSLRequest，而网关未实现 TLS，必须返回明确
  错误并关闭或拒绝连接。
- 不能把 SSLRequest 当作普通 Handshake Response 继续解析。

### 3.5 MySQL 认证规则

第一阶段可以只实现有限认证路径，但协议模型必须支持：

- `mysql_native_password` 兼容路径。
- `caching_sha2_password` 预留。
- auth switch request。
- auth more data。
- auth success。
- auth failure。

注意：新版本 MySQL 服务端已经调整 `mysql_native_password` 支持状态，实现时不
能把它写死为唯一长期方案。

### 3.6 MySQL Command Phase 规则

首期必须定义以下命令规则：

- `COM_QUERY`: 文本 SQL 查询。
- `COM_INIT_DB`: 切换默认数据库。
- `COM_QUIT`: 正常关闭连接。
- `COM_PING`: 返回 OK。
- `COM_FIELD_LIST`: 若不支持，返回协议错误或兼容响应。
- `COM_CHANGE_USER`: 若不支持，返回明确错误；支持时必须重新进入认证流程。
- prepared statement commands: 第一阶段可不支持，但必须识别并返回明确错误。

每个 command handler 必须声明：

- 是否需要后端连接。
- 是否允许在 failed transaction 中执行。
- 成功响应类型。
- 错误响应类型。
- 是否修改 session state。

### 3.7 MySQL COM_QUERY 响应规则

`COM_QUERY` 响应只能是以下协议形态之一：

- ERR_Packet。
- OK_Packet。
- LOCAL INFILE Request，若支持。
- Text Resultset。

Text Resultset 必须按顺序返回：

1. column count。
2. column definitions。
3. EOF_Packet 或 OK-as-EOF，取决于 `CLIENT_DEPRECATE_EOF`。
4. zero or more text rows。
5. EOF_Packet 或 OK-as-EOF。

非查询语句必须返回 OK_Packet，并正确表达：

- affected rows。
- last insert id。
- status flags。
- warning count。
- info。
- session state tracking，若协商支持。

### 3.8 MySQL 错误包规则

ERR_Packet 必须包含：

- header `0xFF`。
- error code。
- SQL state marker `#` 和 5 字节 SQLSTATE，若 `CLIENT_PROTOCOL_41` 开启。
- human-readable error message。

协议错误、认证错误、后端 SQL 错误必须映射到合适的 MySQL error code 和
SQLSTATE，不能全部使用同一个通用错误。

## 4. PostgreSQL 协议规则

### 4.1 协议阶段

PostgreSQL 协议必须按阶段实现：

1. TCP connected。
2. Optional SSLRequest 或 GSSENCRequest。
3. StartupMessage。
4. Authentication request/response cycle。
5. AuthenticationOk 或 ErrorResponse。
6. ParameterStatus / BackendKeyData / ReadyForQuery。
7. Query cycle 或 Extended Query cycle。
8. Terminate 或 fatal close。

没有发送 ReadyForQuery 前，不得接受普通查询周期。

### 4.2 PostgreSQL Message 规则

普通 PostgreSQL frontend/backend message 格式为：

- 1 字节 message type。
- 4 字节 big-endian length，包含 length 字段自身，不包含 type 字节。
- payload。

StartupMessage、SSLRequest、GSSENCRequest、CancelRequest 没有前置 message
type，直接以 4 字节 length 开始。

规则：

- 所有整数按 PostgreSQL 协议 big-endian 处理。
- length 小于 4 必须视为 malformed。
- payload 实际长度必须等于 length - 4。
- 未知消息类型必须按当前状态决定返回错误或关闭连接。
- Startup 类消息必须先检查 request code 或 protocol version。

### 4.3 PostgreSQL Startup 规则

StartupMessage 必须解析：

- protocol version。
- `user`，必需。
- `database`，可选，默认可按协议映射到 user。
- `options`。
- `replication`。
- `application_name`。
- `client_encoding`。
- 其他运行时参数。
- `_pq_.` 前缀的协议扩展参数。

SSLRequest 规则：

- request code `80877103` 必须识别。
- 未支持 TLS 时返回单字节 `N`，然后等待真正的 StartupMessage。
- 支持 TLS 时返回 `S` 并切换到 TLS 后继续读取 StartupMessage。

GSSENCRequest 和 CancelRequest 必须预留识别点。第一阶段不支持时要显式拒绝或
关闭，不能误解析为 StartupMessage。

### 4.4 PostgreSQL 认证规则

认证流程必须支持多步模型：

- AuthenticationOk。
- AuthenticationCleartextPassword。
- AuthenticationMD5Password。
- AuthenticationSASL。
- AuthenticationSASLContinue。
- AuthenticationSASLFinal。
- PasswordMessage。
- SASLInitialResponse。
- SASLResponse。
- ErrorResponse。

第一阶段可以采用 AuthenticationOk 的简化路径，但认证模块接口必须能扩展到
MD5、SCRAM-SHA-256 和 passthrough。

### 4.5 PostgreSQL 启动完成规则

认证成功后，服务端必须按协议发送启动完成消息：

- ParameterStatus，发送客户端关心的 session 参数。
- BackendKeyData，用于未来 cancel request。
- ReadyForQuery，标记进入查询可用状态。

ParameterStatus 至少应覆盖：

- `server_version`。
- `server_encoding`。
- `client_encoding`。
- `DateStyle`。
- `TimeZone`。
- `integer_datetimes`。
- `standard_conforming_strings`。

ReadyForQuery 必须携带事务状态：

- `I`: idle。
- `T`: in transaction。
- `E`: failed transaction。

### 4.6 PostgreSQL Simple Query 规则

Simple Query 周期由 frontend `Query` message 触发。

响应规则：

- SELECT、FETCH、EXPLAIN、SHOW 等返回行的语句：
  1. RowDescription。
  2. zero or more DataRow。
  3. CommandComplete。
  4. ReadyForQuery。
- INSERT、UPDATE、DELETE、DDL 等不返回行的语句：
  1. CommandComplete。
  2. ReadyForQuery。
- 空查询：
  1. EmptyQueryResponse。
  2. ReadyForQuery。
- 错误：
  1. ErrorResponse。
  2. ReadyForQuery，除 fatal 错误外。

一个 Query message 可以包含多个 SQL statement。协议层必须明确多语句行为：

- 按 PostgreSQL 语义处理隐式事务。
- 遇到错误后中止后续 statement。
- 最后发送一次 ReadyForQuery。

第一阶段如果不完整支持多语句，必须记录限制并给出明确错误或保守执行策略。

### 4.7 PostgreSQL Extended Query 规则

Extended Query 必须建模这些消息：

- Parse。
- Bind。
- Describe。
- Execute。
- Close。
- Flush。
- Sync。

第一阶段可以做骨架实现，但必须遵守状态规则：

- Parse 成功返回 ParseComplete。
- Bind 成功返回 BindComplete。
- Describe 返回 RowDescription、ParameterDescription 或 NoData。
- Execute 返回 DataRow/CommandComplete/ErrorResponse/PortalSuspended 之一的
  合法序列。
- Close 成功返回 CloseComplete。
- Flush 只刷新输出，不产生固定响应。
- Sync 是错误恢复和 ReadyForQuery 同步点。

Extended Query 错误规则：

- 任一扩展查询消息出错后，必须发送 ErrorResponse。
- 之后丢弃消息直到 Sync。
- 收到 Sync 后发送 ReadyForQuery 并恢复正常处理。

prepared statement 和 portal 必须属于 session-local 状态，不能跨连接共享。

### 4.8 PostgreSQL RowDescription 和 DataRow 规则

RowDescription 每个字段必须包含：

- field name。
- table object id，未知则 0。
- column attribute number，未知则 0。
- type object id。
- type size。
- type modifier。
- format code，0=text，1=binary。

DataRow 必须按字段顺序编码：

- 字段数量。
- 每个字段长度。
- NULL 使用 `-1` 长度。
- 文本格式值必须按当前编码输出。
- 二进制格式第一阶段可不支持，但必须显式拒绝或降级策略清晰。

PostgreSQL 类型 OID 映射必须集中维护，不允许散落在查询 handler 中。

### 4.9 PostgreSQL ErrorResponse 和 NoticeResponse 规则

ErrorResponse/NoticeResponse 必须由字段组成，并以 zero byte 结束。

至少支持字段：

- `S`: severity。
- `V`: localized severity 或 non-localized severity，若需要。
- `C`: SQLSTATE code。
- `M`: primary message。
- `D`: detail，若安全。
- `H`: hint，若安全。
- `P`: position，若可获得。
- `W`: where，若可获得且不泄露内部实现。

Fatal startup/auth 错误发送 ErrorResponse 后关闭连接。普通查询错误发送
ErrorResponse 后按协议发送 ReadyForQuery。

### 4.10 PostgreSQL 取消和终止规则

必须预留：

- CancelRequest 识别。
- BackendKeyData 生成和 session 关联。
- Terminate 消息关闭连接。

第一阶段如果不支持 cancel，必须保证 CancelRequest 不会被误当作
StartupMessage 或普通查询消息。

## 5. 未来数据库协议规则

新增数据库协议前必须先定义该协议的：

- frame/message 格式。
- connection/startup 状态机。
- authentication 状态机。
- command/query 状态机。
- result metadata 格式。
- row data 格式。
- error/notice 格式。
- transaction state 表达。
- cancellation/termination 机制。
- unsupported feature 策略。

新增协议不得复用 MySQL 或 PostgreSQL 的包格式、错误包、状态机。
可以复用的是协议无关接口和内部执行模型。

## 6. 代码落地规则

协议代码落地时必须形成以下边界：

- `ProtocolFrameCodec`: 只处理帧读写和基础类型编码。
- `ProtocolStateMachine`: 管理连接状态和合法迁移。
- `ProtocolHandshake`: 管理启动、协商、认证。
- `ProtocolSession`: 保存每个客户端连接的状态。
- `ProtocolCommandHandler`: 处理协议命令。
- `ProtocolResultMapper`: 映射结果集。
- `ProtocolErrorMapper`: 映射错误。
- `ProtocolCapabilities`: 描述已协商能力。

核心服务只能接收协议无关的执行请求，不能依赖 MySQL、PostgreSQL 具体类。

## 7. 测试规则

新增或修改协议行为必须先有测试。

必须覆盖：

- 帧长度和 endian 编码。
- 大包或分片。
- malformed frame。
- startup/handshake 正常路径。
- SSL/TLS unsupported 路径。
- authentication success/failure。
- command/query 正常路径。
- result metadata 和 row encoding。
- NULL 值。
- update count / command tag。
- SQLSTATE/error code 映射。
- transaction state。
- unsupported command。

单元测试不得依赖真实数据库。需要真实 MySQL、PostgreSQL 客户端和后端的测试
必须归类为集成测试。
