# 协议参考表（镜像视图）

> **本文件是镜像视图，不是真源。**
>
> 真源是 `src/main/java/com/whosly/gateway/adapter/**` 下的 Java 枚举。本文件只用于
> 评审与对照。两者不一致时以代码为准，并同步更新本文件。
>
> "状态"列表示当前实现状态：已实现 / 仅透传 / 规划中。规划中的条目允许出现，但
> **不得在实现中声称已支持**（见 `docs/rules/database-protocol-rules.md` §1.1）。

> 本分支「做到了什么 / 缺口」见 [`STATUS_AND_GAPS.md`](STATUS_AND_GAPS.md)；文档导航见 [`README.md`](README.md)。

## 1. MySQL

### 1.1 Capability flags

真源：`adapter/mysql/MySQLCapability.java`

| 位 | 名称 | 说明 | 状态 |
|---|---|---|---|
| 0 | `CLIENT_LONG_PASSWORD` | 长密码认证（旧协议，恒置位） | 已实现 |
| 1 | `CLIENT_FOUND_ROWS` | 返回匹配行数而非变更行数 | 已实现 |
| 2 | `CLIENT_LONG_FLAG` | 长列标志 | 已实现 |
| 3 | `CLIENT_CONNECT_WITH_DB` | 握手响应携带默认库 | 已实现 |
| 4 | `CLIENT_NO_SCHEMA` | 禁止 `db.table.col` 语法 | 已实现 |
| 5 | `CLIENT_COMPRESS` | zlib 压缩，触发 opaque tunnel | 已实现 |
| 6 | `CLIENT_ODBC` | ODBC 兼容 | 已实现 |
| 7 | `CLIENT_LOCAL_FILES` | 允许 `LOAD DATA LOCAL INFILE` | 已实现 |
| 8 | `CLIENT_IGNORE_SPACE` | 忽略函数名后空格 | 已实现 |
| 9 | `CLIENT_PROTOCOL_41` | 4.1 协议 | 已实现 |
| 10 | `CLIENT_INTERACTIVE` | 交互式客户端超时策略 | 已实现 |
| 11 | `CLIENT_SSL` | SSL/TLS，触发 opaque tunnel | 已实现 |
| 12 | `CLIENT_IGNORE_SIGPIPE` | 忽略 SIGPIPE | 已实现 |
| 13 | `CLIENT_TRANSACTIONS` | 协议级事务状态 | 已实现 |
| 14 | `CLIENT_RESERVED` | 保留 | 已实现 |
| 15 | `CLIENT_SECURE_CONNECTION` | 认证响应前置 1 字节长度 | 已实现 |
| 16 | `CLIENT_MULTI_STATEMENTS` | 多语句 | 已实现 |
| 17 | `CLIENT_MULTI_RESULTS` | 多结果集 | 已实现 |
| 18 | `CLIENT_PS_MULTI_RESULTS` | 预处理语句多结果集 | 已实现 |
| 19 | `CLIENT_PLUGIN_AUTH` | 认证插件协商 | 已实现 |
| 20 | `CLIENT_CONNECT_ATTRS` | 连接属性 | 已实现 |
| 21 | `CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA` | 认证数据用 length-encoded | 已实现 |
| 22 | `CLIENT_CAN_HANDLE_EXPIRED_PASSWORDS` | 可处理过期密码 | 已实现 |
| 23 | `CLIENT_SESSION_TRACK` | 会话状态跟踪 | 已实现 |
| 24 | `CLIENT_DEPRECATE_EOF` | OK-as-EOF | 已实现 |
| 25 | `CLIENT_OPTIONAL_RESULTSET_METADATA` | 结果集元数据可选 | 已实现 |
| 26 | `CLIENT_ZSTD_COMPRESSION_ALGORITHM` | zstd 压缩，触发 opaque tunnel | 已实现 |
| 27 | `CLIENT_QUERY_ATTRIBUTES` | `COM_QUERY` 前置参数元数据 | 已实现 |
| 28 | `MULTI_FACTOR_AUTHENTICATION` | 多因子认证 | 已实现 |
| 29 | `CLIENT_CAPABILITY_EXTENSION` | 能力扩展 | 已实现 |
| 30 | `CLIENT_SSL_VERIFY_SERVER_CERT` | 校验服务端证书 | 已实现 |
| 31 | `CLIENT_REMEMBER_OPTIONS` | 记住选项 | 已实现 |

