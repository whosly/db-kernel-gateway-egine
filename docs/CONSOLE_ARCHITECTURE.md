# 数据库管控台 · 前后端架构设计

> 分支：`future/database-wire-protocol-foundation`  
> 状态：**设计已定稿（作者自检通过）→ 据此实现**  
> 关联：领域与 API 摘要见 [`CONSOLE_DESIGN.md`](CONSOLE_DESIGN.md)；缺口与证据见 [`STATUS_AND_GAPS.md`](STATUS_AND_GAPS.md)

---

## 0. 设计目标与约束

| ID | 约束 | 含义 |
|---|---|---|
| C1 | 协议无关 | UI / API **禁止**按 MySQL/PG/… 分叉页面或路径 |
| C2 | 实例一等 | 管控对象是 **Gateway Instance**；`dbType` 仅为属性 / 徽章 |
| C3 | 多实例多类型 | 同一控制面同时管理 N 个 listener（类型可混） |
| C4 | 同进程交付 | 前端构建产物进网关 jar；运维一个进程、一个 HTTP 口 |
| C5 | API 为迁移边界 | 换前端框架不改后端契约；后端演进不破坏已发布字段语义 |
| C6 | 密钥永不回传 | 仅 `passwordConfigured` / 掩码；日志同样禁止明文 |
| C7 | Java 17 | 后端编译目标保持 17 |
| C8 | 鉴权本阶段不做完整 SSO | 可选 `gateway.console.api-token`（§12.7）；生产仍建议网络隔离 / 反代；完整 SSO 规划 |

**本阶段非目标**：完整 Auth/SSO（仅可选 api-token）、SQL Server 深度能力、独立 Node 生产部署、按品牌插件市场 UI。

---

## 1. 逻辑架构（总览）

```text
┌─────────────────────────────────────────────────────────────┐
│  Browser  ·  /console/  (Vue 3 SPA)                          │
│  views → stores → api client → (fetch)                      │
└────────────────────────────┬────────────────────────────────┘
                             │  /console/api/*  (JSON)
┌────────────────────────────▼────────────────────────────────┐
│  Spring Web                                                 │
│  ConsolePageController  → SPA index.html                    │
│  ConsoleApiController   → DTO / Map 响应（无密钥）            │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│  Console application services                               │
│  SupportedDatabaseCatalog  ·  GatewayInstanceRegistry       │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│  Runtime                                                    │
│  GatewayListenerRuntime  (1 ProtocolAdapter / instance)     │
│  shared: pool / TLS / routing / reset / audit / risk        │
│  per-instance: GatewayRuntimeMetrics                        │
└────────────────────────────┬────────────────────────────────┘
                             │ wire
                    ┌────────┴────────┐
                    ▼                 ▼
               MySQL clients    PostgreSQL clients …
```

遗留兼容面（**不是**管控台主路径）：

```text
/gateway/*  ·  /actuator/gateway  ·  CLI
        │
        ▼
 GatewayListenerRuntime.getLegacyAdapter()
 （优先 proxy-db-type+proxy-port 匹配，否则 default，否则首个 bound）
```

---

## 2. 后端架构

### 2.1 分层

| 层 | 包 / 组件 | 职责 | 禁止 |
|---|---|---|---|
| 接入 | `controller.console.*` | HTTP、状态码、异常映射 | 业务分支按 dbType |
| 应用 | `console.*` | 目录视图、实例视图、启停编排 | 直接 `new` 协议细节 |
| 运行时 | `runtime.GatewayListenerRuntime` | 多 listener 生命周期 | 暴露密码 |
| 配置 | `config.Gateway*Properties` | `catalog` / `instances` 绑定 | UI 专用字段泄漏进协议层 |
| 协议 | `adapter.*` | 各 DB wire 实现 | 被 controller 直接依赖（除 metrics 类型） |

### 2.2 领域对象（稳定）

**CatalogEntry（类型）** ≠ **GatewayInstance（实例）**

- Catalog：回答「系统支持哪些类型、能否创建」。
- Instance：回答「当前有哪些 listener、状态与指标」。

状态机（实例）：

```text
UNSUPPORTED  ← dbType 不可创建 / stub
DISABLED     ← enabled=false（不建 listener 或拒绝启）
STOPPED      ← 已绑定 adapter 且未运行
RUNNING      ← 已绑定且监听中
UNBOUND      ← 仅兼容保留；creatable 配置实例不应再出现
```

### 2.3 配置真相源

1. **启动真相**：`application.yml` → `gateway.catalog` + `gateway.instances`（空则合成 `id=default`）。
2. **运行真相**：`GatewayListenerRuntime` 内存中的 ManagedListener 表。
3. **本阶段**：yml = 引导实例；管控台增删写入 **H2**；启停改运行态并与 H2 `enabled` 可同步。不写回 yml。

### 2.4 REST 契约（v1，冻结语义）

基准路径：`/console/api`

| 方法 | 路径 | 成功体要点 |
|---|---|---|
| GET | `/supported-databases` | `{ databases: CatalogEntry[] }` |
| GET | `/instances` | `{ instances, count, byStatus }` |
| GET | `/instances/{id}` | `GatewayInstance`（含可选 metrics 摘要） |
| GET | `/instances/{id}/status` | `{ id, status, bound, message, … }` |
| GET | `/instances/{id}/metrics` | 该 listener 计数 map |
| POST | `/instances` | 创建管控台实例 → **H2 insert** + runtime bind（可选 auto-start）；密码不回传 |
| DELETE | `/instances/{id}` | 仅 `source=console`：停听 + 删 H2 + 移除运行时；YAML 实例 → 4xx |
| POST | `/instances/{id}/start` | `{ ok, message, … }` |
| POST | `/instances/{id}/stop` | 同上 |
| GET | `/overview` | 聚合：instances + databases + **metrics（全实例求和）** + `metricsScope` + `legacyMetrics` + health + config |
| GET | `/health` | 进程级：runningCount / boundCount / status |
| GET | `/config/summary` | 非密钥；password → 掩码或不出现 |
| POST | `/instances` | 校验后写入 H2 + 注册 Runtime；可 creatable 类型 |
| DELETE | `/instances/{id}` | 仅 console/H2 来源：删库行并停 listener；yml 来源拒绝删除 |

**契约规则**

- 字段命名：JSON camelCase；与 Java record/getter 一致。
- 错误：未知 id → 4xx + `{ message }`；禁止堆栈回前端。
- **禁止**新增 `/console/api/{mysql|postgresql}/…`。
- 新增字段只追加；删除或改语义必须升文档版本并改 STATUS。

### 2.5 指标语义（易混点，必须统一）

| 来源 | 含义 | 谁用 |
|---|---|---|
| `overview.metrics` | **所有** bound 实例计数 **求和** | 管控台总览 KPI |
| `overview.legacyMetrics` | legacy adapter 单实例 | 对照 `/gateway/metrics` |
| `instances/{id}/metrics` | 该 id | 实例抽屉 |

实现要求：`ConsoleApiController.overview` 不得只读 `@Primary` 单 bean 指标冒充总览。

### 2.6 Spring 装配要点

- `GatewayListenerRuntime`：创建全部 creatable 实例的 adapter。
- `@Primary ProtocolAdapter`：= `runtime.getLegacyAdapter()`，供 `/gateway/*`、CLI。
- `GatewayInstanceRegistry`：只做查询/启停委托，不拥有 listener 所有权。
- `ConsolePageController`：`/console`、`/console/`、`/console/{path}`（无扩展名）→ `forward:/console/index.html`（SPA）。

