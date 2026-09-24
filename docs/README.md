# 文档索引

本目录是仓库文档的导航入口。实现细节以代码为准；规则描述「应当怎样」，
状态表描述「当前分支实际怎样」。

| 文档 | 用途 | 与代码的关系 |
|---|---|---|
| [../README.md](../README.md) | 产品说明、快速启动、配置与运维要点 | 面向使用者；能力声明须与本分支一致 |
| [../AGENTS.md](../AGENTS.md) | 仓库硬约束（分层、透明性、安全、工作约定） | 开发前必读 |
| [STATUS_AND_GAPS.md](STATUS_AND_GAPS.md) | **本分支功能现状与缺口（P0/P1/P2）** | 证据来自代码/测试；随实现更新 |
| [OPS.md](OPS.md) | **运维开启清单 / 告警清单 / 集成跳过策略** | 面向部署；与 STATUS P2 对齐 |
| [PROTOCOL_REFERENCE_TABLES.md](PROTOCOL_REFERENCE_TABLES.md) | 协议参考表镜像（能力位、命令码、错误码等） | **镜像**；真源是 `adapter.*` 枚举 |
| [rules/database-protocol-rules.md](rules/database-protocol-rules.md) | 数据库 wire protocol 规则 | 描述协议应当如何，不是现状清单 |
| [rules/ai-error-handling-rules.md](rules/ai-error-handling-rules.md) | 失败分类与修复流程 | 流程约束 |
| [sql/audit-sink-schema.sql](sql/audit-sink-schema.sql) | 审计 JDBC 目的端建表脚本 | `destination=jdbc` 时使用 |

## 阅读顺序建议

1. 使用者：`README.md` → 对应数据库模板 → 运维见 `OPS.md` → 需要时再看审计章节。
2. 协议开发：`AGENTS.md` → `rules/database-protocol-rules.md` → `PROTOCOL_REFERENCE_TABLES.md` → 代码。
3. 排期 / 评审：`STATUS_AND_GAPS.md`（先看 P0）。

## 图示

- `../assets/mysql-gateway-flow.gif`
- `../assets/postgresql-gateway-flow.gif`
- `../assets/demo-mysql-pg-gateway.gif`（可选：集成/构建 PASS 录屏）