> "已实现"指该位已被识别并用于观测判定；能力本身仍由目标数据库兑现，网关不伪造。

### 1.2 `SERVER_STATUS_*` 状态位

真源：`adapter/mysql/MySQLServerStatusFlag.java`

| 值 | 名称 | 用途 | 状态 |
|---|---|---|---|
| `0x0001` | `SERVER_STATUS_IN_TRANS` | 事务中 | 已用于事务状态 |
| `0x0002` | `SERVER_STATUS_AUTOCOMMIT` | 自动提交 | 已用于 autocommit |
| `0x0008` | `SERVER_MORE_RESULTS_EXISTS` | 还有后续结果集 | 已用于多结果集 |
| `0x0010` | `SERVER_STATUS_NO_GOOD_INDEX_USED` | 未用好索引 | 已定义 |
| `0x0020` | `SERVER_STATUS_NO_INDEX_USED` | 未用索引 | 已定义 |
| `0x0040` | `SERVER_STATUS_CURSOR_EXISTS` | 游标已打开 | 已定义 |
| `0x0080` | `SERVER_STATUS_LAST_ROW_SENT` | 已发送最后一行 | 已定义 |
| `0x0100` | `SERVER_STATUS_DB_DROPPED` | 库已删除 | 已定义 |
| `0x0200` | `SERVER_STATUS_NO_BACKSLASH_ESCAPES` | 反斜杠不转义 | 已定义 |
| `0x0400` | `SERVER_STATUS_METADATA_CHANGED` | 元数据已变更 | 已定义 |
| `0x0800` | `SERVER_QUERY_WAS_SLOW` | 慢查询 | 已定义 |
| `0x1000` | `SERVER_PS_OUT_PARAMS` | 预处理语句含出参 | 已定义 |
| `0x2000` | `SERVER_STATUS_IN_TRANS_READONLY` | 只读事务 | 已定义 |
| `0x4000` | `SERVER_SESSION_STATE_CHANGED` | 会话状态变化 | 已定义 |

### 1.3 命令码与响应形态

真源：`adapter/mysql/MySQLCommandType.java`。每个命令声明 `expects_response` 与
`response_shape`，观测相位机按声明分支，不再用首字节猜测（规则 §3.6）。ERR 对任何
期望响应的命令都合法，优先级高于形态分支。