### 2.7 后端包结构（目标）

```text
com.whosly.gateway
  controller.console   ConsoleApiController, ConsolePageController
  console              Catalog / Instance 视图模型与 Registry
  console.persist      H2 `gateway_instance`（JdbcTemplate）
  runtime              GatewayListenerRuntime
  config               GatewayCatalogProperties, GatewayInstanceProperties, GatewayConfig
  adapter.*            协议实现（已有）
```

---


## 2.8 联调闭环（本轮必达）与后续脱敏/加密挂点

**联调路径（FE → BE → 数据面）**

1. 管控台「新建实例」：选择目录中的 `dbType`，填写监听端口与后端目标（含密码，仅提交一次）。
2. `POST /console/api/instances` → `GatewayListenerRuntime` **运行时注册**并创建 `ProtocolAdapter`。
3. 管控台「启动」→ 客户端连接 `listenHost:listenPort`，流量经网关到目标库。
4. 总览/抽屉展示该实例状态与指标（全实例聚合 KPI）。

**配置来源与持久化（控制面存储 ≠ 被代理的业务库）**

| 来源 | 创建 | 删除 | 持久化 |
|---|---|---|---|
| `gateway.instances` / 合成 default | 启动装载 | 仅停，不删 | `application.yml`（引导） |
| 管控台 POST 创建 | API + Runtime | DELETE 停并删行 | **嵌入式 H2 文件库**（控制面，重启仍在） |

- H2 默认文件路径可配（如 `gateway.console.datasource` / `./data/gateway-console`）；单测用 H2 内存模式。
- 密码列存于 H2，API **永不回传**；生产级加密 / 外部密钥机属后续，本阶段可明文落控制面库但文档标注风险。
- **禁止**把管控配置写进被代理的 MySQL/PG 业务库。

**列脱敏 / 列加密挂点（Phase A+ 已实现）**

- 挂点：已有 `masking` / traffic observer 管线，按 **实例** 绑定规则（H2 + 热挂 `MaskingEngine`），而不是按 DB 品牌页面。
- 管控台实例抽屉「脱敏规则」Tab；详见 §11。密钥管理 UI / schema 列提示见 §12（已落地）。

## 3. 前端架构（Vue 3 + TypeScript + Vite）

### 3.1 技术选型（已定）

| 项 | 选择 | 理由 |
|---|---|---|
| 框架 | Vue 3 + `<script setup>` | 组件化、中文管控台常见栈 |
| 语言 | TypeScript | 降低迁框架/重构时漏交互；与 API DTO 对齐 |
| 构建 | Vite | 快；`base: '/console/'` |
| 路由 | vue-router（history，base `/console/`） | 总览/实例/目录/运维 |
| 状态 | 轻量：`pinia` 或单 `composables/useConsolePoll.ts` | 避免过重；本阶段推荐 **composable + 模块级 cache**，可不引入 pinia |
| UI 库 | **不引入** Element/Ant 重依赖（首期） | 自研布局 + CSS 变量，视觉可控、包体小 |
| 图表 | 手写 SVG（状态分布） | 无 echarts 体积；后续可换 |
| HTTP | `fetch` 封装 `api/client.ts` | 零 axios 依赖亦可 |

### 3.2 目录（仓库根 `console-ui/`）

```text
console-ui/
  package.json
  vite.config.ts          # base: '/console/'
  tsconfig*.json
  index.html
  src/
    main.ts
    App.vue
    router/index.ts
    api/
      client.ts           # baseURL `/console/api`
      types.ts            # 与后端契约一一对应的 interface
      consoleApi.ts       # 函数：getOverview, startInstance, …
    composables/
      usePolling.ts       # 可见性暂停的定时刷新
    layouts/
      ConsoleLayout.vue   # 侧栏 + 顶栏
    views/
      OverviewView.vue
      InstancesView.vue
      CatalogView.vue
      OpsView.vue
    components/
      KpiGrid.vue
      StatusDonut.vue
      InstanceCard.vue
      InstanceDrawer.vue
      CatalogTable.vue
    styles/
      tokens.css          # 色板、间距
      main.css
```

### 3.3 路由与信息架构

| 路由 | 名称 | 数据 |
|---|---|---|
| `/` | 总览 | `GET /overview` → KPI、状态分布、实例卡 |
| `/instances` | 网关实例 | 列表 + 抽屉 `GET /instances/{id}` + metrics |
| `/catalog` | 类型目录 | `GET /supported-databases` |
| `/ops` | 运维 | `config/summary` + legacy 说明 |

**交互闭环（本阶段）**

1. 进入页 → 拉 overview/instances  
2. 定时刷新 6s；`document.hidden` 时暂停  
3. 启/停 → POST → toast → 立即 refresh  
4. 点卡片 → 抽屉展示详情与**该实例**指标  
5. 密钥字段只显示「已配置 / 未配置」

**本阶段要做**：页面新建/删除（**H2 持久化**）+ 启停联调。**不做**：把配置写进业务库；不做生产级密码加密。

### 3.4 前端分层规则

- `views` 不直接 `fetch`；只调 `api/consoleApi.ts`。
- `types.ts` 是契约镜像；后端改字段先改文档与 types，再改 UI。
- 组件内 **禁止** `if (dbType === 'mysql')` 换布局；最多用 `dbType` 显示徽章文案。
- 错误统一 toast；网络错误可重试刷新。

### 3.5 开发与集成构建

| 模式 | 命令 | 行为 |
|---|---|---|
| 前端本地 | `cd console-ui && npm ci && npm run dev` | Vite dev；proxy `/console/api` → `http://127.0.0.1:8080` |
| 后端本地 | `mvn spring-boot:run` | 提供 API +（package 后）静态资源 |
| 一体包 | `mvn -DskipTests package` 或默认 package | `frontend-maven-plugin` 准备 Node → `npm ci` → `npm run build` → 输出到 `target/classes/static/console/` |

`src/main/resources/static/console/`：

- **开发期**：可保留占位 README；**正式产物以 Maven 构建输出为准**。
- 删除手写 vanilla `index.html/css/js` 作为唯一 UI，避免双实现。

Vite：

```ts
// 要点（实现时遵守）
base: '/console/',
server: { proxy: { '/console/api': 'http://127.0.0.1:8080' } },
build: { outDir: 'dist', emptyOutDir: true }
```

---

## 4. 部署与运行时拓扑

```text
客户端 SQL ──► :33307 / :35433 / …   (各 ProtocolAdapter ServerSocket)
运维浏览器 ──► :8080/console/        (SPA)
              :8080/console/api/*    (JSON)
              :8080/gateway/*        (legacy)
```

单 JVM；多 listen 端口；HTTP 管理口与数据面分离（端口不同）。

---

## 5. 安全与多租户（边界）

| 项 | 本阶段 | 后续 |
|---|---|---|
| 认证 | 无 | Session / OIDC |
| 鉴权 | 无 | 角色：只读 / 运维启停 |
| CSRF | 同站 SPA + 简易 POST；反代后补 | SameSite + token |
| 审计 | 启停打应用日志 | 接入 gateway.audit |

---

## 6. 演进路线（防视野受限）

