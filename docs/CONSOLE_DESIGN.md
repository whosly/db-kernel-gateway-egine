# 数据库管控台设计（CONSOLE）

> 分支：`future/database-wire-protocol-foundation`  
> 模型：**协议无关 · 实例中心 · 多实例多类型**。  
> 适配器成熟度：MySQL / PostgreSQL **网关代理**可启停；SQL Server **透明代理**（P0 + P1 观测/拒绝，可创建）；Oracle stub。  
> **现行 API**：`/console/api/v1`（契约见 [`CONSOLE_API_V1.md`](CONSOLE_API_V1.md)）。鉴权 / RBAC / 实例 ACL / SQL IDE / 主题见 [`CONSOLE_ARCHITECTURE.md`](CONSOLE_ARCHITECTURE.md) §16–§26。

> **前后端完整架构（分层 / 契约 / 演进）**：见 [`CONSOLE_ARCHITECTURE.md`](CONSOLE_ARCHITECTURE.md)。  
> 本文保留领域模型与 API 摘要；**现行实现状态以 STATUS + 架构文档 §11–§26 为准**（本文早期「非目标」已收敛）。

## 1. 核心原则

1. **一等实体 = 网关实例（Gateway Instance）**，不是「MySQL 管控台」或「PG 管控台」。
2. **数据库类型只是实例属性 / 目录标签**（`dbType=mysql|postgresql|sqlserver|oracle|…`）。UI 与 REST **禁止**按品牌分叉布局或管理流。
3. **一个管控台**同时列出并管理 **多种类型的多个实例**（例如同 JVM 内 2×MySQL + 1×PG）。
4. **`gateway.catalog` / supported-databases** = 可插拔**类型注册表**（哪些适配器存在、是否可创建），与**实例注册表**分离。
5. 行业控制台 UX：侧栏（总览 / 实例 / SQL / 目录 / 告警 / 运维），实例卡带类型徽章与代理模式、KPI、启停 — **全部通用**。

## 2. 目标

| 能力 | MVP | 说明 |
|---|---|---|
| 浏览类型目录 | ✅ | `gateway.catalog` + registry 交叉校验 |
| 列出网关实例 | ✅ | 配置驱动；可多条、多类型 |
| 单实例状态 / 指标 / 启停 | ✅ | 绑定运行时 adapter 的实例可操作 |
| 非密钥配置摘要 | ✅ | 密码脱敏；从不回传明文 |
| 同进程多 listener 真实运行 | ✅ | `GatewayListenerRuntime` 为每个 creatable 实例建独立 ProtocolAdapter；单测覆盖 mysql+pg 独立启停 |
| 鉴权 / SSO / RBAC | ✅ | `open`/`token`/`form`/`oidc`；VIEWER/OPERATOR/ADMIN + 权限串；实例 ACL（§24）；生产仍建议反代 / 网络隔离 |

## 3. 领域模型

### 3.1 类型目录（Catalog）— 不是实例

```text
SupportedDatabase (catalog entry)
  id, displayName, enabled, maturity   # ga|partial|planned|stub
  defaultProxyPort, defaultTargetPort, notes
  registered, creatable, consoleCreateAllowed   # 运行时交叉校验
```

- SQL Server：`maturity=partial`，**可创建透明代理** listener（`consoleCreateAllowed`）；无结果集脱敏 / Attention cancel / 深 token；见 [`SQLSERVER_TDS_PLAN.md`](SQLSERVER_TDS_PLAN.md)。
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
  source: config|console     # YAML vs 管控台 H2
