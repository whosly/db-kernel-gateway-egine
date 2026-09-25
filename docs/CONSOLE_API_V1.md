# 管控台 Console API v1 契约

> 分支：`future/database-wire-protocol-foundation`  
> 状态：**已实现（与 Vue console-ui 同变更；仅 /console/api/v1）**  
> 关联：[`CONSOLE_ARCHITECTURE.md`](CONSOLE_ARCHITECTURE.md) §2.4；领域摘要见 [`CONSOLE_DESIGN.md`](CONSOLE_DESIGN.md)

---

## 0. 目标与非目标

| ID | 约束 |
|---|---|
| A1 | 基准路径 **`/console/api/v1`**（版本化）；SPA 静态仍为 **`/console`**（不版本化） |
| A2 | 资源路径用 **名词复数**；非 CRUD 动词统一 **`/actions/{action}`** |
| A3 | **协议无关**：禁止 `/v1/mysql/...`、`/v1/postgresql/...` 等品牌路径 |
| A4 | JSON：列表 `{items,total}`；单资源直接返回对象；错误 **RFC 7807** `application/problem+json` |
| A5 | 启停等生命周期动作成功返回 **更新后的 `GatewayInstance`**（200）；失败 Problem |
| A6 | 响应 **永不含密码**；仅 `passwordConfigured` |
| A7 | **仅 v1**：取消未版本化 `/console/api/*` 业务路径（同变更更新 Vue）；不做长期双轨 |
| A8 | Java 17；SPI 依赖倒置保持不变；不实现 Prometheus/Oracle 新能力 |

**非目标**：Prometheus 抓取端点、Oracle 专属能力、长期 unversioned 兼容层、品牌分叉 API。

---

## 1. 路径总表（旧 → 新）

基准：旧 `/console/api` → 新 **`/console/api/v1`**。

### 1.1 总览 / 目录 / 健康 / 配置

| 方法 | 旧路径 | 新路径 | 成功体 |
|---|---|---|---|
| GET | `/supported-databases` | **`/databases`** | `{ items: CatalogEntry[], total }` |
| GET | `/overview` | `/overview` | 聚合对象（内嵌 `instances`/`databases` 数组，非 list envelope） |
| GET | `/health` | `/health` | 进程健康对象 |
| GET | `/config/summary` | **`/config`** | 非密钥配置摘要 |
| GET | `/config/export` | `/config/export` | 可下载配置 JSON |

### 1.2 实例 CRUD 与集合操作

| 方法 | 旧路径 | 新路径 | 成功体 |
|---|---|---|---|
| GET | `/instances` | `/instances` | `{ items, total, byStatus? }` |
| POST | `/instances` | `/instances` | `GatewayInstance`，**201** + `Location: /console/api/v1/instances/{id}` |
| GET | `/instances/{id}` | `/instances/{id}` | `GatewayInstance` |
| PUT | `/instances/{id}` | `/instances/{id}` | `GatewayInstance` |
| DELETE | `/instances/{id}` | `/instances/{id}` | `{ ok, message }`（删除成功确认；无资源可回） |
| GET | `/instances/{id}/status` | `/instances/{id}/status` | 状态对象 |
| GET | `/instances/{id}/metrics` | `/instances/{id}/metrics` | 指标对象 |
| GET | `/instances/export` | `/instances/export` | `{ items, total }`（无密码） |
| POST | `/instances/import` | `/instances/import` | 导入结果 `{ ok, created, skipped, failed, … }` |
| POST | `/instances/bulk` | **`/instances/bulk-actions`** | `{ action, results, okCount, failCount, ok }` |

### 1.3 实例动作（统一 `/actions/{action}`）

| 方法 | 旧路径 | 新路径 | 成功体 |
|---|---|---|---|
| POST | `/instances/{id}/start` | **`/instances/{id}/actions/start`** | `GatewayInstance`（200） |
| POST | `/instances/{id}/stop` | **`/instances/{id}/actions/stop`** | `GatewayInstance`（200） |
| POST | `/instances/{id}/clone` | **`/instances/{id}/actions/clone`** | `GatewayInstance`（201 + Location） |
| POST | `/instances/{id}/health-check` | **`/instances/{id}/actions/health-check`** | HealthCheck 结果对象 |
| GET | `/instances/{id}/health-check` | **`/instances/{id}/actions/health-check`** | 同上（只读探测） |
| POST | `/instances/{id}/masking-rules/reload` | **`/instances/{id}/actions/reload-masking-rules`** | `{ ok, message, … }` |

