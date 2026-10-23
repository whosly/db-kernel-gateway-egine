# P1-5 补齐：decimal / 时间 / uuid 结果集脱敏（设计自检）

日期：2026-10-23（Asia/Shanghai）  
范围：MySQL + PostgreSQL 结果集脱敏路径；geometry / interval / money / timetz 仍 fail-closed。

## 现状

| 层 | 行为 |
|---|---|
| 规则 | `null` 全类别；`fixed`/`partial`/`hash`/`encrypt` 仅 `TEXT` |
| MySQL binary | `decimal` 可读（lenenc）但 encode 误拒「packed」；`date/time/datetime/timestamp` 可读（1-byte len）不可写；`geometry` 未知 |
| PG binary | text 族/int/float/bool 可写；`numeric`/时间/`uuid` 非空改写拒绝 |
| 分类 | PG `uuid` → `OTHER`（故意不让自由文本规则认领） |

## 根因（自检）

1. **MySQL NEWDECIMAL 线上是 ASCII lenenc 字符串**（官方 Binary Protocol Resultset），不是存储层 BCD。现有 `Packed decimal` 拒绝是误判 → **去掉特判即可非空改写**。
2. 时间 / PG numeric / uuid 需要**可复现的二进制编码器**；规则产出的仍是文本形态的 `MaskedValue`，由协议层编码（既有分层不变）。
3. 管控台策略要对 NUMERIC/TEMPORAL/uuid 生效，规则 `supportedCategories`/`matches` 必须扩；`encrypt` 仍仅 TEXT（密文前缀无法塞进 typed binary）。

## 方案

### A. 协议层 `*BinaryValues.encode`

**MySQL**
- `decimal`：与 varchar 相同 — lenenc(ASCII)；校验大致 `[+-]?digits[.digits]`（拒绝空/`***` 等）。
- `date`：解析 `YYYY-MM-DD` → `len=4` + year LE2 + month + day。
- `datetime`/`timestamp`：`YYYY-MM-DD[ HH:MM:SS[.ffffff]]` → len 4/7/11。
- `time`：`[-]H+:MM:SS[.ffffff]` → len 8/12（days=0 路径足够）。
- `geometry`：继续拒绝。

**PostgreSQL**
- `numeric`：标准 binary（ndigits/weight/sign/dscale + base-10000 digits），从十进制文本/`BigDecimal` 编码；NaN 可拒绝或单独支持。
- `date`：int4 days since 2000-01-01。
- `time`：int8 µs since midnight（不做 `timetz`）。
- `timestamp` / `timestamptz`：int8 µs since 2000-01-01 00:00:00；timestamptz 按 UTC/`Instant` 解析。
- `uuid`：16 字节（`UUID.fromString`）。
- `interval`/`money`/未知 OID：仍拒绝。

### B. 规则类别

- 新增 `ColumnMetadata.Category.UUID`；PG `uuid` OID 映射到它（测试从 OTHER 更新）。
- `FixedValueRule`：`TEXT | NUMERIC | TEMPORAL | UUID`（值合法性交给编码器 / text 直通）。
- `PartialMaskRule`：`TEXT | UUID`；UUID 保持 8-4-4-4-12 与连字符，只遮 hex。NUMERIC 的 partial 仅在 **TEXT 格式或 MySQL decimal ASCII** 有意义——为简单起见 partial **不**扩 NUMERIC（避免 `1***.56` 无法进 PG numeric binary）；操作员对金额用 `fixed`/`null`。
- `HashingRule`：`TEXT | UUID`；UUID 用 SHA-256 前 16 字节格式化为 UUID 字符串。
- `EncryptingRule`：仍仅 TEXT。
- `NullingRule`：不变（`EnumSet.allOf` 自动含 UUID）。

### C. 文本格式路径

不变：`replacement.bytes()` 直写。扩类别后 `fixed("0")` / `fixed("1970-01-01")` / `fixed("00000000-0000-0000-0000-000000000000")` 即可命中。

### D. 测试

- `MySQLBinaryValuesTest`：decimal/date/datetime/time 编码往返形态；geometry 仍拒。
- `PostgreSQLBinaryValuesTest`：numeric/date/time/timestamp/timestamptz/uuid 编码；未知仍拒。
- 规则单测：Fixed/Partial/Hash 对新类别；encrypt 不认 UUID。
- Interceptor：原「numeric binary 非空拒绝」改为「numeric-zero 成功改写」；补 uuid/date 一条。
- `mvn test` 全绿后更新 STATUS §4.2 / P1-5。

### E. 刻意不做

- geometry、interval、money、timetz、time with timezone、MySQL YEAR 已有 fixed-width。
- 不把 binary 改成 text format。
- 不做 KMS；不改加密线格式。

## 自检结论

分层清晰、fail-closed 保留、MySQL decimal 修正有协议依据、类别扩展避免 `***` 写入 uuid。**通过 → 编码。**