| 码 | 命令 | 是否有响应 | 响应形态 | 观测处理 |
|---|---|---|---|---|
| `0x00` | `COM_SLEEP` | 否 | `UNKNOWN` | 服务端内部；被客户端发送即暂停观测 |
| `0x01` | `COM_QUIT` | 否 | `NO_RESPONSE` | 会话转 `CLOSING`，不等待响应 |
| `0x02` | `COM_INIT_DB` | 是 | `OK` | 更新会话当前库 |
| `0x03` | `COM_QUERY` | 是 | `RESULTSET` | 抽取 SQL 事件 |
| `0x04` | `COM_FIELD_LIST` | 是 | `COLUMN_LIST` | 统计字段数（响应无列数包） |
| `0x05` | `COM_CREATE_DB` | 是 | `OK` | 仅透传 |
| `0x06` | `COM_DROP_DB` | 是 | `OK` | 仅透传 |
| `0x07` | `COM_REFRESH` | 是 | `OK` | 仅透传 |
| `0x08` | `COM_SHUTDOWN` | 是 | `OK` | 仅透传 |
| `0x09` | `COM_STATISTICS` | 是 | `RAW_STRING` | 单包收尾，不解析内容 |
| `0x0A` | `COM_PROCESS_INFO` | 是 | `RESULTSET` | 仅透传 |
| `0x0B` | `COM_CONNECT` | 否 | `UNKNOWN` | 服务端内部；被客户端发送即暂停观测 |
| `0x0C` | `COM_PROCESS_KILL` | 是 | `OK` | 仅透传 |
| `0x0D` | `COM_DEBUG` | 是 | `EOF_ONLY` | 仅透传 |
| `0x0E` | `COM_PING` | 是 | `OK` | 仅透传 |
| `0x0F` | `COM_TIME` | 否 | `UNKNOWN` | 服务端内部；被客户端发送即暂停观测 |
| `0x10` | `COM_DELAYED_INSERT` | 是 | `OK` | 仅透传 |
| `0x11` | `COM_CHANGE_USER` | 是 | `OK` | 更新客户端身份与当前库 |
| `0x12` | `COM_BINLOG_DUMP` | 是 | `STREAM` | 流式；观测暂停 |
| `0x13` | `COM_TABLE_DUMP` | 是 | `STREAM` | 流式；观测暂停 |
| `0x14` | `COM_CONNECT_OUT` | 否 | `UNKNOWN` | 服务端内部；被客户端发送即暂停观测 |
| `0x15` | `COM_REGISTER_SLAVE` | 是 | `OK` | 仅透传 |
| `0x16` | `COM_STMT_PREPARE` | 是 | `PREPARE` | 抽取 SQL；按头部计数消费元数据尾部 |
| `0x17` | `COM_STMT_EXECUTE` | 是 | `RESULTSET` | 仅透传 |
| `0x18` | `COM_STMT_SEND_LONG_DATA` | 否 | `NO_RESPONSE` | 不进入 `EXECUTING` |
| `0x19` | `COM_STMT_CLOSE` | 否 | `NO_RESPONSE` | 不进入 `EXECUTING` |
| `0x1A` | `COM_STMT_RESET` | 是 | `OK` | 仅透传 |
| `0x1B` | `COM_SET_OPTION` | 是 | `EOF_ONLY` | 单个 EOF，或 `CLIENT_DEPRECATE_EOF` 下的 OK |
| `0x1C` | `COM_STMT_FETCH` | 是 | `RESULTSET` | 仅透传 |
| `0x1D` | `COM_DAEMON` | 否 | `UNKNOWN` | 服务端内部；被客户端发送即暂停观测 |
| `0x1E` | `COM_BINLOG_DUMP_GTID` | 是 | `STREAM` | 流式；观测暂停 |
| `0x1F` | `COM_RESET_CONNECTION` | 是 | `OK` | 清空观测到的会话状态 |

### 1.4 响应形态

真源：`adapter/mysql/MySQLResponseShape.java`。

| 形态 | 含义 | 观测行为 |
|---|---|---|
| `OK` | 单个 OK_Packet | 解析 affected rows / warning count / status flags |
| `COLUMN_LIST` | 列定义列表 + EOF，无列数包 | 统计字段数后收尾 |
| `PREPARE` | prepare-ok 头 + 参数/列定义 + 各自的 EOF | 按头部计数消费，不当作结果集 |
| `RESULTSET` | 列数 → 列定义 →（EOF）→ 行 → 终止包；也可能是 OK | 记录列数、行数与事务状态 |
| `EOF_ONLY` | 单个 EOF，或 `CLIENT_DEPRECATE_EOF` 下的 OK | 按 EOF/OK 收尾 |
| `RAW_STRING` | 单个无标记字符串包 | 不解析内容，单包收尾 |
| `STREAM` | 只有连接结束才终止的响应流 | 暂停观测 |
| `NO_RESPONSE` | 服务端不回包 | 不进入 `EXECUTING`，不等待响应 |
| `UNKNOWN` | 无法推断响应形态 | 暂停观测，等到下一个命令再同步 |

## 2. PostgreSQL

### 2.1 Frontend message 类型

真源：`adapter/postgresql/PostgreSQLMessageType.java`

| 类型 | 名称 | 观测处理 | 状态 |
|---|---|---|---|
| `B` | `Bind` | 建立 portal → statement 映射 | 已实现 |
| `C` | `Close` | 移除 statement / portal 映射 | 已实现 |
| `D` | `Describe` | 仅透传 | 已实现 |
| `E` | `Execute` | 关联出预处理 SQL | 已实现 |
| `H` | `Flush` | 仅透传（无固定响应） | 已实现 |
| `P` | `Parse` | 记录 statement → SQL | 已实现 |
| `p` | `PasswordMessage` | 仅透传，不记录载荷 | 已实现 |
| `Q` | `Query` | 抽取 SQL 事件 | 已实现 |
| `S` | `Sync` | 标记错误恢复同步点 | 已实现 |
| `X` | `Terminate` | 会话转 `CLOSING` | 已实现 |