| Phase | 内容 | 依赖 / 状态 |
|---|---|---|
| **A** | Vue/TS/Vite SPA；总览聚合；抽屉；**H2 持久化创建/删除 + 启停**；FE↔BE↔代理端口联调 | ✅ 完成 |
| **A+** | 实例脱敏规则 CRUD + 热挂；**密钥管理 UI** + **schema 列提示** | ✅ 完成（见 §11 / §12） |
| **B** | 控制面密码信封加密 / 可外置 DB；操作审计 | ✅ 完成（见 §12） |
| **B+** | 会话 / Kill / 健康 / 导出 / 最近语句环 / Compose | ✅ 完成（见 §13） |
| **C** | 完整鉴权 / SSO | 规划；**C partial** 见 §14（审计可见性 + 可选 read-token） |
| **D** | 可选独立前端部署（CDN + API 网关）；BFF | 规划 |
| **E** | 可观测图表（时序）；接 Micrometer | **E lite** 见 §14（进程内 timeseries；外部 Prometheus 非必需） |
| **管控台完备 + SQL 工作台** | 编辑/克隆/导入/筛选/批量 + SQL 经代理 | ✅ 见 §15（Prometheus 本轮不做） |

每阶段仍遵守 C1–C6；前端可替换，**API 版本**用文档章节号管理（现为 **契约 v1**）。

---

## 7. 测试策略

| 层 | 内容 |
|---|---|
| 后端单测 | overview 聚合、启停委托、未知 id、无密钥泄漏 |
| 前端 | `vue-tsc --noEmit`；`vite build` 必过 |
| 手工 | 双实例 yml → 打开 `/console` 启停与抽屉 |
| 不做（本阶段） | Playwright e2e、Testcontainers 联调强制门槛 |

---

## 8. 自检清单（作者）

- [x] 实例一等、类型仅属性；无品牌 API  
- [x] 多 listener 与 legacy 边界写清  
- [x] overview 指标 = 全实例求和，与 legacy 分离  
- [x] Vue/TS/Vite 目录、base、Maven 集成路径明确  
- [x] 前端禁止按 dbType 分叉布局  
- [x] 密钥策略与鉴权延期写明  
- [x] Phase A/B/C 演进避免「做完才发现迁不动」  
- [x] 与现有 `GatewayListenerRuntime` / `CONSOLE_DESIGN` 不冲突  
- [x] 本阶段 CRUD 落 **H2 文件库**；yml 仅引导；业务库不承载管控配置  

**结论：设计可通过 → 进入 Phase A 实现。**

---

## 9. 实现任务切片（Phase A）

1. 固化本文档 + 更新 `CONSOLE_DESIGN.md` 指向本文  
2. 脚手架 `console-ui`（Vue3+TS+Vite+router）  
3. 实现 `api/types.ts` + `consoleApi.ts` + 四视图 + 新建/删除表单  
4. overview 全实例求和 + `legacyMetrics`；H2 POST/DELETE  
5. `frontend-maven-plugin` + SPA forward  
6. 移除 vanilla 唯一实现；`mvn test` + `vite build` 绿灯  
7. 提交（push 由父流程）  


## 10. 自检记录

| 轮次 | 发现 | 处理 |
|---|---|---|
| 1 | 初稿分层/契约/演进完整 | 通过 |
| 2 | 当前代码 `overview.metrics` 仍只读 legacy `@Primary` 指标 | Phase A 必须改为全实例求和并返回 `legacyMetrics` |
| 3 | vanilla 与 Vue 双实现风险 | Phase A 以 Vite 产物为唯一 UI |
| 4 | 用户要求配置落库 | 改为嵌入式 H2 文件库；yml 仅引导；与业务库隔离 |


---

## 11. Phase A+ · 实例安全策略（列脱敏 / 列加密挂点）

> 作者自检通过后实现。协议无关：策略挂在 **Gateway Instance**，不按 DB 品牌分页面。

### 11.1 目标

| 能力 | 本轮 | 说明 |
|---|---|---|
| 按实例配置脱敏规则 | ✅ | H2 持久化；热更新到该实例 `MaskingEngine` |
| 规则类型 | ✅ | `null` / `fixed` / `partial` / `hash` / `encrypt`（映射现有 `*Rule`） |
| 列选择 | ✅ | `column` 精确名；可选 `table`；可选 `namePattern`（regex） |
| 管控台 UI | ✅ | 实例抽屉或「安全策略」子页；类型无关表单 |
| 进程级 Spring `MaskingRule` bean | 兼容 | 仍作为**全局默认**；实例 H2 规则优先合并（实例规则 priority 覆盖同名列） |
| 密钥管理 UI | ✅ | 见 §12.5：`GET/PUT/DELETE /console/api/security/masking-key`；永不回传明文 |
| 动态发现表结构 | ✅ | 见 §12.6：`GET …/schema/columns`（JDBC metadata；失败 502） |

### 11.2 持久化（控制面 H2）

表 `gateway_instance_masking_rule`：

```text
id (pk), instance_id, name, strategy, priority,
column_name, table_name null, name_pattern null,
fixed_value null, keep_prefix null, keep_suffix null,
hash_hex_length null, enabled, created_at, updated_at
```

- `strategy`: `null|fixed|partial|hash|encrypt`
- API 不回传与密钥相关字段；`fixed_value` 可回传（非密钥）或可选掩码
- 实例删除时级联删规则

### 11.3 API（契约追加，仍无品牌路径）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/console/api/instances/{id}/masking-rules` | 列表 |
| PUT | `/console/api/instances/{id}/masking-rules` | 全量替换（事务）或 |
| POST | `/console/api/instances/{id}/masking-rules` | 新增一条 |
| PUT | `/console/api/instances/{id}/masking-rules/{ruleId}` | 更新 |
| DELETE | `/console/api/instances/{id}/masking-rules/{ruleId}` | 删除 |

写操作后：`GatewayListenerRuntime.reloadMasking(instanceId)` 重建该 listener 的 `MaskingEngine` 并
`adapter.setMaskingEngine(...)` **不停 TCP 监听端口**。新会话立即生效；已有会话在建立时捕获的
Engine 保持至重连。可选 `POST .../masking-rules/reload` 手动重挂。

### 11.4 运行时挂接

```text
buildAdapter(instance)
  MaskingRuleRegistry = globalBeans ⊕ instanceH2Rules
  MaskingEngine(engine)
  adapter.setMaskingEngine(engine)
```

- 无规则 → 透明（现有行为）
- 有规则 → fail-closed 改写（现有行为）
- **每实例独立 Engine**，避免串规则
- 实例 H2 规则编译时 priority += `1_000_000`，同列上优先于进程级 Spring `MaskingRule` bean
- `encrypt` 依赖 `gateway.masking.key-base64`（及可选 `key-id`）；未配置则 API 400

### 11.5 前端

- `InstancesView` 抽屉增加「脱敏规则」Tab：表格 + 新增表单（strategy 下拉）
- `api/types.ts` 增补 DTO；禁止 `if (dbType===…)` 分支

### 11.6 自检

- [x] 策略挂实例而非品牌
- [x] H2 控制面，非业务库
- [x] 复用现有 MaskingEngine / *Rule，不重写协议改写
- [x] encrypt 无控制台密钥 UI，用网关配置密钥
- [x] 与联调闭环（创建实例→启停→代理）同一路径



---

## 12. Phase B · 控制面安全加固（+ A+ 遗留 + 可选 API Token）

> 作者自检通过后实现。仍遵守 C1–C7；协议无关；密钥永不回传。

### 12.1 目标总览

