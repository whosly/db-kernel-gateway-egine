# 数据库协议规则

本文档定义数据库网关在实现数据库 wire protocol 时必须遵守的规则。
它描述的是数据库协议层规则，不是当前代码已经实现了什么。

当前第一阶段目标是实现 MySQL Client/Server Protocol 和 PostgreSQL
Frontend/Backend Protocol 的完整透明代理。认证、TLS/SSL/GSS、压缩、
prepared statement、binary protocol、Parse/Bind/Execute、COPY/LOAD DATA 等真实
数据库语义由目标数据库完成；网关负责连接、透明转发、明文阶段协议观测、opaque
tunnel、生命周期管理、审计元数据和自身错误映射。后续 Oracle、SQL Server 等协议
必须按同一套协议抽象扩展，不能把某一种数据库协议当成通用协议。

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

版本基线：

- MySQL 以当前官方稳定 MySQL Client/Server Protocol 文档为最高基线；本设计自检时
  官方 dev 文档显示 MySQL 9.7.1。
- PostgreSQL 以当前稳定 PostgreSQL Frontend/Backend Protocol 为最高基线；本设计
  自检时稳定文档为 PostgreSQL 18，协议最高版本为 3.2，并必须兼容 3.0。

规则优先级：

1. 官方协议文档。
2. 本规则文档。
3. 项目设计文档。
4. 当前代码实现。

如果当前代码与协议文档冲突，按协议文档修正。

## 1.1 当前规约自检结论

本规约经过一次开发前自检，结论如下：

- 总体方向正确：规约以数据库 wire protocol 为对象，而不是以当前实现为对象；
  MySQL 与 PostgreSQL 的连接阶段、帧格式、认证、查询周期、结果集、错误响应
  均覆盖到了第一阶段开发需要的关键面。
- 需要持续校准：数据库协议细节以官方文档为准；规约中的字段清单和流程规则
  是工程约束，不替代官方规范。
- 不允许过度广告能力：网关在握手或启动阶段声明的 capability、参数、认证方式、
  result format、TLS/压缩支持，必须与实际实现一致。尚未实现的能力只能预留
  接口，不能对客户端声称已支持。
- 第一阶段允许保守实现：不支持的子协议必须被识别并返回协议原生错误或明确
  关闭连接，不能误解析、静默忽略或伪装为成功。

后续必须继续细化的规则缺口：

- 表类资产单源：MySQL capability flag、MySQL status flag、MySQL command 码、
  MySQL error code / SQLSTATE 映射、PostgreSQL frontend/backend message 类型、
  PostgreSQL type OID 的唯一真源是 `com.whosly.gateway.adapter.*` 下的 Java 枚举。
  `docs/PROTOCOL_REFERENCE_TABLES.md` 只是这些枚举的**镜像视图**，用于评审与对照，
  不得反向成为真源，也不得声称某张表已定义而实现中不存在。
- MySQL error code / SQLSTATE 映射表：网关自身错误的首批映射由
  `GatewayErrorMapping` 定义；目标数据库错误必须原样透传，不得改写。
- PostgreSQL type OID 映射表：首批审计/测试用途类型集合由 `PostgreSQLTypeOid`
  定义；只用于审计与测试，不得用它改写目标库 RowDescription。
- PostgreSQL Extended Query 错误恢复：透明代理模式下目标库负责 Parse/Bind/Execute
  错误语义；网关只记录可见消息并保持转发。
- 集成测试策略：后续应明确哪些测试使用真实 `mysql`、`psql`、JDBC 客户端，哪些
  保持纯单元测试。

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

透明代理模式下，TLS、压缩、GSSAPI 和认证插件由目标数据库决定。网关必须识别
协商切换点并进入 opaque tunnel，不得合成比目标数据库更多的能力，也不得替目标
数据库降级或伪造认证结果。

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
- 转发方向。
- 可观测元数据。
- 目标数据库成功响应。
- 目标数据库失败响应。
- 是否保持连接。
- 命令结束标记。
- 下一个 `READY` 或关闭状态。

查询周期不能只以 SQL 字符串为中心。数据库客户端会发送非 SQL 命令、元数据
命令、prepared statement 命令、cancel、sync、flush、copy/load 等协议消息。

### 2.6 结果集协议规则

透明代理模式下，结果集必须由目标数据库生成并由网关原样转发。网关不得用 JDBC 或
内部对象重新序列化结果集。

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

