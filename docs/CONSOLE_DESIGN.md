# 数据库管控台设计（CONSOLE）

> 分支：`future/database-wire-protocol-foundation`  
> 模型：**协议无关 · 实例中心 · 多实例多类型**。  
> 适配器成熟度：MySQL / PostgreSQL 今日可启停；SQL Server 仅目录；Oracle stub。

## 1. 核心原则

1. **一等实体 = 网关实例（Gateway Instance）**，不是「MySQL 管控台」或「PG 管控台」。
2. **数据库类型只是实例属性 / 目录标签**（`dbType=mysql|postgresql|sqlserver|oracle|…`）。UI 与 REST **禁止**按品牌分叉布局或管理流。
3. **一个管控台**同时列出并管理 **多种类型的多个实例**（例如同 JVM 内 2×MySQL + 1×PG）。
4. **`gateway.catalog` / supported-databases** = 可插拔**类型注册表**（哪些适配器存在、是否可创建），与**实例注册表**分离。
5. 行业控制台 UX：侧栏（总览 / 实例 / 类型目录 / 运维），实例卡带类型徽章、KPI、启停 — **全部通用**。

## 2. 目标

| 能力 | MVP | 说明 |
|---|---|---|
| 浏览类型目录 | ✅ | `gateway.catalog` + registry 交叉校验 |
| 列出网关实例 | ✅ | 配置驱动；可多条、多类型 |
| 单实例状态 / 指标 / 启停 | ✅ | 绑定运行时 adapter 的实例可操作 |
| 非密钥配置摘要 | ✅ | 密码脱敏；从不回传明文 |
| 同进程多 listener 真实运行 | 🔜 | 注册表与 API 已按多实例建模；运行时仍可先绑一个 adapter |
| 鉴权 / SSO | 🔜 | 延期；生产靠网络隔离 / 反代 |

## 3. 领域模型

### 3.1 类型目录（Catalog）— 不是实例

```text
SupportedDatabase (catalog entry)
  id, displayName, enabled, maturity   # ga|partial|planned|stub
  defaultProxyPort, defaultTargetPort, notes
  registered, creatable, consoleCreateAllowed   # 运行时交叉校验
```

- SQL Server：`maturity=partial`，深度能力 ON HOLD，**可出现在目录**，新建 listener 是否允许由 `consoleCreateAllowed` 决定。
- Oracle：`enabled=false` / stub，创建禁用。

### 3.2 网关实例（Instance）— 一等实体

```text
GatewayInstance
  id: string                 # 稳定 id，如 "gw-mysql-1"
  name: string               # 展示名
  dbType: string             # 目录 id 属性，非布局分支键
  listenHost: string         # 默认 0.0.0.0
  listenPort: int
  enabled: boolean           # 配置是否启用该实例槽位
  status: RUNNING|STOPPED|DISABLED|UNBOUND|UNSUPPORTED
  targetHost / targetPort / targetDatabase / targetUsername
  passwordConfigured: bool   # 仅布尔；无明文
  bound: boolean             # 是否已绑定本进程 ProtocolAdapter
  metrics: map               # 绑定时有值
```

**status 语义**

| status | 含义 |
|---|---|
| RUNNING | 已绑定且 adapter 在跑 |
| STOPPED | 已绑定且 adapter 已停 |
| DISABLED | `enabled=false` |
| UNBOUND | 已配置但本进程尚未挂 listener（多 listener 落地前的占位） |
| UNSUPPORTED | `dbType` 不可创建（stub / 未注册） |

### 3.3 配置

```yaml
gateway:
  catalog:
    databases: [ ... ]          # 类型注册表

  # 实例注册表（可多条、类型可混）。空列表时管控台回退合成 1 条：
  # 由 gateway.proxy-db-type / proxy-port / target.* 生成 id=default。
  instances:
    - id: gw-1
      name: 业务库代理
      db-type: mysql
      listen-host: 0.0.0.0
      listen-port: 33307
      enabled: true
      # 可选覆盖；省略则继承 gateway.target.*
      # target-host / target-port / target-database / target-username
    - id: gw-2
      name: 分析库代理
      db-type: postgresql
      listen-port: 35433
      enabled: true
```

今日运行时：`GatewayConfig` 仍创建 **一个** `ProtocolAdapter`。  
`GatewayInstanceRegistry` 将「与当前 `proxy-db-type` + `proxy-port` 匹配的实例」标为 **bound**，启停委托该 adapter；其余实例可见为 UNBOUND（为双 listener 预留）。

## 4. REST API（实例中心 · 类型无关）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/console/api/supported-databases` | 类型目录 |
| GET | `/console/api/instances` | 全部实例列表 |
| GET | `/console/api/instances/{id}` | 单实例详情（无密钥） |
| GET | `/console/api/instances/{id}/status` | 状态 |
| GET | `/console/api/instances/{id}/metrics` | 指标 |
| POST | `/console/api/instances/{id}/start` | 启（仅 bound+creatable） |
| POST | `/console/api/instances/{id}/stop` | 停 |
| GET | `/console/api/overview` | 总览聚合 |
| GET | `/console/api/health` | 进程健康（聚合 bound 实例） |
| GET | `/console/api/config/summary` | 非密钥摘要 |

兼容：既有 `/gateway/*`、`/actuator/gateway` **保留**；管控台不替换它们。

**禁止**：`/console/api/mysql/...`、`/console/api/postgresql/...` 等按品牌拆分的管理 API。

## 5. UI

侧栏（中文）：

1. **总览** — KPI + 实例数量按状态汇总（不按品牌分栏）
2. **网关实例** — 实例卡网格；卡上 **类型徽章**；启停按钮
3. **类型目录** — catalog 表
4. **运维** — 配置摘要 / 与 `/gateway` 关系说明

静态资源：`static/console/{index.html,console.css,console.js}`，无 Node 构建。

## 6. 安全

- API / UI **永不**返回密码或 keystore 明文；用 `passwordConfigured` + `********`
- 鉴权延期；生产前置反代 / 网络隔离
- 启停仅打应用日志

## 7. 代码布局

```text
config/GatewayCatalogProperties.java      # 类型
config/GatewayInstanceProperties.java     # 实例列表
console/SupportedDatabaseCatalog.java
console/SupportedDatabaseInfo.java
console/GatewayInstance.java
console/GatewayInstanceRegistry.java
controller/console/ConsoleApiController.java
controller/console/ConsolePageController.java
static/console/*
docs/CONSOLE_DESIGN.md
```

## 8. 非目标（本提交）

- 真实双/多 `ServerSocket` 同进程（模型已预留）
- SQL Server 功能深化
- Auth/SSO
- 按 DB 品牌的独立页面或 API
