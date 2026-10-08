# 运维手册（开启清单 / 告警清单）

> 与 [`STATUS_AND_GAPS.md`](STATUS_AND_GAPS.md) 对齐：只写本分支已具备的运维面。  
> 网关默认 **自动启动协议代理**（`Application` 生命周期）；交互式 stdin CLI **默认关闭**。

## 开启清单

| 步骤 | 做什么 | 配置 / 命令 |
|---|---|---|
| 1 | JDK 17 + 复制模板 | `cp src/main/resources/application-*-template.yml src/main/resources/application-dev.yml` |
| 2 | 填目标库（勿提交密码） | `gateway.target.*`、`gateway.proxy-port`、`gateway.proxy-db-type` |
| 3 | 启动（非交互） | `mvn spring-boot:run -Dspring-boot.run.profiles=dev` 或 `java -jar …` |
| 4 | 确认代理在听 | 日志含 protocol adapter started；或 `GET /gateway/status` |
| 5 | （可选）审计 | `gateway.audit.enabled=true`；生产建议独立盘与 `destination` |
| 6 | （可选）明文强制 | 需审计时设 `gateway.require-cleartext-inspection=true`（或随审计默认） |
| 6b | （可选）TLS 终止 | `gateway.tls.enabled=true` + `keystore-path`（PKCS12/JKS）；客户端从首字节 TLS；密码用环境变量 |
| 6c | （可选）后端连接池 | `gateway.pool.enabled=true`；可选 `gateway.pool.reset-mode=protocol`（MySQL COM_RESET_CONNECTION / PG DISCARD ALL）；默认 `none` |
| 6d | （可选）按库/用户路由 | `gateway.routing.enabled=true` + `rules`（`match-database` / `match-username` + `endpoints`）；**PG** cleartext Startup 首连生效；**MySQL** 首连仍 fallback（server-first）；未命中回退 `target`/`backend-endpoints`；默认关 |
| 7 | （可选）风控拒绝清单 | `gateway.risk.denied-operations` / `denied-statement-keywords` |
| 8 | （可选）交互 CLI | 仅调试：`gateway.cli.interactive=true`（会读 `System.in`） |
| 9 | （可选）控制面密码强制加密 | 生产：`GATEWAY_CONSOLE_SECRET_KEY_BASE64` + `GATEWAY_CONSOLE_REQUIRE_SECRET_ENCRYPTION=true`；缺钥时创建/更新带密码 → **503**，无静默明文。实验室默认关。迁移见 [`CONSOLE_ARCHITECTURE.md`](CONSOLE_ARCHITECTURE.md) §12.2 |

### HTTP / Actuator 出口（P2-3 / P2-4）

| 路径 | 用途 |
|---|---|
| `GET /gateway/status` | 是否运行、协议、端口、会话数 |
| `GET /gateway/metrics` | 内存计数器（接受/拒绝/opaque/failover/policy） |
| `POST /gateway/start` / `POST /gateway/stop` | 启停代理（进程仍在） |
| `GET /actuator/gateway` | 同上摘要（嵌套 `metrics`） |
| `GET /actuator/health` | Spring Boot 健康检查 |

计数器进程内、非持久；重启清零。未接 Micrometer 远程后端。

## 告警清单（建议阈值）

| 信号 | 来源 | 建议 |
|---|---|---|
| `connectionsRejectedLimit` 持续上升 | `/gateway/metrics` | 调大 `max-connections` 或扩容；查客户端风暴 |
| `connectionsRejectedPolicy` 上升 | 同上 | 核对 `allowed-client-cidrs` |
| `opaqueTunnelsDenied` 上升 | 同上 | 客户端在走 TLS/压缩而 `require-cleartext-inspection` 开启 |
| `opaqueTunnelsEntered` 高且未 deny | 同上 | 明文观测/审计失效窗口——是否应强制明文 |
| `backendFailovers` 突发 | 同上 | 主后端不可达或冷却中；查目标与 `backend-endpoints` / `gateway.routing.rules` |
| `policyDenials` 上升 | 同上 | 风控命中；核对 deny 清单是否过宽 |
| 审计 spool 写失败 / 熔断 | 应用日志 | fail-closed 会拒流量；查磁盘配额与权限 |
| 代理端口起不来 | 启动日志 | 端口占用或配置错误 |

## 管控台告警阈值（进程内）