### 2.10 观测路径规则

透明代理的数据路径只有一条：字节原样双向转发。观测是挂在数据路径旁的只读旁路，
必须满足以下不变量：

- **观测不得影响转发**：观测不得修改、延迟、重排或阻塞客户端与目标数据库之间的
  任何字节。
- **观测必须可失败**：观测、审计、状态推断的异常一律 fail-open，只降级观测结果，
  不得让客户端连接失败。
- **改写必须 fail-closed**：SQL 改写与结果集脱敏是唯一允许改变字节的操作；改写
  失败必须拒绝并返回协议原生错误，不得部分改写，也不得原样放行。
- **未改写即零序列化**：未被改写的消息必须直接写出其原始字节，不得为了统一而重新
  序列化。
- **观测状态必须带置信度**：至少区分 `CONFIRMED`（与协议完全一致）、`UNCERTAIN`
  （遇到未建模形态，保留上次已确认值且不再更新）、`SUSPENDED`（相位机无法自愈，
  停止写入会话状态）。未确认状态不得用于路由、连接复用或安全决策。
- **观测必须能再同步**：解析一旦错位，必须复位而不是继续推断。再同步点定义为：
  - MySQL：客户端命令包 `sequence_id == 0`（见 3.2），每个新命令都是确定的边界；
  - PostgreSQL：后端 `ReadyForQuery`，它是查询周期的语义终点。
- **未知即暂停或降级**：未识别的命令与未识别的响应形态必须进入 `SUSPENDED`，禁止继续
  猜测其后续含义。当帧格式本身自描述时（例如 PostgreSQL 的 `type + length`），未建模的
  消息不会破坏后续解析，只需降级为 `UNCERTAIN` 并继续观测；能否降级取决于该协议能否在
  字节流中确定消息边界，不能确定边界的必须暂停。
- **opaque tunnel 之后不再解析**：一旦协商进入 TLS/GSS/压缩等不透明链路，必须停止
  所有明文解析，只保留字节转发。
- **状态所有权唯一**：一条连接的协议状态（相位机、分帧缓冲、以及由它派生的会话镜像）
  在任一时刻只允许由一个执行体驱动。两个方向共享同一份状态时，必须由**每连接**的
  监视器串行化；**禁止全局锁**，也禁止无保护地跨线程共享可变状态。
- **临界区只放状态**：串行化必须只覆盖协议状态本身。审计投递、风控评估等可能阻塞的
  下游操作必须在临界区之外执行，否则一个方向的慢下游会拖住另一个方向（违反
  "观测不得延迟转发"）。
- **读方走快照**：会话状态必须通过**在同一监视器下构建的不可变快照**对外发布。禁止
  无保护地直接读字段：否则消费方可能读到"从未存在过的中间组合"，并据此做路由或池化
  决策（见 8.3）。
- **并发下不丢**：同方向必须保序；不得丢失、重复或改写事件的归属。审计投递策略
  （阻塞 / 丢弃并计数 / 本地落盘）必须显式配置，禁止无界队列，也禁止静默丢弃。
- **会话必须输出可消费快照**：每个协议会话都要能给出统一的快照视图，至少包含协议名、
  连接状态、观测置信度、是否处于事务、客户端身份与默认库、会话脏度、连接时间与最近活动
  时间，并自带"观测是否可信"与"可否无重置复用"的判定。审计、风控、路由和连接池化只消费
  该快照，不得直接读取协议内部字段。

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
- 客户端 handshake response、SSLRequest、command packet 都必须分别校验
  sequence id 和 payload 长度，不能只检查首字节命令号。

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

- 如果客户端设置 SSL 能力并发送 SSLRequest，网关必须将 SSLRequest 透传给目标库，
  并在目标库接受 TLS 后进入 TLS opaque tunnel。
- 不能把 SSLRequest 当作普通 Handshake Response 继续观测解析。
- 如果目标数据库没有在 Initial Handshake 中声明 `CLIENT_SSL`，客户端仍发送
  SSLRequest，网关应记录为异常协商；默认仍透传给目标数据库处理，除非安全策略明确
  要求关闭。

### 3.5 MySQL 认证规则

认证由目标数据库完成，网关必须透明转发：

- `mysql_native_password` 兼容路径。
- `caching_sha2_password` 预留。
- auth switch request。
- auth more data。
- auth success。
- auth failure。