| 能力 | 本轮 | 说明 |
|---|---|---|
| 控制面密码信封加密 | ✅ | AES-GCM；主密钥 `gateway.console.secret-key-base64`（32 字节 AES，Base64） |
| 可外置控制面 DB | ✅ | 保留 `db-path`；可选 `jdbc-url` / `username` / `password` |
| 管控操作审计 | ✅ | H2 表 `gateway_console_audit` + SLF4J；可选列表 API |
| 脱敏密钥管理 UI/API | ✅ | 状态查询 / 设置 / 清除；热重载；永不回传明文 |
| Schema 列提示 | ✅ | 服务端 JDBC `DatabaseMetaData`；协议无关；无品牌 UI |
| 可选 API Token | ✅（C-lite） | `gateway.console.api-token`；空白则开放（实验室默认） |
| 完整 Spring Security / SSO | ❌ | Phase C 规划 |
| Micrometer 图表 | ❌ | Phase E |
| 独立 CDN 前端 | ❌ | Phase D |

### 12.2 密码信封加密

**配置**

```yaml
gateway:
  console:
    # 32-byte AES key, Base64。占位示例（勿当真钥提交）：
    # secret-key-base64: ${GATEWAY_CONSOLE_SECRET_KEY_BASE64:}
    secret-key-base64: ""
```

**存储形态**

- 前缀：`enc:v1:` + Base64(`iv ‖ ciphertext ‖ tag`)，IV 12 字节，AES-GCM，tag 128 bit。
- 写入：主密钥存在 → 明文密码加密后入库；主密钥缺失 → **实验室模式**允许明文写入并 **WARN 一次**。
- 读取：有前缀 → 解密供运行时 bind；无前缀 → 视为遗留明文（兼容）；下次 update 时可顺带改写为密文。
- API 仍只暴露 `passwordConfigured`，永不回传密码。
- **加密写入缺主密钥**：返回清晰 **400/503**（消息说明需配置 `gateway.console.secret-key-base64`）。本实现：实验室模式仍允许明文落库（WARN），与「缺钥仍可 lab」一致；若运维强制加密，可另开开关（规划）。

**组件**：`ConsoleSecretCipher`（控制面专用，与结果集 `MaskingCipher` 分离）。

### 12.3 可外置控制面 DB

| 属性 | 默认 | 说明 |
|---|---|---|
| `gateway.console.db-path` | `./data/gateway-console` | 嵌入式 H2 文件路径 |
| `gateway.console.jdbc-url` | （空） | 非空时 **覆盖** 文件路径，直连该 JDBC URL |
| `gateway.console.username` | `sa` | 控制面库用户 |
| `gateway.console.password` | （空） | 控制面库口令 |

- 单测继续用 H2 内存。
- 其它 JDBC 库（PG 等）为 **尽力而为**（DDL 用 H2 `MERGE` / 标准 SQL 子集）；生产推荐仍 H2 文件或兼容库。
- **禁止**把被代理业务库当作控制面库。

### 12.4 管控操作审计

表 `gateway_console_audit`：

```text
id (pk), at, action, instance_id null, detail_json, actor
```

- 写入时机：实例 create/update/delete、start/stop、脱敏规则 CRUD、脱敏密钥变更。
- `detail_json`：**禁止**含密码、脱敏密钥、`secret-key` 材料。
- 同步打一条 SLF4J `INFO`（同样无密钥）。
- 可选：`GET /console/api/audit?limit=` 列表（默认有上限）。

### 12.5 脱敏密钥管理（不回显明文）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/console/api/security/masking-key` | `{configured, keyId, source:"config"|"console"|"none"}` |
| PUT | `/console/api/security/masking-key` | body `{keyId?, keyBase64}` → 控制面 secrets 表加密存储；热重载；**永不返回 key** |
| DELETE | `/console/api/security/masking-key` | 清除控制台覆盖；回退 yaml `gateway.masking.key-base64` |

- 优先复用 / 扩展 `MaskingKeyProvider`、`InstanceMaskingRuleCompiler`（可变 holder）。
- PUT 后：所有已绑定实例 `reloadMasking`（不停 TCP）。
- secrets 表行用 `ConsoleSecretCipher` 加密；无主密钥时 PUT → 400/503。

### 12.6 Schema 列提示（安全）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/console/api/instances/{id}/schema/columns?table=` | `{columns:[{name,table,nullable,typeName}]}` |

- 服务端用该实例 **已存目标凭据** 建 JDBC 连接，读 `DatabaseMetaData`。
- 协议无关：仅按 `dbType` 拼 JDBC URL（实现细节），**无品牌 UI / 无品牌 API 路径**。
- 实例未绑定凭据或连接失败 → **502** + 可读消息。
- 不落业务库控制配置；短连接，用毕关闭。

### 12.7 可选 API Token（Phase C-lite）

```yaml
gateway:
  console:
    api-token: ${GATEWAY_CONSOLE_API_TOKEN:}   # 空白 = 开放（实验室）
```

- 非空白时：`/console/api/**` 要求 `Authorization: Bearer <token>` **或** `X-Console-Token: <token>`；缺/错 → **401**。
- **不**保护静态 SPA `/console` 与 `/console/` 资源（否则首屏无法加载；Token 由运维网络或后续 SSO 补）。
- 非完整 Spring Security / SSO（仍属 Phase C）。

### 12.8 演进表更新

| Phase | 内容 | 状态 |
|---|---|---|
| **A** | Vue SPA；H2 实例 CRUD；启停；overview 聚合 | ✅ 完成 |
| **A+** | 实例脱敏规则 CRUD + 热挂；**本轮补**：密钥 UI + schema 列提示 | ✅ 完成 |
| **B** | 密码信封加密；可外置 DB；操作审计 | ✅ 完成 |
| **C** | 完整鉴权 / SSO；审计进 spool | 规划（本轮仅 api-token） |
| **D** | 独立 CDN 前端 | 规划 |
| **E** | Micrometer 图表 | 规划 |

### 12.9 自检清单（作者）

- [x] 密码 `enc:v1:` AES-GCM；遗留明文可读；API 无泄漏
- [x] 缺主密钥：lab 明文 WARN；加密相关写入有明确错误语义
- [x] `db-path` + 可选 `jdbc-url` 外置；业务库隔离
- [x] 审计表无密钥字段；关键写路径落审计 + SLF4J
- [x] 脱敏密钥 GET 仅状态；PUT/DELETE 热重载；不回显
- [x] schema columns 协议无关 JDBC metadata；失败 502
- [x] api-token 可选；仅 `/console/api/**`
- [x] 无品牌 API/UI 分叉；Java 17
- [x] 单测：加解密往返、遗留明文、无密码泄漏、审计、密钥状态、token 401、schema（mock/H2）
- [x] 前端中文标签；`vue-tsc` + `npm run build` 通过
- [x] STATUS / README 如实更新

**结论：设计可通过 → 进入 Phase B 实现。**


---

## 13. 行业对标 · 会话 / 健康 / 导出 + Compose 一键启

> 作者自检通过后实现。对标 **MaxScale MaxGUI / ProxySQL Admin / PgBouncer SHOW** 的 **代理控制面**能力（非完整 DBA IDE）。  
> 明确 **非目标**：MaxGUI 查询编辑器、ETL、故障切换编排、完整 Spring Security SSO。

### 13.1 对标映射

