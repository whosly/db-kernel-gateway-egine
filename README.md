# 数据库内核网关引擎

这是一个 Java 17 + Spring Boot 实现的多协议数据库网关项目，目标是在网关层接入
数据库客户端协议，并把请求转发到后端数据库。

当前仓库正在建设 MySQL Client/Server Protocol 与 PostgreSQL
Frontend/Backend Protocol 的完整协议透明代理。第一阶段以 MySQL 当前官方稳定
Client/Server Protocol 文档和 PostgreSQL 当前稳定 Frontend/Backend Protocol 为
协议基线，由目标数据库完成真实认证、TLS/SSL/GSS、压缩、prepared statement、
binary protocol 和 extended query 等协议语义。

## 当前状态

- 已有 Spring Boot 启动入口、网关配置、协议适配器基础接口。
- 已有 MySQL 与 PostgreSQL 适配器包，以及共享协议抽象包。
- 已有 MySQL packet codec、PostgreSQL message codec、协议会话状态和错误映射的
  单元测试基础。
- SQL 解析使用 Alibaba Druid。
- 当前只允许配置 `mysql` 或 `postgresql`；不支持的协议必须快速失败，不能映射到
  其他适配器占位。

## 文档入口

- [项目需求文档](docs/PROJECT_REQUIREMENTS.md)：编码前确认完整协议代理目标、范围和验收标准。
- [系统设计文档](docs/SYSTEM_DESIGN.md)：编码前确认透明代理架构、opaque tunnel、模块边界和测试策略。
- [协议参考表](docs/PROTOCOL_REFERENCE_TABLES.md)：MySQL capability、PostgreSQL OID、网关自身错误映射。
- [项目概览](docs/PROJECT_OVERVIEW.md)：目标、架构、配置和扩展边界。
- [数据库协议规则](docs/rules/database-protocol-rules.md)：开发数据库 wire
  protocol 前必须遵守。
- [AI Coding 错误处理规则](docs/rules/ai-error-handling-rules.md)：遇到失败时的
  自动处理和沉淀规则。
- [仓库规则](AGENTS.md)：面向 coding agent 的仓库入口规则。

## 快速开始

环境要求：

- Java 17 或兼容的更高版本 JDK。
- Maven 3.6+。
- 如需运行真实代理链路，需要本地或远端 MySQL/PostgreSQL 后端数据库。

运行测试：

```bash
mvn test
```

构建：

```bash
mvn clean package
```

启动：

```bash
java -jar target/db-kernel-gateway-egine-1.0.0-SNAPSHOT.jar
```

## 配置

主要配置位于 `src/main/resources/application.yml`：

```yaml
gateway:
  proxy-db-type: postgresql
  proxy-port: 5433
  target:
    host: localhost
    port: 5432
    username: postgres
    password: change-me
    database: demo
```

开发和提交时不要把真实密码、认证 payload、token 或带凭据的连接串写入日志或文档。

## 协议开发约束

修改协议代码前必须先阅读 `docs/rules/database-protocol-rules.md`。第一阶段以
MySQL 和 PostgreSQL 完整协议透明代理为主，新增协议需要先定义自己的
frame/message codec、状态机、认证/加密/压缩转发模型、结果流、错误格式和测试边界。

包边界：

- `com.whosly.gateway.adapter.protocol`：共享协议抽象。
- `com.whosly.gateway.adapter.mysql`：MySQL 专属协议代码。
- `com.whosly.gateway.adapter.postgresql`：PostgreSQL 专属协议代码。
- `com.whosly.gateway.parser`：SQL 解析。
- `com.whosly.gateway.service`：后端数据库连接与执行服务。

## 测试

协议变更必须先补或更新聚焦测试，至少覆盖相关 codec/observer、状态迁移、
opaque tunnel 切换、双向 relay、网关自身错误映射和真实客户端代理链路。单元测试不
依赖真实数据库；`mysql`、`psql`、JDBC 与真实后端数据库验证归类为集成测试。