注意：新版本 MySQL 服务端已经调整 `mysql_native_password` 支持状态，实现时不
能把它写死为唯一长期方案。

服务端握手包中的 auth plugin name 来自目标数据库。网关不得修改 auth plugin name，
不得伪造 `mysql_native_password` 或 `caching_sha2_password` 支持状态。

### 3.6 MySQL Command Phase 规则

首期必须定义以下命令规则：

- `COM_QUERY`: 文本 SQL 查询。
- `COM_INIT_DB`: 切换默认数据库。
- `COM_QUIT`: 正常关闭连接。
- `COM_PING`: 返回 OK。
- `COM_FIELD_LIST`: 识别并透传。
- `COM_CHANGE_USER`: 识别并透传，由目标数据库重新认证。
- prepared statement commands: 必须识别并透传，包括 prepare、execute、send long
  data、close、reset、fetch。

每个 command handler 必须声明：

- 是否需要后端连接。
- 是否允许在 failed transaction 中执行。
- 成功响应类型。
- 错误响应类型。
- 是否修改 session state。

每个 command handler 还必须声明**是否有响应**与**响应形态**；观测相位机按声明选择
分支，禁止用 payload 首字节猜测：

- `expects_response`：该命令是否会产生响应包。
- `response_shape` 取值：
  - `OK`：单个 OK_Packet。
  - `ERR`：单个 ERR_Packet。
  - `RESULTSET`：列数 → 列定义 →（EOF）→ 行 → 终止包。
  - `EOF_ONLY`：单个 EOF_Packet（或 `CLIENT_DEPRECATE_EOF` 下的 OK-as-EOF）。
  - `COLUMN_LIST`：列定义列表 + EOF，**没有列数包**。
  - `PREPARE`：prepare-ok 头（含 statement id 与参数/列数），其后是声明数量的参数定义
    与列定义，各自以 EOF 结束；`CLIENT_DEPRECATE_EOF` 时不发送这些 EOF。
  - `RAW_STRING`：无标记字符串包。
  - `STREAM`：流式响应，直到连接关闭或进入下一阶段。
  - `NO_RESPONSE`：服务端不回任何包。
  - `UNKNOWN`：未识别，必须进入 `SUSPENDED`（见 2.10）。

必须显式建模以下形态，否则观测会错位：

- `COM_FIELD_LIST`：`COLUMN_LIST`。响应**没有列数包**，首个 ColumnDefinition 的
  payload 首字节是字段名长度，不得被当作列数。
- `COM_STATISTICS`：`RAW_STRING`。响应是无标记字符串包，不是 OK/ERR/结果集。
- `COM_SET_OPTION`：`EOF_ONLY`。
- `COM_STMT_SEND_LONG_DATA`、`COM_STMT_CLOSE`：`NO_RESPONSE`。不得当作会产生响应的
  命令，也不得让会话状态滞留在执行中。
- `COM_STMT_PREPARE`：`PREPARE`。响应首包是 prepare-ok，**不是列数包**；参数与列定义的
  数量必须取自该头部，否则元数据定义会被误读为结果集头。
- prepared statement 的 prepare/execute/send long data/close/reset/fetch 必须按本节
  声明响应形态后再观测，不得使用统一猜测分支。

`COM_QUIT` 是终止命令：必须进入 `CLOSING`，且不得等待响应。

### 3.7 MySQL COM_QUERY 响应规则

`COM_QUERY` 响应只能是以下协议形态之一：

- ERR_Packet。
- OK_Packet。
- LOCAL INFILE Request，若支持。
- Text Resultset。

在命令阶段之外不能发送普通 OK_Packet 伪装成功。握手阶段、认证阶段、能力协商
阶段的错误必须使用对应阶段允许的协议响应；如果协议不允许继续，则发送错误后
关闭连接。

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
- 参数区必须是 key/value 的 null-terminated 字符串序列，并以额外的 zero byte
  结束。缺失终止符或奇数 key/value 项必须视为 malformed startup。

SSLRequest 规则：

- request code `80877103` 必须识别。
- 网关必须把 SSLRequest 转发给目标数据库，并把目标数据库返回的单字节响应透传给
  客户端。
- 目标数据库返回 `S` 后，网关进入 TLS opaque tunnel；返回 `N` 后继续观测明文
  StartupMessage。