| 行业能力 | 本项目落点 | API（协议无关） |
|---|---|---|
| Processlist / SHOW CLIENTS | `ProtocolAdapter#getActiveSessionSnapshots()` | `GET /console/api/instances/{id}/sessions` |
| KILL CLIENT | 关闭客户端腿 Socket | `DELETE /console/api/instances/{id}/sessions/{connectionId}` |
| Backend monitor / ping | TCP（首选）+ 可选 JDBC | `POST /console/api/instances/{id}/health-check` |
| Config export（无密钥） | 实例 + 脱敏规则元数据 + 配置摘要 | `GET .../instances/export` · `GET .../config/export` |
| 轻量近期语句 | 内存环 `RecentTrafficRing` | `GET .../instances/{id}/recent-statements` |
| 一键实验室 | Docker Compose + 多阶段镜像 | 仓库根 `docker-compose.yml` |

### 13.2 会话列表与 Kill

**响应字段（无密钥）**：`connectionId`、`protocolName`、`state`、`confidence`、`inTransaction`、`clientUser`、`clientDatabase`、`dirtiness`（摘要 map）、`connectedAt`、`lastActivity`（ISO-8601）。

**Kill 语义**：

- `AbstractProtocolAdapter` 维护 `connectionId → Socket`（与 `activeSessions` 同步 register/unregister）。
- Kill **仅关闭客户端腿**；中继线程退出后释放后端（与 MaxGUI/PgBouncer「掐客户端」同类，**不**代发协议级 KILL 到后端）。
- 未知 `connectionId` → **404**；实例未绑定 / 已停止 → 空列表或 Kill 404。

### 13.3 后端健康探测

- 超时约 **3s**。
- 步骤：TCP connect `targetHost:targetPort` → 可选 JDBC `isValid` / 简单查询（有解密后凭据时）。
- 响应：`{ok, latencyMs, targetHost, targetPort, message, checkedAt, tcpOk?, jdbcOk?}`。
- 协议无关：禁止 `/console/api/mysql/...` 品牌路径。

### 13.4 配置导出

- **实例导出**：JSON 数组；`passwordConfigured` 布尔；**永不**含 password / secret-key / masking key 材料；可附带脱敏规则元数据（id/name/strategy/column/table/enabled，无密钥）。
- **配置导出**：catalog 摘要 + 实例导出 + `maskingKeyConfigured` + `audit.enabled` 等非密钥标志（来自 config summary / GatewayConfig）。
- UI：运维页或实例页「导出配置」下载 JSON。

### 13.5 最近语句环（诚实边界）

- 进程内 `RecentTrafficRing`（默认容量 100），实现 `DatabaseTrafficObserver`，与审计 observer **compose**。
- 优先在 **masking 包装之后**写入，避免明文密码进环；截断过长 statement。
- 按 `instanceId` 标签过滤（装配时 tagging）。
- **STATUS 诚实声明**：内存-only，重启丢失；**不能**替代 audit spool。

### 13.6 Docker Compose 一键启（实验室）

| 服务 | 镜像 / 构建 | 端口 | 说明 |
|---|---|---|---|
| `mysql` | `mysql:8` | `13308:3306` | 密码 `Aa123456.`（含末尾点）；库 `demo`；healthcheck |
| `postgres` | `postgres:16` | `15432:5432` | 同密码风格；healthcheck |
| `gateway` | 多阶段 Dockerfile | `8080`、`33307`、`35433` | `SPRING_PROFILES_ACTIVE=docker`；挂载 `application-docker.yml` 或 profile 资源；卷 `./data/console` |

- 构建：`maven:3.9-eclipse-temurin-17` → `mvn -DskipTests package`（CI 跑测试）；运行时 `eclipse-temurin:17-jre`。
- `.env.example`：`GATEWAY_CONSOLE_SECRET_KEY_BASE64`（`openssl rand -base64 32`）；**勿**提交真实 `.env`。
- 密码仅 lab；**不声称**生产安全。主路径 SPA 由 jar 提供 `/console`；可选 profile `dev-ui` 非必须。

### 13.7 演进表更新

| Phase | 内容 | 状态 |
|---|---|---|
| **A / A+ / B** | 见 §11–§12 | ✅ |
| **B+（本轮）** | 会话 / Kill / 健康探测 / 导出 / 最近语句环 / Compose | ✅ 设计 → 实现 |
| **C partial / E lite（本轮）** | 审计可见性 · 进程内指标时序 · 风控规则 · 连接池徽章 | ✅ 见 §14 |
| **C / D / E 完整** | SSO · CDN · 外部 Micrometer/Grafana | 规划 |

### 13.8 自检清单（作者）

- [x] 会话 API 协议无关；无密钥字段；停止实例返回空列表
- [x] Kill：connectionId→Socket；关客户端腿；未知 id → 404；文档写明语义
- [x] 健康探测：TCP + 可选 JDBC；~3s 超时；无品牌路径
- [x] 导出：无 password / masking key；仅 `passwordConfigured` / configured 标志
- [x] RecentTrafficRing：有界、可截断、mask 后写入、实例过滤；STATUS 标明内存-only
- [x] Compose / Dockerfile / `.env.example` / `.dockerignore` / `application-docker.yml`；README 中文一键命令
- [x] 无 `/console/api/mysql|postgresql/...` 分叉
- [x] 单测：空会话、Kill 404、导出无密码、ring 边界、健康超时映射
- [x] `mvn test` 绿；`npm run build` 绿；STATUS / README 更新

**结论：设计可通过 → 进入 §13 实现。**

---

## 14. Phase C partial + E lite · 审计可见性 / 指标时序 / 风控规则

> 作者自检通过后实现。对标行业代理（审计状态、指标趋势、query/risk rules），**不做**完整 SSO/OAuth、MaxGUI 查询编辑器、外部 Grafana 必选路径。

### 14.1 目标总览

| 能力 | 本轮 | 说明 |
|---|---|---|
| 流量审计状态 API | ✅ | `GET /console/api/audit/status` — 非密钥字段：enabled / destination / spoolDir / maskStatements / shipperRunning? / recordsPendingHint? / consoleAuditCount? |
| 管控操作审计列表 | ✅（增强） | 既有 `GET /console/api/audit`；可选 `action` + `limit` 过滤 |
| 可选只读 Token | ✅（nice-to-have） | `gateway.console.read-token` 仅允许 GET；写操作仍需 `api-token` |
| 进程内指标时序 | ✅ | `MetricsHistorySampler` 每 N 秒快照（默认 5s，环约 120 点 ≈ 10 min）；`GET /console/api/metrics/history` |
| Overview 火花图 | ✅ | 轻量 SVG（无 echarts）；不依赖外部 Prometheus |
| 连接池状态 | ✅ | 实例 metrics/status 暴露 `pool.enabled` / `idleCount` / `maxIdle`；抽屉徽章「连接池」 |
| 风控规则管控 | ✅ | `GET/PUT /console/api/risk-policy`；H2 覆盖 + YAML 默认；热挂到运行中 adapter |
| 完整 SSO / Spring Security | ❌ | 仍属 Phase C 完整版 |
| 外部 Grafana / Micrometer 必选 | ❌ | 可选后续；主路径为进程内 history |

### 14.2 审计可见性（C partial）

```text
GET /console/api/audit/status
→ {
    enabled, destination, spoolDir, maskStatements,
    shipperRunning?, recordsPendingHint?, consoleAuditCount?,
    help: "见 docs/OPS.md · gateway.audit.*"
  }
```