> `FunctionCall`（`F`）与服务端 `CopyFail`（`f`）尚未枚举。未枚举的消息仍按自描述帧安全
> 跳过，并把该会话的观测置信度降级为 `UNCERTAIN`（规则 §2.10），不中断转发，也不暂停
> 观测，因为 PostgreSQL 的帧自带边界。

### 2.2 Backend message 类型

真源：`adapter/postgresql/PostgreSQLBackendMessageType.java`

| 类型 | 名称 | 观测处理 | 状态 |
|---|---|---|---|
| `R` | `Authentication` | 记录认证类型 int4 | 已实现 |
| `S` | `ParameterStatus` | 记录会话参数 | 已实现 |
| `K` | `BackendKeyData` | 记录 pid/secret 并登记取消键索引 | 已实现 |
| `Z` | `ReadyForQuery` | 更新事务状态、结束 Sync | 已实现 |
| `T` | `RowDescription` | 记录字段数、重置行计数 | 已实现 |
| `D` | `DataRow` | 行计数 | 已实现 |
| `C` | `CommandComplete` | 记录命令标签 | 已实现 |
| `E` | `ErrorResponse` | 记录 severity / SQLSTATE | 已实现 |
| `N` | `NoticeResponse` | 记录 severity / SQLSTATE | 已实现 |
| `A` | `NotificationResponse` | 记录 pid 与 channel（不保留 payload） | 已实现 |
| `1` | `ParseComplete` | 计数 | 已实现 |
| `2` | `BindComplete` | 计数 | 已实现 |
| `3` | `CloseComplete` | 计数 | 已实现 |
| `n` | `NoData` | 计数 | 已实现 |
| `t` | `ParameterDescription` | 记录参数个数 | 已实现 |
| `s` | `PortalSuspended` | 计数 | 已实现 |
| `I` | `EmptyQueryResponse` | 已定义 | 已定义 |
| `G` | `CopyInResponse` | 会话转 `STREAMING`、重置 COPY 计数 | 已实现 |
| `H` | `CopyOutResponse` | 同上 | 已实现 |
| `W` | `CopyBothResponse` | 同上 | 已实现 |
| `d` | `CopyData` | COPY 数据计数 | 已实现 |
| `c` | `CopyDone` | 已定义 | 已定义 |
| `V` | `FunctionCallResponse` | 已定义 | 已定义 |
| `v` | `NegotiateProtocolVersion` | 已定义 | 已定义 |

### 2.3 Type OID

真源：`adapter/postgresql/PostgreSQLTypeOid.java`。用途：审计与测试。
**禁止**用它改写目标库 `RowDescription`。

| OID | 类型名 | 类别 |
|---|---|---|
| 16 | `bool` | boolean |
| 17 | `bytea` | binary |
| 18 | `char` | internal |
| 19 | `name` | identifier |
| 20 | `int8` | integer |
| 21 | `int2` | integer |
| 23 | `int4` | integer |
| 25 | `text` | string |
| 26 | `oid` | object-id |
| 114 | `json` | json |
| 142 | `xml` | xml |
| 700 | `float4` | floating |
| 701 | `float8` | floating |
| 790 | `money` | numeric |
| 869 | `inet` | network |
| 1042 | `bpchar` | string |
| 1043 | `varchar` | string |
| 1082 | `date` | date-time |
| 1083 | `time` | date-time |
| 1114 | `timestamp` | date-time |
| 1184 | `timestamptz` | date-time |
| 1186 | `interval` | date-time |
| 1700 | `numeric` | numeric |
| 2950 | `uuid` | uuid |
| 3802 | `jsonb` | json |
| 1000 | `_bool` | array |
| 1001 | `_bytea` | array |
| 1005 | `_int2` | array |
| 1007 | `_int4` | array |
| 1009 | `_text` | array |
| 1015 | `_varchar` | array |
| 1016 | `_int8` | array |
| 1021 | `_float4` | array |
| 1022 | `_float8` | array |
| 1231 | `_numeric` | array |
| 2951 | `_uuid` | array |
| 3807 | `_jsonb` | array |

## 3. 协议无关

### 3.1 连接状态