GSSENCRequest 和 CancelRequest 必须识别并透传给目标数据库，不能误解析为
StartupMessage。

SSLRequest 和 GSSENCRequest 的响应只能是 PostgreSQL 协议要求的单字节响应
（例如 `N` 或 `S`），不能发送普通 typed ErrorResponse。CancelRequest 不会进入
普通会话认证流程，处理后必须结束该特殊请求连接。

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

网关不得采用 AuthenticationOk 的简化路径。所有 Authentication*、PasswordMessage、
SASLInitialResponse 和 SASLResponse 必须由目标数据库和客户端真实交换。

### 4.5 PostgreSQL 启动完成规则

认证成功后，目标数据库必须按协议发送启动完成消息，网关原样转发：

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

ParameterStatus 的值来自目标数据库，网关不得固定返回或改写。

ReadyForQuery 必须携带事务状态：

- `I`: idle。
- `T`: in transaction。
- `E`: failed transaction。

### 4.6 PostgreSQL Simple Query 规则

Simple Query 周期由 frontend `Query` message 触发。透明代理模式下，网关只观测
message 类型和长度，并原样转发请求与响应。

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

多语句语义由目标数据库处理，网关不得自行拆分或拒绝。

### 4.7 PostgreSQL Extended Query 规则

Extended Query 必须建模这些消息：

- Parse。
- Bind。
- Describe。
- Execute。
- Close。
- Flush。
- Sync。

透明代理模式下，网关必须透传完整 Extended Query 消息流，并可观测以下合法序列：

- Parse 成功返回 ParseComplete。
- Bind 成功返回 BindComplete。
- Describe 返回 RowDescription、ParameterDescription 或 NoData。
- Execute 返回 DataRow/CommandComplete/ErrorResponse/PortalSuspended 之一的
  合法序列。
- Close 成功返回 CloseComplete。
- Flush 只刷新输出，不产生固定响应。
- Sync 是错误恢复和 ReadyForQuery 同步点。

Extended Query 错误规则：

- 任一扩展查询消息出错后，目标数据库发送 ErrorResponse。
- 之后目标数据库负责丢弃消息直到 Sync。
- 收到 Sync 后目标数据库发送 ReadyForQuery 并恢复正常处理。

prepared statement 和 portal 状态由目标数据库维护；网关只能记录可见元数据，不能
跨连接共享或复用。

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
- 二进制结果集的支持边界按「**能否为该类型产出合法的二进制值**」界定，禁止按"看起来像文本"
  推测：客户端会用自己请求的类型解析这些字节。**置 NULL 对任何类型都合法**（写协议自身的
  NULL 标记，不重新编码），因此置 NULL 必须对所有类型可用；非空改写只允许在二进制表示可复现
  的类型上（字符串家族与 `bytea` 即原始字节、`jsonb` 需版本前缀），其余类型必须拒绝。
- 引擎只决定「值应该是什么」，**能否在线上表示是协议层的职责**：因此值的格式（text/binary）
  不构成引擎拒绝的理由，只有类别不兼容与无法编码才拒绝。
- 二进制行的类型表**只能有一份实现**，同时供"算长度"（解析）与"写字节"（编码）使用：两者若
  对某个类型的宽度理解不一致，重组的行不会报错，而是把后面的值**悄悄挪到错误的列**。
- 二进制行解析后必须**恰好走完载荷**：只要有剩余字节或不足，就说明某类型的宽度假设不成立，
  必须**拒绝该结果集而不是继续解析**。这条自检是把"对协议布局的假设"变成可验证事实的唯一手段。
- 未知类型（未在类型表中）一律视为"无法解析"，禁止按"大概是 1 字节/长度前缀"之类推测补齐。

PostgreSQL 类型 OID 映射必须集中维护，不允许散落在查询 handler 中。

RowDescription 的 format code 必须与目标数据库后续 DataRow 编码一致。透明代理
模式下网关只观测 format code，不得把 binary format 改写为 text format，也不得替
目标数据库拒绝 Bind 中请求的 binary format。

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
- `ProtocolCommandObserver`: 观测协议命令或消息。
- `ProtocolRelay`: 双向转发协议流。
- `ProtocolErrorMapper`: 映射网关自身错误。
- `ProtocolCapabilities`: 描述已协商能力。

透明代理核心服务只管理连接、转发、状态和审计，不能依赖 MySQL、PostgreSQL 具体
结果集实现来重写数据库响应。

