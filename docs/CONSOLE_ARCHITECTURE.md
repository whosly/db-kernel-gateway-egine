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
| C8 | 鉴权本阶段不做 | 生产靠网络隔离 / 反代；文档标明风险 |

**本阶段非目标**：Auth/SSO、SQL Server 深度能力、独立 Node 生产部署、按品牌插件市场 UI。

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

**未来列脱敏 / 列加密（不在本轮实现 UI）**

- 挂点：已有 `masking` / traffic observer 管线，按 **实例** 绑定规则，而不是按 DB 品牌页面。
- 管控台后续增加「实例 → 安全策略」页即可，不改协议无关模型。

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

| Phase | 内容 | 依赖 |
|---|---|---|
| **A（当前·联调闭环）** | Vue/TS/Vite SPA；总览聚合；抽屉；**H2 持久化创建/删除 + 启停**；FE↔BE↔代理端口联调 | H2 + Runtime |
| **B** | 控制面密码加密 / 可外置 DB；操作审计 | 密钥与合规 |
| **C** | 鉴权；只读令牌；操作审计进 spool | Spring Security |
| **D** | 可选独立前端部署（CDN + API 网关）；BFF | 运维需求 |
| **E** | 可观测图表（时序）；接 Micrometer | 指标后端 |

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