- 流量审计仍由 `gateway.audit.*` + `AuditSpool` / `AuditShipper` 驱动；§14 本轮只**暴露状态**。内容浏览见 **§18**（`GET /console/api/audit/spool`）。
- Ops 页补充启用说明 + 指向 `docs/OPS.md`。
- `GET /console/api/audit?action=&limit=` 仍仅列**管控操作审计**（H2 `gateway_console_audit`）。

### 14.3 指标时序（E lite）

| 项 | 约定 |
|---|---|
| 采样器 | `MetricsHistorySampler`（Spring `@Scheduled` 或自管 scheduler） |
| 间隔 | `gateway.console.metrics-history.interval-seconds`（默认 5） |
| 容量 | `gateway.console.metrics-history.capacity`（默认 120） |
| 点字段 | `t`（epoch ms）、`connectionsAccepted`、`policyDenials`、`activeConnections`、其余 `GatewayRuntimeMetrics.snapshot()` 键 |
| API | `GET /console/api/metrics/history?instanceId=&limit=` → `{intervalSeconds, points:[…]}`；无 `instanceId` = 总览求和 |
| UI | Overview 简易 SVG sparkline |
| 边界 | **内存环，重启丢失**；不替代 Prometheus remote |

外部 Prometheus：若后续加 `micrometer-registry-prometheus`，文档写 scrape 口；**本轮不强制**。

### 14.4 连接池徽章

- `AbstractProtocolAdapter` 暴露 `poolEnabled` + 聚合 `idleCount`（直连 `PooledBackendProvider`；路由时对 fallback/rules 求和）。
- `GET …/instances/{id}/metrics|status` 增加 `pool: {enabled, idleCount, maxIdle}`。
- 抽屉 Info Tab 显示「连接池 · 开/关 · idle=n」。

### 14.5 风控规则（ProxySQL-like lite）

| 项 | 约定 |
|---|---|
| 持久化 | H2 表 `gateway_risk_policy`（单例 id=`default`）；无行则有效策略 = YAML `gateway.risk.*` |
| API | `GET /console/api/risk-policy`；`PUT` body `{deniedOperations:[], deniedStatementKeywords:[], enabled:true}` |
| 语义 | 空列表 + enabled = **allow-all**（与 `DenyListDatabaseRiskPolicy` 一致）；协议无关 |
| 热挂 | `MutableDatabaseRiskPolicy`（AtomicReference）共享给各 adapter；PUT 后立即对**已有会话**生效（evaluate 走委托） |
| UI | Ops「风控」区块：编辑列表、保存、空=放行全部 |
| YAML | 仍为启动默认；控制台覆盖优先生效并持久化到 H2 |

### 14.6 可选 read-token

- `gateway.console.api-token`：读写（既有）。
- `gateway.console.read-token`：仅 `GET`（及 `HEAD`）；与 api-token 任一匹配即过；**写方法**必须匹配 api-token（若 api-token 已配置）。
- 二者皆空 → 实验室开放（既有行为）。

### 14.7 演进表更新

| Phase | 内容 | 状态 |
|---|---|---|
| **A / A+ / B / B+** | 见 §11–§13 | ✅ |
| **C partial（本轮）** | 审计状态可见 · 可选 read-token | ✅ |
| **E lite（本轮）** | 进程内 metrics history + sparkline | ✅ |
| **风控 lite（本轮）** | risk-policy GET/PUT + 热挂 | ✅ |
| **C / E 完整** | SSO · 外部 Micrometer/Grafana | 规划 |

### 14.8 自检清单（作者）

- [x] 无品牌 API 路径；风控/审计/指标均挂实例或进程级
- [x] audit/status 无密钥 / 无 JDBC 密码
- [x] metrics history 内存环边界写明；UI 不强制 Prometheus
- [x] pool 字段仅非密钥计数；未启用时 enabled=false
- [x] risk-policy 空=allow-all；热挂可验证；单测覆盖 GET/PUT + deny 求值
- [x] read-token 仅 GET；写仍需 api-token（若已配）
- [x] STATUS P2-3/P2-4 + README 同步；`mvn test` + `npm run build` 绿

**结论：设计可通过 → 进入 §14 实现。**


## 15. 管控台自身完善 · 实例编辑 / 导入 / 克隆 / 筛选 + SQL 工作台

> 本轮焦点：**管控台控制面完备**（对标 MaxGUI / ProxySQL web 的实例生命周期），**不含**外部 Prometheus / Micrometer scrape / Grafana。
> 进程内 `metrics/history`（§14 E lite）保留；外部指标出口明确 **out of this round**。

### 15.1 目标总览

| 能力 | 状态约定 | 说明 |
|---|---|---|
| 实例编辑 `PUT` | ✅ 本轮 | 仅 `source=console`；YAML 实例 400；不可改 `dbType`/`id` |
| 克隆 `POST …/clone` | ✅ 本轮 | 复制为新管控台实例；可带脱敏规则；密码密文在 H2 内复制、API 不回显 |
| 导入 `POST …/import` | ✅ 本轮 | 对齐 export shape；默认不接受/忽略密码字段 |
| 列表筛选 | ✅ 本轮 | `GET /instances?status=&dbType=&q=` 服务端过滤 |
| 批量启停 | ✅ 本轮 | `POST /instances/bulk` per-id 结果 |
| SQL 工作台 | ✅ 本轮 / 已改经代理 | `POST …/sql/execute`；挂网关实例；**经代理 listenPort**（脱敏/观测/数据面风控生效）；h2 lab 仍直连 |
| Prometheus 出口 | ❌ 本轮不做 | 见 §14.3 / STATUS P2-4 |

### 15.2 实例更新（PUT）

| 项 | 约定 |
|---|---|
| 路径 | `PUT /console/api/instances/{id}` |
| 可写字段 | `name`、`listenHost`、`listenPort`、`targetHost`、`targetPort`、`targetDatabase`、`targetUsername`、`targetPassword`（可选；省略/空串=保留原密码）、`enabled` |
| 不可改 | `id`、`dbType`（需重建：删后建或克隆后改目标） |
| 来源 | 仅 `source=console`；`source=config` → **400**「YAML/配置实例不可编辑；请先克隆为管控台实例」 |
| 端口冲突 | 与其它 **enabled** 实例 `listenPort` 冲突 → 400（排除自身） |
| 运行中变更 | **停听 → 按新配置重建 adapter → 同 id 再启动**（listen/target/凭据/enabled 影响绑定的字段）；仅改展示名等元数据也走同一替换路径以保持简单。不采用「运行中 409 拒绝」 |
| 密码 | 响应仅 `passwordConfigured`；永不回显明文 |
| 审计 | `instance.update` |

### 15.3 克隆

| 项 | 约定 |
|---|---|
| 路径 | `POST /console/api/instances/{id}/clone` |
| Body | 可选 `{ id?, name?, listenPort?, copyMaskingRules?: true }` |
| 行为 | 任意来源均可克隆为 **新** `source=console` 实例；配置文件原件保留 |
| 密码 | 从 H2 解密复制（console 源）或进程目标密码（config 源）写入新行密文；响应不回显 |
| 端口 | `listenPort` 省略则自动选空闲端口（自源端口+1 起探测） |
| 脱敏 | 默认复制 masking rules（新 rule id） |
| 审计 | `instance.clone` |

### 15.4 导入

