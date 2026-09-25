# Keycloak 实验室（管控台 OIDC SSO）

> 非生产。本目录供 Mac Docker Desktop / Linux Engine 使用。  
> 仓库 CI / 无 Docker 的开发机 **不**要求启动 Keycloak；默认 `mvn test` 不依赖 Docker。

## 一路径（推荐）

见仓库根 [`docs/OPS.md`](../../docs/OPS.md) 小节 **「OIDC Keycloak 一路径」**。

摘要：

```bash
# 1) IdP
docker compose -f docker-compose.keycloak.yml up -d
# 等待 http://localhost:8081 可开（realm gateway 已 import）

# 2) 网关（本机 JDK 17）
cp src/main/resources/application-oidc-keycloak-template.yml \
   src/main/resources/application-dev.yml   # gitignore
# 按需改 client-secret（默认与 realm JSON 一致：change-me）
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 3) 浏览器
# http://localhost:8080/console/login → 「使用 SSO 登录」
# 账号 console-admin / admin  → CONSOLE_ADMIN
# 账号 console-viewer / viewer → CONSOLE_VIEWER
```

## 导入内容

| 项 | 值 |
|---|---|
| Realm | `gateway` |
| Client | `gateway-console`（Confidential，Standard flow） |
| Secret | `change-me` |
| Redirect URI | `http://localhost:8080/login/oauth2/code/console` |
| Web origins | `http://localhost:8080` |
| Realm roles | `CONSOLE_ADMIN` / `CONSOLE_VIEWER` |
| Token claims | `realm_access.roles` + flat `roles`（ID / access / userinfo） |

重新导入：`docker compose -f docker-compose.keycloak.yml down && docker compose -f docker-compose.keycloak.yml up -d`（无持久卷）。

## 真联调诚实边界

若当前环境 **没有 Docker**，只能验证配置模板 / ClientRegistration 单测 / 文档；  
完整浏览器 SSO 须在有 Docker 的机器上按上述步骤验收。
