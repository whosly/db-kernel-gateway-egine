# SQL Server P2：TEXTPTR / MONEY* / XML·UDT·TVP / VARBINARY(MAX)（设计自检）

日期：2026-10-23（Asia/Shanghai）  
范围：TDS `TABULAR_RESULT` 脱敏深改写；对照 [MS-TDS] ROW TEXTPTR、MONEYN、PARTLENTYPE(XML/UDT)、PLP。  
基线：`9bd432b` — NBCROW/PLP text/numeric/temporal 已可改；下列仍 fail-closed。

## 现状（改前）

| 类型 | 行为 |
|---|---|
| MONEY / MONEY4 / MONEYN | `isEncodable=false` → 有规则 DENY |
| TEXT / NTEXT / IMAGE（TEXTPTR） | ROW 读返回 empty → DENY |
| VARBINARY / BINARY（含 MAX/PLP） | `supportsNonNullRewrite=false` → DENY |
| XML / UDT | COLMETADATA `UNSUPPORTED` → 整段元数据解析失败 |
| TVP / 未知 type | 继续拒绝 |

## 根因与协议依据

1. **MONEY***：FIXED 8 / FIXED 4 / BYTE_LEN(4|8)；值为 ×10000 的 LE 整数（scale 4）。与 decimal 同层：规则 UTF-8 文本 → `TdsBinaryValues`。
2. **TEXTPTR**：非空 = `B_VARBYTE textptr` + `8BYTE timestamp` + `LONGLEN` + data；NULL = `0xFFFFFFFF`（无 textptr/timestamp）。改写非空时合成 16B 零 textptr + 零 timestamp（结果集客户端只用 Data；textptr 供 UPDATETEXT，网关脱敏路径可接受）。
3. **VARBINARY(MAX)**：已是 PLP；与 text PLP 相同 chunk 回写；非 MAX 走 USHORT_LEN。规则层：扩展 `FixedValueRule` 认 BINARY（常量 UTF-8 字节作 payload）。
4. **XML**：TYPE_INFO = `SCHEMA_PRESENT` + 可选名；ROW = PLP，**载荷为 MS-BINXML**（非 UCS-2 文本）。非空改写无安全 BINXML 编码器 → **仅 PLP_NULL**。
5. **UDT**：TYPE_INFO = `MAX_BYTE_SIZE` + 名 + assembly；ROW = PLP（MS-SSCLRT）。非空改写会破坏反序列化 → **仅 PLP_NULL**。
6. **TVP / 未知**：无稳定结果集改写模型 → **整列/整元数据 fail-closed**。

## 方案

### A. MONEY*
- `TdsBinaryValues` 编解码 MONEYTYPE / MONEY4TYPE / MONEYN。
- `supportsNonNullRewrite` 纳入；FIXED 仍不可 NULL。

### B. TEXTPTR
- `TdsRowCodec` `readTextPtr` / `writeTextPtr`。
- TEXT/NTEXT → 文本规则；IMAGE → BINARY（fixed UTF-8 字节）。
- `supportsNonNullRewrite` / `supportsNullRewrite` 打开。

### C. VARBINARY / BINARY（含 MAX）
- `supportsNonNullRewrite`：BIGVARBIN / BIGBINARY（PLP 与否皆可）。
- `encodeNonNullBytes`：`value.bytes()`。
- `FixedValueRule` 增加 `Category.BINARY`。

### D. XML / UDT
- COLMETADATA 解析 XML_INFO / UDT_INFO，`plp=true`。
- `supportsNullRewrite=true`；`supportsNonNullRewrite=false`（文档写明 BINXML/CLR）。

### E. 刻意拒绝
- TVP、未知 type byte、XML/UDT **非空**、无法解析的 TEXTPTR 截断。

## 测试

- MONEY round-trip + interceptor fixed。
- TEXT/NTEXT TEXTPTR 非空/NULL；IMAGE fixed/null。
- VARBINARY(MAX) PLP fixed。
- XML/UDT：COLMETADATA 可解析；nulling 成功；fixed 非空 DENY。
- TVP / 未知仍 DENY。

## 自检结论

可改写项均有 MS-TDS 长度/布局依据；XML/UDT 非空与 TVP/未知保持 fail-closed 并文档化。**通过 → 编码。**

## 落地

已合入 `future/database-wire-protocol-foundation`（本轮提交）。测试基线见 STATUS §1（665）。

| 项 | 结果 |
|---|---|
| MONEY* 非空/round-trip | 可改写 |
| TEXTPTR TEXT/NTEXT/IMAGE | 可改写（合成零 textptr） |
| VARBINARY/BINARY（含 MAX） | 可改写；`FixedValueRule` 认 BINARY |
| XML/UDT | COLMETADATA 可解析；**仅 NULL**；非空 DENY |
| TVP / 未知 | 仍 DENY |