| 项 | 约定 |
|---|---|
| 路径 | `POST /console/api/instances/import` |
| Body | `{ instances: [...], replace?: false, skipExisting?: true }`；`instances[]` 对齐 `GET …/instances/export` |
| 密码 | **忽略**导入 JSON 中的任何 password 字段；新建 `passwordConfigured=false`，需用户随后编辑写入 |
| 冲突 | `replace=true`：覆盖已有 **console** 同 id（不可覆盖 config id）；`skipExisting=true`（默认）：跳过冲突；二者皆 false：遇冲突 400 |
| 校验 | `dbType` 须 creatable；`listenPort` 冲突拒绝该条 |
| 审计 | `instance.import`（detail 含 created/skipped/failed 计数） |

### 15.5 列表筛选与批量

| 项 | 约定 |
|---|---|
| 筛选 | `GET /console/api/instances?status=&dbType=&q=`；`q` 匹配 id/name/listenHost/targetHost（含）；`byStatus`/`count` 基于过滤后集合 |
| 批量 | `POST /console/api/instances/bulk` body `{ action: "start"\|"stop", ids: string[] }` → `{ results: [{id, ok, message}], okCount, failCount }`；config 实例允许 stop；start 须已 bound |

### 15.6 SQL 工作台（协议无关）

| 项 | 约定 |
|---|---|
| 定位 | 一等能力挂在 **Gateway Instance**；不是按品牌分叉的页面 |
| 执行路径 | **服务端 JDBC 经该实例代理监听口**（与业务客户端同路径）：connect host 在 bind=`0.0.0.0`/`::`/`*` 时用 `127.0.0.1`，否则用具体 `listenHost`；port=`listenPort`；库名/用户/密码仍为**目标凭据**（透明代理）。列提示 `/schema/columns` 仍直连目标库。 |
| 例外 | `dbType=h2`（lab/单测）无 ProtocolAdapter 线协议 → **直连目标 JDBC**，不要求 RUNNING |
| 路径 | `POST /console/api/instances/{id}/sql/execute` |
| Body | `{ sql, maxRows?: 200 (cap 1000), timeoutMs?: 15000 (cap 60000) }` |
| 响应 | `{ ok, columns, rows, rowCount, truncated, durationMs, viaProxy, proxyHost?, proxyPort?, note, message?, warnings? }`；`note` 中文说明经代理口 |
| 前置 | 线协议类型须实例 **RUNNING**；未启动 → **400**「请先启动…」 |
| 单语句 | 仅允许单条语句；中间 `;` 拒绝（允许末尾分号） |
| 风控 | 执行前管控台层 `MutableDatabaseRiskPolicy`（defense in depth）；代理数据面亦有风控 |
| 超时 | JDBC `Statement.setQueryTimeout`；超时取消 |
| 安全 | 不记/不回密码；单元格字符串截断（4KB）；审计 `sql.execute`（语句截断） |
| 类型 | MySQL / MariaDB / PostgreSQL / SQL Server（有驱动）；h2 lab 直连；SQL Server 无驱动则 **400** |
| 错误 | 无密码/未启动/不支持类型 → 400；连接失败 → 502；未知实例 → 400 |
| UI | 顶栏「SQL 工作台」路由 `/sql`；标明经代理口 + 须启动；中文文案 |

### 15.7 REST 契约追加

| 方法 | 路径 | 要点 |
|---|---|---|
| PUT | `/instances/{id}` | 更新 console 实例 |
| POST | `/instances/{id}/clone` | 克隆为新 console 实例 |
| POST | `/instances/import` | 批量导入 |
| GET | `/instances?status=&dbType=&q=` | 筛选 |
| POST | `/instances/bulk` | 批量 start/stop |
| POST | `/instances/{id}/sql/execute` | SQL 工作台 |

### 15.8 演进表更新

| Phase | 内容 | 状态 |
|---|---|---|
| A–E lite / 风控 | 见 §11–§14 | ✅ |
| **管控台完备（本轮）** | 编辑 · 克隆 · 导入 · 筛选 · 批量 | ✅ |
| **SQL 工作台（经代理）** | JDBC → listenPort；脱敏可验证；h2 例外直连 | ✅ |
| Prometheus / SSO / IDE | 外部指标 · 完整鉴权 · 完整 SQL IDE | 规划（本轮明确不做） |

### 15.9 自检清单（作者）

- [x] 无品牌 API 路径；实例一等；SQL 挂实例
- [x] PUT 拒绝 config 源；不可改 dbType/id
- [x] 运行中更新：stop→rebind→start；端口冲突排除自身
- [x] 密码永不回显；omit 保留；导入忽略密码
- [x] 克隆复制 H2 密文；API 无明文
- [x] SQL 单语句 + 风控 + 单元格截断 + 审计截断
- [x] SQL 经代理 listenPort（RUNNING 门禁）；h2 lab 直连例外已文档化
- [x] Prometheus / 外部 scrape **本轮不做**（写明）
- [x] STATUS P2-3 + README API 表同步；`mvn test` + `npm run build` 绿

**结论：设计可通过 → 进入 §15 实现。**

## 16. 完整 SQL IDE（MaxGUI-lite 对标）

> 本轮「完整」相对 MaxGUI / ProxySQL Web 的 **轻量 IDE**，**不是** DataGrip。  
> 协议无关：对象树 / 历史 / 片段均挂 **网关实例**；无 `/console/api/mysql/...` 品牌路径。  
> SQL **执行**仍走 `InstanceSqlExecuteService` → 代理 `listenPort`（§15.6）；**元数据树**直连目标 JDBC（与 columns 同路径）。

### 16.1 能力

| 能力 | 说明 |
|---|---|
| Schema catalog | JDBC DatabaseMetaData → schemas / tables；REST 挂实例 |
| 多 Tab 编辑器 | Vue 本地 Tab + localStorage |
| 历史 / 片段 | 控制面 H2 持久化 + REST |
| 导出 | 结果集 CSV / JSON |
| EXPLAIN | 按 dbType 包装（MySQL/PG/…）；执行仍经代理 |

### 16.2 非目标（本轮明确不做）

语句取消、可视化 Query Builder、ER 图、跨实例联合查询、品牌专用 IDE API。

### 16.3 自检

- [x] 对象树 / 历史 / 片段 / 导出 / EXPLAIN 包装落地
- [x] 执行路径仍为代理 listenPort（RUNNING 门禁）
- [x] `mvn test` + `npm run build` 绿

---

## 17. 管控台鉴权 · SSO（OIDC）与 HTTPS

> **诚实边界**：`open` / `token` / `form` 可在无外部依赖下完整使用。  
> `oidc` 是 **可激活** 的 Spring Security OAuth2 Login 模式（ClientRegistration + 角色映射 + 登录 UX），  
> **E2E 登录仍需要真实 IdP**（Keycloak / 任意 OIDC）。单元测试用 **显式 endpoint URI**，不打外网 discovery。

### 17.1 模式一览

| mode | 说明 |
|---|---|
| `open` | lab 默认（无 token 时）；API 开放 |
| `token` | Bearer / `X-Console-Token`；可选 read-token |
| `form` | Session + Cookie CSRF；`gateway.console.auth.users` |
| `oidc` | OAuth2 Authorization Code；registration id 默认 `console` |

配置前缀：`gateway.console.auth.*`。角色：`CONSOLE_ADMIN` / `CONSOLE_VIEWER`（Spring `ROLE_*`）。

### 17.2 OIDC ClientRegistration 解析顺序