- 阈值 CRUD：`/console/api/v1/alerts/thresholds`
- 当前触发：`GET /console/api/v1/alerts/active`（亦 `/alerts`）
- 评估：复用 `MetricsHistorySampler` tick + 读接口懒评估；指标来自进程内环（与总览火花图同源）
- UI：侧栏「告警」；总览有触发徽章
- **诚实**：H2 持久化阈值与 `last_fired`；**不是** Prometheus / Alertmanager / PagerDuty

## 集成测试（P2-6 跳过策略）

| 命令 | 行为 |
|---|---|
| `mvn test` | surefire **排除** `*IntegrationTest`；默认单元全绿，**不需要 Docker** |
| `mvn -Pintegration-test test` 且无 local props | 跑 IntegrationTest，但 `integration.enabled=false` → **`assumeTrue` 跳过**（Skipped，不失败） |
| 真跑集成 | 建 `src/test/resources/integration-test-local.properties`（gitignore）：`integration.enabled=true` + 可达 MySQL/PG |
| `-Pintegration-testcontainers` | **占位 stub**（pom 属性）；尚未接线 Testcontainers，勿当已实现 |

细节见 `src/test/resources/integration-test.properties` 注释。

## JDBC 旁路说明（P2-2）

- **数据平面**：客户端 wire → adapter → `BackendProvider` → 目标库 socket。
- `DatabaseConnectionService`（DriverManager）已 **@Deprecated**，**不参与**转发；勿与透明代理混淆。
- 审计 `destination=jdbc` 是独立目的库，与被代理库分离。


## SQL Server 本地实验室（可选）

> 计划与阶段见 [`SQLSERVER_TDS_PLAN.md`](SQLSERVER_TDS_PLAN.md)。P0 透明中继 + P1-lite 明文 Login7/SQL_BATCH 观测；**勿**把生产 SA 密码写入已提交文件。
>
> 管控台 SQL 工作台：实例 RUNNING 后，JDBC 经网关 **31433**（或实例 listenPort）执行；依赖 `mssql-jdbc`（默认已打包）。

| 项 | 值 |
|---|---|
| 模板 | `application-sqlserver-template.yml` → 复制为 `application-dev.yml`（gitignore） |
| 网关端口 | **31433** |
| 目标端口 | **1433** |
| 模板密码 | `change-me`（占位） |
| 本地 Docker 示例 SA | `Aa123456.`（含末尾点号；与本机 MySQL/PG lab 习惯一致） |
| 生产 | 另行配置；环境变量 / 密钥管理，**不**提交 git |

若 SA 复杂度策略拒绝 `Aa123456.`，可改用 `Aa123456!` 并只改本地 `application-dev.yml`。本仓库执行环境默认 **不**自动 `docker run`。


## 管控台鉴权与 HTTPS（实验室）

| 模式 | 配置 | 说明 |
|---|---|---|
| open | `gateway.console.auth.mode=open`（或未设且无 token） | lab 默认，API 开放 |
| token | `mode=token` 或配置 `api-token`/`read-token` | Bearer / X-Console-Token；read-token≈CONSOLE_VIEWER |
| form | `mode=form` + `gateway.console.auth.users` | Session + Cookie CSRF；默认用户 admin/admin、viewer/viewer（若未配置 users） |
| oidc | `mode=oidc` + `auth.oidc.issuer-uri` + client-id/secret | 可激活 OAuth2 Login；显式 URI 或 `provider=keycloak` 或 issuer discovery；**E2E 需真实 IdP** |

### OIDC Keycloak 一路径（Mac Docker Desktop）

> 本仓库执行环境可能 **无 Docker**；Compose 供你的 Mac / 有 Engine 的机器使用。  
> 默认 `mvn test` **不**需要 Docker / 不启 Keycloak。

```bash
# 1) 启动 IdP（realm gateway + client gateway-console 自动 import）
docker compose -f docker-compose.keycloak.yml up -d
# 管理台 http://localhost:8081/  → admin / admin
# 就绪后 realm：http://localhost:8081/realms/gateway/.well-known/openid-configuration

# 2) 网关切到 OIDC（JDK 17；application-dev.yml 已 gitignore）
cp src/main/resources/application-oidc-keycloak-template.yml \
   src/main/resources/application-dev.yml
# 已含：mode=oidc、provider=keycloak、issuer/client/secret、role-claim=realm_access.roles
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 3) 浏览器
# 打开 http://localhost:8080/console/login
# 点「使用 SSO 登录」→ Keycloak 登录
#   console-admin / admin   → CONSOLE_ADMIN
#   console-operator / operator → CONSOLE_OPERATOR（可选）
#   console-viewer / viewer → CONSOLE_VIEWER
# 成功落到 /console/
```

