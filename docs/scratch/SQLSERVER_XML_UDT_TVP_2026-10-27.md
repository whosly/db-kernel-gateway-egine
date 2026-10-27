# SQL Server P2：XML 非空 / UDT / TVP 脱敏深改写（设计自检）

日期：2026-10-27（Asia/Shanghai）  
范围：TDS `TABULAR_RESULT` 脱敏；对照 [MS-BINXML]、[MS-SSCLRT]、[MS-TDS] TVP。  
基线：`f6a92a0` — XML/UDT **仅 NULL**；TVP/未知 fail-closed；Login7 deferred 已落地。

## 现状（改前）

| 类型 | 行为 |
|---|---|
| XML | COLMETADATA 可解析；`supportsNonNullRewrite=false`；NULL=PLP_NULL |
| UDT | 同上；CLR / MS-SSCLRT 非空 DENY |
| TVP（0xF3） | `WireLayout.UNSUPPORTED` → 整段 COLMETADATA 解析失败 |

## 根因与协议依据

1. **XML**：ROW=PLP，载荷为 **MS-BINXML**（非 UCS-2 文本）。此前无编码器 → 仅 NULL。  
   协议安全路径：实现最小 BINXML **writer/reader**（[MS-BINXML] `document`：`DF FF` + ver + `B0 04` + NAMEDEF/QNAMEDEF/ELEMENT + SQL-NVARCHAR + ENDELEMENT）。规则侧按 **TEXT** 处理；非空改写把规则 UTF-8 文本放入合成根元素 `<m>…</m>` 的文本节点（文本节点无需 XML 转义）。
2. **UDT**：ROW=PLP（MS-SSCLRT）。盲目填字节会破坏 CLR 反序列化。  
   - **hierarchyid**：路径字符串（仅 `/` 分隔非负整数，无 `.` fake level）按 [MS-SSCLRT] Li/Oi/Fi 编码 → 协议安全。  
   - **其它 UDT**（含 geometry/geography/自定义 CLR）：非空仍 **DENY**；允许 `fixed` 以 **`0x` + hex** 作不透明 PLP 载荷（仅 TDS 帧合法，应用层自担）。  
   - NULL 仍 PLP_NULL。
3. **TVP**：主要为 RPC 入参；结果集列罕见。`UNSUPPORTED` 会使整段元数据失败。  
   协议安全路径：解析 `TVP_TYPENAME` + `TVP_COLMETADATA` + optional tokens 至首个 `TVP_END`；**不**改写 TVP 单元格（无稳定结果集模型）→ 有规则命中 TVP 列仍 DENY；无规则则可解析元数据。

## 方案

### A. XML
- `TdsBinXml.encodeTextDocument(text)` / `tryDecodeToText(binxml)`。
- `TdsTypeCode.XML` category → `TEXT`；`supportsNonNullRewrite=true`。
- `TdsRowCodec` / interceptor `toRuleValue` 走 BINXML。

### B. UDT
- COLMETADATA 保留 `udtTypeName`（B_VARCHAR type name）。
- `hierarchyid`（大小写不敏感）→ category TEXT；path↔bytes。
- 其它：`supportsNonNullRewrite` 仅当规则产出可解析的 `0x` hex；否则 DENY。
- geometry/geography/未知 CLR 非空默认 DENY（文档化）。

### C. TVP
- `WireLayout.TVP_META`；解析 typename + 嵌套列至 `TVP_END`。
- `supportsNullRewrite=false`；`supportsNonNullRewrite=false`。
- ROW 遇 TVP 列：无法跳过嵌套值时 parse empty → 有规则则 DENY（与现 fail-closed 一致，但纯 TVP 元数据不再拖垮整段 COLMETADATA）。

### D. 刻意拒绝
- XML：无法解码的 BINXML 上 **需要原文** 的 partial/hash → DENY；encode 失败 → DENY。
- UDT：非 hierarchyid 且非 `0x` hex 的非空；hierarchyid 含 `.` / 非法段。
- TVP 单元格改写；未知 type byte。

## 测试

- BINXML round-trip；interceptor fixed/partial XML 非空 FORWARD。
- hierarchyid `/`、`/1/`、`/1/1/` round-trip；interceptor fixed。
- 通用 UDT：null OK；`0x0102` fixed OK；明文 fixed DENY。
- TVP COLMETADATA 可解析；规则命中 TVP → DENY。

## 自检结论

可改写项均有规范依据；不安全处保持 fail-closed 并文档化。**通过 → 编码。**


## 落地（同日）

已合入 `future/database-wire-protocol-foundation`：

| 项 | 状态 |
|---|---|
| `TdsBinXml` 合成 `<m>…</m>` + best-effort decode | ✅ |
| `TdsHierarchyId` 非负路径 + `0x` hex 助手 | ✅ |
| `TdsTypeCode.XML`→TEXT；`TVP_META`；COLMETADATA 保留 `udtTypeName` | ✅ |
| interceptor / `TdsRowCodec` 非空改写 | ✅ |
| TVP 单元格改写 | ❌ 仍 DENY（无规则时透传） |
| geometry 等非 `0x` CLR / hierarchyid `.` fake | ❌ 仍 DENY |

测试：`TdsXmlUdtTvpTest` + 既有 XML/UDT 用例更新；全量 **699** 绿。


## 落地（TVP 嵌套单元格 · 同日续）

| 项 | 状态 |
|---|---|
| `TdsTvpCodec` 解析/重编码（ORDER_UNIQUE / COLUMN_ORDERING / END / ROW cells） | ✅ |
| 嵌套单元格按列名规则改写（TEXT/NTEXT/IMAGE 走 TVP_ROW 无 TextPointer） | ✅ |
| `fDefault`（Flags 0x200）省略单元格 | ✅ |
| TVP **整列** wholesale 规则 | ❌ 仍 DENY |
| 嵌套未知 type / 嵌套 TVP | ❌ 仍 DENY |

规则命中嵌套列名 → 改写；无嵌套规则 → 字节透传；命中外层 TVP 列名 → DENY。