数据路径边界（规则 2.10 的落地位置）：

- `WireMessage` / `RawBackedMessage`：消息视图。未改写时直接暴露原始字节区间；改写必须
  通过 `withReplacement` 生成新消息，原视图保留给观测与审计。
- `MessagePipeline` / `MessageInterceptor` / `InterceptorPhase`：有序拦截链，顺序固定为
  观测 → 策略 → 改写 → 审计。改写必须在策略之后，否则改写可以绕过策略检查。
- `TrafficDecision`：携带转发动作与待写消息。未改写时写原始字节，只有改写过的消息才写
  替换载荷。
- `BackendProvider`：目标连接的唯一获取入口。池化实现必须先要求
  `SessionSnapshot.isReusableWithoutReset()` 并执行重置策略，无法证明可重置时必须销毁连接。

禁止事项：

- 禁止在未改写的情况下重新序列化消息。
- 禁止绕过 `BackendProvider` 直接建立目标连接。
- 禁止把改写拦截器注册在策略拦截器之前。

## 7. 测试规则

新增或修改协议行为必须先有测试。

必须覆盖：

- 帧长度和 endian 编码。
- 大包或分片。
- malformed frame。
- startup/handshake 正常路径。
- SSL/TLS/GSS opaque tunnel 切换路径。
- authentication passthrough success/failure。
- command/query 透明转发路径。
- result metadata 和 row encoding 观测，不改写。
- NULL 值。
- update count / command tag。
- SQLSTATE/error code 映射。
- transaction state。
- unsupported command。

单元测试不得依赖真实数据库。需要真实 MySQL、PostgreSQL 客户端和后端的测试
必须归类为集成测试。

## 8. 演进约束（连接池化 / SQL 改写 / 结果集脱敏）

第一阶段是完整透明代理。后续演进到连接池化复用、SQL 改写或结果集脱敏时，必须满足
以下约束。这些约束在演进开始前即生效，用于保证演进是增量而不是重写。

### 8.1 数据路径

- 未改写的消息一律写出原始字节，禁止为了统一而全量重新序列化。
- 只有被显式改写的消息才允许重新序列化，且序列化的消息类型必须逐个开启。
- 结果集必须流式处理，禁止把完整结果集 materialize 到内存后再输出。
- **有界持有是唯一允许的延迟例外**：默认情况下每一批读到的字节必须立即转发。只有当一条
  消息需要被改写、而它尚未接收完整时，才允许扣住这条消息的已有字节直到其完整。此时必须
  同时受两个上界约束：**单条消息最大字节数**与**最长持有时间**（默认 1 MiB / 1 秒，
  必须可配置）。超过任一上界必须**拒绝并返回协议原生错误**，不得原样放行——放行等于
  静默跳过改写，与 8.2 第 2 条冲突。
- 持有必须逐方向独立计量，且只覆盖"未完成的那一条消息"；已完成的消息不得滞留。
- **消息边界只有一份实现**：边界由协议层给出（见 2.10），数据路径必须向协议层索取，
  禁止自行分帧或维护第二套帧状态机。协议层无法给出可信边界时（opaque tunnel、非法
  长度），必须报告"无法分帧"并立即转发，不得猜测边界。
- 协议层给出的边界必须**随会话相位解析**（如 PostgreSQL 启动家族 → typed、SSL 协商回
  应、opaque 切换），不得在会话建立时固化；会话中途进入 opaque 后必须立刻停止持有。
- **一次 inspect 恰好一条消息**：启用持有后，数据路径必须按协议层给出的边界**逐条**交付，
  禁止把同一窗口内的多条消息合并成一次调用。需要整条消息的改写只能对一条消息做出判断，
  合并交付会让它在消息边界上解析出错误的值（进而改错数据）。
- **结果集元数据必须随命令周期失效**：列元数据只在同一命令周期内有效（MySQL 由列定义给出，
  PostgreSQL 由 `RowDescription` 给出并在 `ReadyForQuery` 后失效）。禁止跨结果集复用元数据，
  也禁止在元数据不完整时按"猜"的列序改写——把值归到错误的列比不改写更危险（8.2）。
- 改写所需的元数据由协议层产出，**缺失信息不得编造**：PostgreSQL 的 `RowDescription` 不携带
  可空性（可空性在系统目录里，不在线上），按"可脱敏"方向取值并写明；MySQL 的列定义携带
  `NOT_NULL` 标志，必须据此取值而不是假定可空。