相关文件：`docker-compose.keycloak.yml`、`deploy/keycloak/realm-gateway.json`、`deploy/keycloak/README.md`、`application-oidc-keycloak-template.yml`。

**STATUS**：实验室 Compose + 文档就绪；真联调在有 Docker 的机器上。本环境若无 Docker，仅验证配置 / beans / 单测。

### OIDC / SSO（实验室）

登录 URL：`/oauth2/authorization/console`（registration-id 可改）。成功回 `/console/`。  
`GET /console/api/auth/status`（或 `/mode`）在 `oidc=true` 时返回 `ssoLoginUrl`。

**Keycloak 示例**（路径默认，无需 discovery）：

```yaml
gateway.console.auth.mode: oidc
gateway.console.auth.oidc.provider: keycloak
gateway.console.auth.oidc.issuer-uri: http://localhost:8081/realms/gateway
gateway.console.auth.oidc.client-id: gateway-console
gateway.console.auth.oidc.client-secret: change-me
gateway.console.auth.oidc.role-claim: realm_access.roles
```

**通用 IdP**（推荐显式 endpoint，单元测试同此，无外网）：

```yaml
gateway.console.auth.oidc.issuer-uri: https://idp.example/realms/lab
gateway.console.auth.oidc.authorization-uri: https://idp.example/.../auth
gateway.console.auth.oidc.token-uri: https://idp.example/.../token
gateway.console.auth.oidc.jwk-set-uri: https://idp.example/.../certs
gateway.console.auth.oidc.user-info-uri: https://idp.example/.../userinfo
```

Redirect URI 登记：`{console-base}/login/oauth2/code/console`。角色 claim → `CONSOLE_ADMIN` / `CONSOLE_OPERATOR` / `CONSOLE_VIEWER`（缺省 VIEWER）；权限串见 [`CONSOLE_ARCHITECTURE.md`](CONSOLE_ARCHITECTURE.md) §22。OIDC 细节见 §17。

### 管控台 HTTPS（server.ssl，非 gateway.tls）

```bash
keytool -genkeypair -alias gateway-console -keyalg RSA -keysize 2048 -validity 3650 \
  -storetype PKCS12 -keystore ./data/console-lab.p12 -storepass changeit \
  -dname "CN=localhost,OU=lab,O=gateway,L=local,ST=lab,C=CN"
# 复制并编辑：src/main/resources/application-console-https-template.yml
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# https://localhost:8443/console/
```

协议代理客户端 TLS 终止仍用 `gateway.tls.*`，与管控台 HTTPS 无关。

## 管控台浏览流量审计（spool / 环）

- 状态（非密钥）：`GET /console/api/audit/status`
- **内容浏览**：`GET /console/api/audit/spool?limit=50&source=auto|ring|spool|jdbc`
  - lab 默认：`source=ring` 或 `auto` 回退到进程内最近语句（重启丢失）
  - 开启 `gateway.audit.enabled=true` 后可读本地 spool 分段；**不**推进 ship offset
  - `destination=jdbc` 且配置了 `gateway.audit.jdbc.url` 时可只读审计表（须已建表，见 `docs/sql/audit-sink-schema.sql`）
- 管控操作审计仍用：`GET /console/api/audit`（H2，与流量 spool 分离）
- UI：运维页「审计」Tabs

## 列加密（脱敏密钥）

1. 配置控制面主密钥：`GATEWAY_CONSOLE_SECRET_KEY_BASE64`（`openssl rand -base64 32`）。
2. 管控台 **运维 → 安全 · 脱敏密钥**：设置/轮换 AES 密钥（或 yaml `gateway.masking.key-base64`）。
3. 可选自检：`POST /console/api/v1/security/masking-key/verify`（不回传密钥/密文）。
4. 实例抽屉为列配置 `strategy=encrypt` → 经代理查询结果为 `enc:v1:<keyId>:…` 密文。
5. **诚实边界**：进程内 AES-GCM + 控制面密钥环；**不是**云 KMS/HSM。