`{action}` 取值（本版）：`start` | `stop` | `clone` | `health-check` | `reload-masking-rules`。

### 1.4 嵌套资源：脱敏 / 会话 / 语句 / Schema

| 方法 | 旧路径 | 新路径 | 成功体 |
|---|---|---|---|
| GET/POST | `/instances/{id}/masking-rules` | 同相对路径 | GET → `{ items, total }`；POST → 规则对象 |
| PUT/DELETE | `/instances/{id}/masking-rules/{ruleId}` | 同 | 规则对象 / `{ ok, message }` |
| PUT | `/instances/{id}/masking-rules` | 同 | 全量替换 → `{ items, total }` |
| GET | `/instances/{id}/sessions` | 同 | `{ items, total }`（可保留 `instanceId`） |
| DELETE | `/instances/{id}/sessions/{connectionId}` | 同 | `{ ok, message }` |
| GET | `/instances/{id}/recent-statements` | 同 | `{ items, total }` |
| GET | `/instances/{id}/schema/catalog` | 同 | catalog 对象 |
| GET | `/instances/{id}/schema/columns` | 同 | columns 对象 |

### 1.5 SQL 执行 / 历史 / 片段

| 方法 | 旧路径 | 新路径 | 成功体 |
|---|---|---|---|
| POST | `/instances/{id}/sql/execute` | **`/instances/{id}/sql/executions`** | 执行结果对象 |
| POST | `/instances/{id}/sql/cancel` | **`/instances/{id}/sql/executions/cancel`** | 取消结果（body 含 `executionId`） |
| POST | `/sql/executions/{executionId}/cancel` | `/sql/executions/{executionId}/cancel` | 取消结果 |
| GET/DELETE | `/sql/history` | `/sql/history` | GET → `{ items, total }`；DELETE → `{ ok, deleted }` |
| GET/POST | `/sql/snippets` | `/sql/snippets` | GET → `{ items, total }`；POST → snippet |
| PUT/DELETE | `/sql/snippets/{id}` | 同 | snippet / `{ ok, id }` |

### 1.6 审计 / 指标 / 安全 / 风控

| 方法 | 旧路径 | 新路径 | 成功体 |
|---|---|---|---|
| GET | `/audit` | **`/audit/operations`** | `{ items, total }` |
| GET | `/audit/status` | `/audit/status` | 状态对象 |
| GET | `/audit/spool`、`/audit/records` | **`/audit/traffic`** | `{ items, total, … }`（`records` 别名废弃） |
| GET | `/metrics/history` | `/metrics/history` | 时序对象 |
| GET/POST… | — | `/alerts/thresholds` | 告警阈值 CRUD `{ items, total }` |
| GET | — | `/alerts/active` · `/alerts` | 当前触发（进程内评估） |
| GET/PUT/DELETE | `/security/masking-key` | 同 | 富状态：`configured`/`activeKeyId`/`source`/`previousKeyIds`/`encryptRulesCanBind`/`encryptRulesWithoutKey`；PUT body 可含 `previousKeyId`/`keepPrevious` |
| POST | — | **`/security/masking-key/verify`** | `{ ok: true, keyId, message }`（不回传密钥/密文） |
| GET | `/security/secret-encryption` | 同 | 状态对象 |
| GET/PUT | `/risk-policy` | `/risk-policy` | 策略对象 |

### 1.7 认证

| 方法 | 旧路径 | 新路径 |
|---|---|---|
| GET | `/console/api/auth/mode`（及 `/status`） | **`/console/api/v1/auth/mode`**（保留 `/status` 别名） |
| GET | `/console/api/auth/me` | `/console/api/v1/auth/me` |
| POST | `/console/api/auth/login` | `/console/api/v1/auth/login` |
| POST | `/console/api/auth/logout` | `/console/api/v1/auth/logout` |

OIDC 入口仍为 Spring 标准 `/oauth2/authorization/{registrationId}`（非 console api 版本树）。

---

## 2. JSON 约定

### 2.1 错误：RFC 7807 Problem Details