真源：`adapter/protocol/ProtocolConnectionState.java`。合法迁移表见
`adapter/protocol/ProtocolSession.java`。

| 状态 | 含义 |
|---|---|
| `CONNECTED` | TCP 已建立，协议尚未启动 |
| `NEGOTIATING` | 版本 / 能力 / TLS / 压缩协商中 |
| `AUTHENTICATING` | 认证交换中 |
| `READY` | 可接收命令或查询 |
| `EXECUTING` | 命令或查询周期执行中 |
| `STREAMING` | COPY / LOAD DATA 等子协议进行中 |
| `CLOSING` | 收到终止命令或 fatal 错误 |
| `CLOSED` | 连接已关闭 |

### 3.2 语句效应与会话脏度

真源：`parser/StatementEffect.java`、`adapter/protocol/SessionDirtiness.java`、
`adapter/protocol/SessionSnapshot.java`。用途：判定连接能否被另一个客户端复用（规则 §8.3）。

| 语句效应 | 观测含义 | 是否使会话变脏 |
|---|---|---|
| `READ` | 只读语句 | 否 |
| `WRITE` | 数据变更 | 否 |
| `DDL` | 结构变更 | 否 |
| `TRANSACTION_CONTROL` | 开启/提交/回滚事务 | 否（事务状态单独跟踪） |
| `SESSION_SETTING` | 会话级设置变更 | 是 |
| `TEMPORARY_OBJECT` | 创建临时对象 | 是 |
| `USER_VARIABLE` | 使用用户变量 | 是 |
| `LOCK` | 持有锁 | 是 |
| `UNKNOWN` | 无法分类 | 是（`tooComplexToReset`） |

| 脏度维度 | 含义 |
|---|---|
| `hasPreparedStatements` | 会话仍持有服务端预处理语句 |
| `hasSessionSettings` | 会话级设置被修改 |
| `hasTemporaryObjects` | 创建过临时对象 |
| `hasUserVariables` | 使用过用户变量 |
| `hasLocks` | 持有锁 |
| `tooComplexToReset` | 存在无法分类的语句，无法证明可安全重置 |

> 无重置复用需要同时满足：观测置信度为 `CONFIRMED`、未处于事务中、且脏度全部为 false。
> 分类器只做关键字扫描（热路径零 AST），`WITH` 语句交给 Druid 判定读写；无法判定一律按
> `UNKNOWN` 处理，即宁可销毁连接也不复用。

### 3.3 网关自身错误映射

真源：`adapter/protocol/GatewayErrorMapping.java`。仅用于网关自身错误；目标数据库
错误一律原样透传。

| 枚举 | MySQL errno | MySQL SQLSTATE | PostgreSQL SQLSTATE | 含义 |
|---|---|---|---|---|
| `TARGET_UNAVAILABLE` | 1042 | `08S01` | `08006` | 目标数据库不可达 |
| `TARGET_CONNECTION_REJECTED` | 1040 | `08004` | `08004` | 目标拒绝连接 |
| `TARGET_HANDSHAKE_FAILED` | 1043 | `08S01` | `08P01` | 目标握手失败 |
| `ROUTE_NOT_FOUND` | 1105 | `HY000` | `08001` | 路由不存在 |
| `UNSUPPORTED_GATEWAY_FEATURE` | 1235 | `42000` | `0A000` | 网关不支持的能力 |
| `PROTOCOL_VIOLATION` | 1047 | `08S01` | `08P01` | 协议违规 |
| `INTERNAL_GATEWAY_ERROR` | 1105 | `HY000` | `XX000` | 网关内部错误 |
| `RESOURCE_EXHAUSTED` | 1041 | `HY000` | `53200` | 网关资源耗尽 |
| `CONNECTION_TIMEOUT` | 2013 | `HY000` | `08006` | 目标连接超时 |
| `RISK_DENIED` | 1142 | `42000` | `42501` | 被风控策略拒绝 |
| `GATEWAY_SHUTTING_DOWN` | 1053 | `08S01` | `57P01` | 网关正在关闭 |

## 4. 维护要求

- 修改任一真源枚举时，必须同步更新本文件对应章节。
- 新增表必须先落在代码枚举中，再在本文件登记为镜像；不得只写文档。
- 本文件不得出现实现中不存在的能力声明（见规则 §1.1）。