### 8.2 开启改写的门槛

对每一种即将开启改写的消息类型，必须同时满足：

1. 存在 `serialize(parse(bytes)) == bytes` 的往返测试，字节级等价。
2. 对应改写器默认 fail-closed，失败时不产生任何部分改写输出。
3. 审计存储中的 SQL 与结果必须与脱敏策略一致；否则脱敏等于失效。脱敏作用于审计出口，
   策略判定必须基于原始语句：网关转发给目标库的字节不受脱敏影响。

### 8.3 连接池化

前提约束：客户端认证是面向**目标库握手盐值**的挑战-响应，盐值每条连接不同。因此
"认证透传"与"后端连接复用"不可兼得：复用已认证的后端连接要求网关自己认证客户端，
并以网关自身凭据认证后端，即 8.4 的认证终结。未完成认证终结前不得启用连接池化。

- 处于事务中（PostgreSQL `T` / `E`，或 MySQL `SERVER_STATUS_IN_TRANS`）的后端连接，
  绝不允许跨客户端复用。
- 归还连接必须经过明确的 reset 策略：发送 `COM_RESET_CONNECTION`（MySQL）或
  `DISCARD ALL`（PostgreSQL），否则必须销毁。
- 无法证明可安全重置的连接不得进入池中；无法判定即视为脏。
- 会话脏度必须保守：协议上不可见的会话状态（临时表、用户变量、advisory lock、
  会话级 `SET`）一律视为脏，宁可销毁。
- 只有置信度为 `CONFIRMED` 的会话状态才可用于池化与路由决策（见 2.10）。

### 8.5 结果集脱敏规则

默认不脱敏：规则注册表为空时原始值可见。脱敏只能由部署显式注册的规则触发。

- **规则即注入点**：实现 `MaskingRule` 并注册为 bean 即生效，不需要改动引擎或数据路径。
- **一个列只应用一条规则**。规则按优先级排序并取最高优先级；**同优先级多命中视为配置冲突**
  并 fail-closed，禁止任意挑选。
- **规则必须声明可产出的值类别**（`supportedCategories`）。引擎据此拒绝把不兼容的值写入
  该列，例如把 `****` 写进 `int4` 列会让客户端解析失败。
- **binary 格式暂不支持**：命中规则且列为 binary 时必须拒绝并上报，不得静默透传。
- **失败一律 fail-closed**：规则抛异常、返回空值、或与列的格式/类别不匹配时，都不得把
  原始值当作已脱敏转发。
- **禁止叠加应用**：不得对同一值按顺序套用多条规则（会复合变换并损坏数据）；需要组合
  语义时由单条规则内部实现。
- 与 8.2 的**审计脱敏职责分离**：本节规则改的是线上响应，8.2 的脱敏只改审计文本。

### 8.4 认证与能力

- 任何"网关自己终结认证"的演进，都必须取客户端与目标数据库能力的交集，只声明网关
  真正能兑现的能力（见 1.1）。
- 不得为了让池化或改写生效而伪造握手、能力或认证结果。

### 8.5 审计留痕约束

当审计数据要求"一条不能少"（合规留痕）时：

- **禁止静默丢弃**：不得因为下游慢或失败而无声地少记一条。丢弃只能作为显式、可计数、
  可告警的策略存在，且不得作为合规场景的默认。
- **禁止无界缓冲**：事件缓冲必须有全局上限，配额按连接分配，防止单个连接耗尽内存。
- **本地先持久化**：事件必须先落到本地 append-only 存储（fsync 后确认），再由独立消费者
  投递最终 sink。落盘失败（磁盘满 / IO 错误）必须 **fail-closed**：拒绝该操作并告警，
  不得放行。
- **至少一次 + 幂等**：崩溃恢复会重放未确认记录，因此每条记录必须携带幂等键
  （连接标识 + 该连接内单调序号），最终 sink 必须能按其去重。
- **顺序按连接保持**：分片键必须是连接标识，保证单连接内事件顺序不被并行投递打乱。
- **先脱敏后落盘**：审计存储的内容必须与脱敏策略一致（见 8.2）；不得先落原文再脱敏。
- **不得写回被代理的目标库**：审计 sink 不得是被代理的那一个数据库实例。