`Content-Type: application/problem+json`

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Unknown gateway instance id: foo",
  "code": "BAD_REQUEST",
  "instance": "/console/api/v1/instances/foo"
}
```

| 字段 | 说明 |
|---|---|
| `type` | URI；本阶段固定 `about:blank`（后续可挂文档锚点） |
| `title` | 与 HTTP 状态短名一致 |
| `status` | HTTP 状态码 |
| `detail` | 人类可读说明（中文或英文，与既有异常消息一致） |
| `code` | 机器码：`BAD_REQUEST` / `NOT_FOUND` / `UNAUTHORIZED` / `FORBIDDEN` / `CONFLICT` / `BAD_GATEWAY` / `SERVICE_UNAVAILABLE` / `INTERNAL_ERROR` 等 |
| `instance` | 发生问题的请求路径（request URI） |

映射：`@RestControllerAdvice` 覆盖 `IllegalArgumentException`→400、`IllegalStateException`→503、`SchemaConnectException`→502；未知实例类消息可用 `NOT_FOUND`(404)。Security 入口（401/403）与 Token Filter 同步改为 Problem。

### 2.2 列表

```json
{ "items": [ … ], "total": 2, "byStatus": { "RUNNING": 1, "STOPPED": 1 } }
```

- 主集合字段固定为 **`items` + `total`**。
- 分面（`byStatus`、过滤回显等）作 **兄弟字段**，不得与 `items` 混用旧名 `instances`/`entries`/`rules`/`sessions`/`snippets`/`databases`/`count`。
- **例外**：`GET /overview` 为聚合资源，内嵌数组仍用领域名 `instances` / `databases`（不是 list endpoint）。

### 2.3 单资源

- GET/PUT：直接返回资源对象。
- POST 创建：资源对象 + **201** + `Location`。
- 禁止 `{ ok: true, instance: … }` 套娃（动作失败走 Problem）。

### 2.4 动作（启停）

- **成功**：返回更新后的 `GatewayInstance`（200）。
- **失败**：Problem（不返回 `{ ok: false }`）。
- `bulk-actions` / `import` / `health-check` / `reload-masking-rules` / SQL execute：返回各自业务结果对象（可含 `ok` 字段表示部分成功语义）。

### 2.5 枚举与密钥

- 实例 `status`、动作名等：**大写**枚举字符串（既有 `RUNNING` 等保持）。
- `proxyMode`：`GATEWAY` | `TRANSPARENT` | `UNSUPPORTED`；`proxyModeLabel` 可保留中文。
- 禁止响应出现 `password` / `targetPassword`；仅 `passwordConfigured: boolean`。

### 2.6 类型化 DTO

优先 `record`/类：Problem、ListEnvelope、热点路径（instances 列表、SQL execution 请求体、错误）。内部仍可用 `Map` 过渡；公共契约字段名以上表为准。

---

## 3. 安全与 CSRF

- Matcher 全部切到 `/console/api/v1/**`；auth 放行：`/console/api/v1/auth/login|me|mode|status`。
- Logout URL：`/console/api/v1/auth/logout`。
- Token Filter：仅拦截 `/console/api/v1/**`；auth 前缀 `/console/api/v1/auth/` 放行；401 体为 Problem。
- CSRF：form/oidc 模式下对 API 写操作仍校验；login POST 可 ignore。

---

## 4. 前端

- `console-ui/src/api/client.ts`：`BASE = '/console/api/v1'`。
- `consoleApi.ts` + `types.ts`：路径与 `items`/`total`、Problem 错误解析（`detail` 优先于 `message`）。
- Vite proxy：`/console/api` → 后端（前缀仍匹配 v1）。
- 视图：列表取值改为 `body.items`。

---

## 5. 破坏性变更说明（运维 / Mac 本地）

**Breaking**：所有管控台 HTTP 客户端必须改用 `/console/api/v1/...`；旧路径不再提供业务处理（无长期双轨）。  
同版本 **Vue console-ui 已同步**；仅升级 jar、仍缓存旧前端或自建脚本调旧路径会失败。

建议：清浏览器缓存 / 硬刷新 `/console/`；更新 curl/脚本与 `VITE_` 代理配置；重新 `mvn package` 使静态资源进 jar。

---

## 6. 作者自检清单

- [x] 全量旧端点均有新路径映射；无品牌路径
- [x] 动作统一 `/actions/{action}`；bulk → `bulk-actions`；sql execute → `sql/executions`
- [x] 列表 `items/total`；错误 Problem；启停回 `GatewayInstance`
- [x] Auth 纳入 `/v1/auth`；SPA `/console` 不版本化
- [x] 仅 v1 + FE 同变更；SPI/协议层不动
- [x] 文档中文；实现后 `mvn test` + `npm run build` 必须绿