1. **显式覆盖**：任一 `authorization-uri` / `token-uri` / `jwk-set-uri` 有值 → 必须三者齐全（`user-info-uri` 可选）。**推荐 lab / CI**。
2. **`provider=keycloak`** 且覆盖为空 → 在 `issuer-uri` 下拼 Keycloak 路径（`/protocol/openid-connect/{auth,token,certs,userinfo}`）。
3. **否则** → `ClientRegistrations.fromIssuerLocation(issuer-uri)`（需网络；失败抛清晰 `IllegalStateException` 提示改用显式 URI）。

登录入口：`/oauth2/authorization/{registration-id}`（默认 `/oauth2/authorization/console`）。  
成功重定向：`/console/`。登出：`POST /console/api/auth/logout`（清 Session + 审计）。

### 17.3 角色映射

| 配置 | 默认 | 说明 |
|---|---|---|
| `oidc.role-claim` | `roles` | 支持点路径如 `realm_access.roles`；空时回退 `realm_access.roles` / `groups` / `roles` |
| `oidc.admin-role-values` | `CONSOLE_ADMIN,admin,console-admin` | 命中 → ADMIN + VIEWER |
| `oidc.viewer-role-values` | `CONSOLE_VIEWER,viewer,console-viewer` | 未命中 admin 时默认 VIEWER |

### 17.4 API / UX

| 项 | 说明 |
|---|---|
| `GET /console/api/auth/mode` 与 `/status` | `oidc=true` 时附带 `ssoLoginUrl`、`registrationId`、`oidcConfigured` |
| LoginView | OIDC 模式展示「使用 SSO 登录」→ `ssoLoginUrl` |
| 审计 | `auth.login` / `auth.login.failure` / `auth.logout` |

### 17.5 Keycloak 实验室示例

```yaml
gateway:
  console:
    auth:
      mode: oidc
      oidc:
        provider: keycloak
        issuer-uri: http://localhost:8081/realms/gateway
        client-id: gateway-console
        client-secret: change-me
        # 或显式（无 discovery / 无 provider 猜测）：
        # authorization-uri: http://localhost:8081/realms/gateway/protocol/openid-connect/auth
        # token-uri:        http://localhost:8081/realms/gateway/protocol/openid-connect/token
        # jwk-set-uri:      http://localhost:8081/realms/gateway/protocol/openid-connect/certs
        # user-info-uri:    http://localhost:8081/realms/gateway/protocol/openid-connect/userinfo
        role-claim: realm_access.roles
        admin-role-values: CONSOLE_ADMIN,admin
```

Keycloak 客户端：Confidential、Standard flow、Valid redirect URI  
`http://localhost:8080/login/oauth2/code/console`（端口随管控台）。  
客户端角色 / realm role 与 `admin-role-values` 对齐。

### 17.6 通用 issuer（非 Keycloak）

优先填齐显式四 URI；或仅填 `issuer-uri` 走 discovery（运行环境须能访问 IdP）。  
**不要**依赖 Keycloak 路径猜测。

### 17.7 HTTPS（管控台，非代理 TLS）

`server.ssl.*` + `application-console-https-template.yml`（见 [OPS.md](OPS.md)）。  
协议代理客户端 TLS 仍用 `gateway.tls.*`，与管控台 HTTPS 无关。

### 17.8 自检

- [x] 显式 URI / keycloak / discovery 三路径；单测无外网
- [x] 角色映射可配置；缺省 VIEWER
- [x] LoginView SSO 按钮；成功 → `/console/`；logout 清 OIDC Session + 审计
- [x] OPS / README / STATUS 诚实：E2E 需真实 IdP
- [x] Prometheus / Oracle / 深度 TDS **本轮不做**

**结论：OIDC 可作为可激活 SSO 模式上线配置；完整联邦验收另开 IdP 联调。**

---

## 18. 审计 spool 内容浏览（管控台）

> 作者自检通过后实现。补齐 §14「只暴露状态、不把 spool 内容搬进表格」的遗留缺口：运维可在管控台**只读浏览**流量审计记录。  
> **不做** Prometheus、Oracle、深度 SQL Server TDS、OIDC 联调深化。

### 18.1 两类审计（勿混淆）

| 类型 | 存储 | API | UI |
|---|---|---|---|
| **管控操作审计** | H2 `gateway_console_audit` | 既有 `GET /console/api/audit?action=&limit=` | Ops「管控操作审计」Tab |
| **流量 / spool 审计** | `RecentTrafficRing` + `gateway.audit` spool 分段；可选 JDBC sink | **本轮** `GET /console/api/audit/spool`（别名 `/audit/records`） | Ops「流量 / spool 审计」Tab |

### 18.2 流量浏览 API

```text
GET /console/api/audit/spool?limit=50&before=&source=auto|ring|spool|jdbc&protocol=&operation=
→ {
    auditEnabled, destination, maskStatements, spoolDir, jdbcConfigured,
    source,          // 实际采用的来源
    entries: [{ ts, observedAt, protocolName, sessionId, sequence?, operation, statement, instanceId?, source, segment? }],
    count, limit, before?, nextBefore?,
    note, help
  }
```

| 项 | 约定 |
|---|---|
| 默认来源 `auto` | 审计启用且 spool 有数据 → spool；否则若 jdbc 有数据 → jdbc；否则内存环 |
| `ring` | 始终可读（进程内，重启丢失）；**不能**替代 spool |
| `spool` | 只读扫描 `gateway.audit.spool-dir` 下 `file-name.*` 分段；不改文件、不推进 ship offset |
| `jdbc` | 可选：当 `gateway.audit.jdbc.url` 已配时 `SELECT` 审计表；失败返回诚实空态 + note |
| 分页 | `limit`（默认 50，上限 200）+ `before`（epoch ms，**排他**上界）；响应 `nextBefore` 供「加载更早」 |
| 安全 | 语句截断（512）+ 可选 `SqlMasker`（跟随 `mask-statements`）+ 弱口令形态二次脱敏；**永不**回传 JDBC 密码 / 脱敏密钥 |
| 过滤 | 廉价 `protocol`（精确忽略大小写）、`operation`（子串） |

### 18.3 诚实边界

| 可读 | 仍仅 ship / 未做 |
|---|---|
| 内存环最近语句（lab 默认可看） | 外部 Prometheus / Grafana |
| 本地 spool 分段只读浏览 | 全文检索 / 跨节点聚合 |
| JDBC sink 表只读（需已建表且可连） | JDBC 真库集成验收（仍属集成范围） |
| 管控操作审计列表（既有） | 把 spool 当作合规归档 UI（归档仍靠 destination=jdbc 或外部搬移） |

### 18.4 UI

- Ops「运维 / 安全」页：**审计**区块 Tabs — 管控操作 / 流量·spool。
- 流量 Tab：来源下拉、协议/操作过滤、空态说明（审计关闭时提示开 `gateway.audit.enabled` 或改用内存环）。
- 中文文案；状态面板（§14）保留。

### 18.5 自检清单（作者）

- [x] 协议无关路径 `/console/api/audit/spool`（+ `/records` 别名）；无品牌前缀
- [x] 与 `GET /audit`（管控操作）分离；UI 双 Tab 标明
- [x] ring + spool 默认 lab 可用；jdbc 可选且失败诚实
- [x] 无密钥 / 无 JDBC 密码进 JSON；语句截断 + mask
- [x] CONSOLE_ARCHITECTURE §18 + STATUS / OPS / README 同步
- [x] `mvn test` + `npm run build` 绿

**结论：设计可通过 → 进入 §18 实现。**
