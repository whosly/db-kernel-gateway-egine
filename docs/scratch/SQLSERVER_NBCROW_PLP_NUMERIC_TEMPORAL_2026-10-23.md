# SQL Server P2：NBCROW / PLP / 数值·时间非空改写（设计自检）

> **落地状态（2026-10-23）**：**已实现**（本提交）。现行边界以 [`STATUS_AND_GAPS.md`](../STATUS_AND_GAPS.md) §4.2 / P2-7 为准。

日期：2026-10-23（Asia/Shanghai）  
范围：TDS `TABULAR_RESULT` 脱敏路径；对照 [MS-TDS] NBCROW / PLP / DECIMALN / DATEN·TIMEN·DATETIME2N·DATETIMEOFFSETN。  
基线：`fdb9ec4` — ROW 仅 text/guid 非空；NBCROW/PLP/数值时间非空 fail-closed。

## 现状（改前）

| 项 | 行为 |
|---|---|
| ROW text/guid | 非空可改写；BYTE_LEN/USHORT_LEN/PLP_NULL 可置 NULL |
| NBCROW | 有规则 → 整消息 DENY |
| PLP 非空 | `writePlp` 拒绝；读路径已能拼 chunk |
| INT/FLOAT/DECIMAL/DATE/TIME/DATETIME2/… | `supportsNonNullRewrite=false` → DENY |
| TEXTPTR / XML / UDT / MONEY | 继续拒绝 |

## 根因

1. NBCROW 只是「位图 + 省略 NULL 单元格」的 ROW；COLMETADATA 已知时位图长度确定，可安全解析/回写。
2. PLP 非空可写成 `ULONGLONGLEN + 单 chunk + TERMINATOR`（已知长度）；未知长度读入后同样可回写为已知长度。
3. 数值/时间与 MySQL/PG 相同分层：规则产出 UTF-8 文本常量，协议层 `TdsBinaryValues` 编成 TYPE_VARBYTE；编不出则 DENY。

## 方案

### A. `TdsRowCodec` NBCROW

- `tryParseNbc`：`ceil(n/8)` 位图；bit1=NULL（无单元格）；bit0=按 COLUMN TYPE_VARBYTE 读（复用 `readValue`）。
- `encodeNbc`：按 masked null 重算位图，只写非空单元格。
- FIXED 列不可 `supportsNullRewrite`（与 ROW 一致）；NBCROW 位图仍可表示后端送来的 FIXED NULL（透传/观测），规则置 NULL 仍拒。

### B. PLP 非空

- `writePlp`：非空 → LE u64 总长 + u32 chunkLen + bytes + u32 0；空串允许（总长 0 + terminator）。
- 仅 text 族（nvarchar/varchar/nchar/char，含 MAX）`supportsNonNullRewrite`；VARBINARY(MAX)/XML 仍拒。

### C. `TdsBinaryValues`

非空可写：

| 类型 | 规则文本 → wire |
|---|---|
| INT1/2/4/8、INTN | 十进制整数 → LE；INTN 宽度=COLMETADATA maxLen |
| FLT4/8、FLOATN | 十进制浮点 → IEEE LE |
| BIT/BITN | 0/1/true/false → 1 byte |
| DECIMALN/NUMERICN | 十进制 → sign + LE 整数×10^scale；宽度按 precision 4/8/12/16 |
| DATEN | `YYYY-MM-DD` → 3B days since 0001-01-01 |
| TIMEN | `HH:MM:SS[.f]` → 3/4/5B ticks（10^-scale s） |
| DATETIME2N | 日期时间 → time\|\|date |
| DATETIMEOFFSETN | 带偏移 → datetime2\|\|int16 minutes |
| DATETIMETYPE / DATETIM4 / DATETIMN | 1900 纪元 days + 分数/分钟 |

刻意拒绝：MONEY*、TEXTPTR、XML/UDT/TVP、VARBINARY 非空、未知 type。

### D. 拦截器

- 去掉「见 NBCROW 即 DENY」；在 `rewriteTabularPayload` 与 ROW 并列改写 NBCROW。
- `toRuleValue`：数值/时间 decode→UTF-8 文本（便于规则/日志）；text/guid 不变。
- `supportsNonNullRewrite` 扩到上表 + PLP text。

### E. 测试

- `TdsBinaryValuesTest`：decimal/date/time/datetime2/int 往返；非法文本拒。
- `TdsRowCodecTest`：NBCROW 位图 null+值；PLP 非空 round-trip。
- `SqlServerResultSetMaskingInterceptorTest`：NBCROW fixed 改写成功；decimal/date ROW 改写；非法仍 DENY。
- Extractor：已知 COLMETADATA 时跳过完整 NBCROW body（可选加固）。

### F. 刻意不做

- Login7 驱动选路、TEXTPTR 深改、MONEY、真库 Docker 冒烟（本环境无 Engine）。
- 不把 NBCROW 强制降级成 ROW（保 token 形态）。

## 自检结论

分层与 MySQL/PG 一致；NBCROW/PLP 有 MS-TDS 依据；不确定布局仍 DENY。**通过 → 编码。**