```

**status 语义**

| status | 含义 |
|---|---|
| RUNNING | 已绑定且 adapter 在跑 |
| STOPPED | 已绑定且 adapter 已停 |
| DISABLED | `enabled=false` |
| UNBOUND | 保留枚举兼容；多 listener 落地后 creatable 实例不再使用（见 STATUS） |
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

运行时：`GatewayListenerRuntime` 为每个 **enabled + creatable** 的 `gateway.instances[]` 条目创建独立 `ProtocolAdapter`（空列表则合成 `id=default`）。  
`GatewayInstanceRegistry` 委托 runtime 启停；实例指标为 **每 listener 独立** `GatewayRuntimeMetrics`。  
遗留 `/gateway/*` / Actuator / CLI 仍注入 **legacy** adapter：优先匹配 `proxy-db-type`+`proxy-port`，否则 `default`，否则第一个已绑定实例。

## 4. REST API（实例中心 · 类型无关）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/console/api/v1/databases` | 类型目录（旧名 `/supported-databases`） |
| GET | `/console/api/v1/instances` | 全部实例列表 |
| GET | `/console/api/v1/instances/{id}` | 单实例详情（无密钥） |
| GET | `/console/api/v1/instances/{id}/status` | 状态 |
| GET | `/console/api/v1/instances/{id}/metrics` | 指标 |
| POST | `/console/api/v1/instances` | 创建（H2 + runtime）；密码不回传 |
| DELETE | `/console/api/v1/instances/{id}` | 仅 source=console |
| POST | `/console/api/v1/instances/{id}/start` | 启（仅 bound+creatable） |
| POST | `/console/api/v1/instances/{id}/stop` | 停 |
| GET | `/console/api/v1/overview` | 总览；`metrics`=全实例求和，`legacyMetrics`=遗留 adapter；见架构文档 §2.5 |
| GET | `/console/api/v1/health` | 进程健康（聚合 bound 实例） |
| GET | `/console/api/v1/config/summary` | 非密钥摘要 |

兼容：既有 `/gateway/*`、`/actuator/gateway` **保留**；管控台不替换它们。

完整契约与 envelope：[`CONSOLE_API_V1.md`](CONSOLE_API_V1.md)。**禁止**：`/console/api/v1/mysql/...` 等按品牌拆分的管理 API。

## 5. UI

侧栏（中文）：

1. **总览** — KPI + 火花图 + 实例数量按状态汇总（不按品牌分栏）
2. **网关实例** — 实例卡网格；**网关代理 / 透明代理**徽章；启停；抽屉（脱敏 / ACL / 会话…）
3. **SQL 工作台** — 经代理 listenPort 的 MaxGUI-lite IDE
4. **类型目录** — catalog 表
5. **告警** — 进程内阈值
6. **运维** — 配置摘要 / 审计 / 密钥 / 风控

前端：`console-ui/`（Vue 3 + TS + Vite，`base: '/console/'`）；`mvn package` 经
`frontend-maven-plugin` 输出到 `target/classes/static/console/`。
管控台创建的实例持久化在嵌入式 H2（`gateway.console.db-path`，默认 `./data/gateway-console`）；
YAML `gateway.instances` 为启动引导，不可经 API 删除。

## 6. 安全

- API / UI **永不**返回密码或 keystore 明文；用 `passwordConfigured` + `********`
- 鉴权：`gateway.console.auth.mode=open|token|form|oidc` + RBAC / 实例 ACL（见架构文档 §17 / §22 / §24）
- 生产仍建议前置反代 / 网络隔离；控制面密码可 `enc:v1:` 信封加密
- 启停与管控写操作写入操作审计（H2）

## 7. 代码布局

```text
config/GatewayCatalogProperties.java      # 类型
config/GatewayInstanceProperties.java     # 实例列表
console/SupportedDatabaseCatalog.java
console/SupportedDatabaseInfo.java
console/GatewayInstance.java
console/GatewayInstanceRegistry.java
controller/console/ConsoleApiController.java          # 应用服务门面
controller/console/Console*ApiController.java         # 按域拆分的 HTTP 入口（v1）
controller/console/ConsolePageController.java
static/console/*   # Vue 构建产物
docs/CONSOLE_DESIGN.md
docs/CONSOLE_ARCHITECTURE.md
docs/CONSOLE_API_V1.md
```

## 8. 运行时组件

```text
runtime/GatewayListenerRuntime.java   # 同 JVM 多 listener 管理
console/GatewayInstanceRegistry.java  # 管控台视图 / 启停委托
```

## 9. 非目标（仍适用）

- SQL Server 深度能力（脱敏 / Attention cancel / 深 token / Login7 路由）——保持 P0 + P1（ERROR 观测/拒绝已落地；脱敏等仍 P2）
- Oracle wire
- 外部 Prometheus / PagerDuty
- 按 DB 品牌的独立页面或 API
- 真 Testcontainers 多实例 live 证明（单测已覆盖独立启停；`-Pintegration-testcontainers` 仍为 stub）
