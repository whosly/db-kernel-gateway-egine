<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from 'vue'
import {
  cancelSql,
  clearSqlHistory,
  createSqlSnippet,
  deleteSqlSnippet,
  executeSql,
  getSchemaCatalog,
  getSchemaColumns,
  listInstances,
  listSqlHistory,
  listSqlSnippets,
} from '../api/consoleApi'
import type {
  GatewayInstance,
  SchemaCatalogResponse,
  SqlExecuteResult,
  SqlHistoryEntry,
  SqlSnippet,
  SqlStatementResult,
} from '../api/types'

const toast = inject<(m: string) => void>('toast', () => {})

interface EditorTab {
  id: string
  title: string
  sql: string
}

const STORAGE_KEY = 'console.sql.tabs.v1'

const instances = ref<GatewayInstance[]>([])
const instanceId = ref('')
const maxRows = ref(200)
const running = ref(false)
const error = ref<string | null>(null)
const result = ref<SqlExecuteResult | null>(null)
const resultTab = ref(0)
const currentExecutionId = ref<string | null>(null)
let abortController: AbortController | null = null

const statementResults = computed((): SqlStatementResult[] => {
  if (!result.value) return []
  if (result.value.results?.length) return result.value.results
  return [
    {
      index: 0,
      ok: result.value.ok,
      columns: result.value.columns,
      rows: result.value.rows,
      rowCount: result.value.rowCount,
      truncated: result.value.truncated,
      updateCount: result.value.updateCount,
      warnings: result.value.warnings,
      durationMs: result.value.durationMs,
    },
  ]
})

const activeResult = computed(() => statementResults.value[resultTab.value] || statementResults.value[0] || null)

const tabs = ref<EditorTab[]>([
  { id: 't1', title: '查询 1', sql: 'SELECT 1' },
  { id: 't2', title: '查询 2', sql: '' },
  { id: 't3', title: '查询 3', sql: '' },
])
const activeTabId = ref('t1')
const activeTab = computed(() => tabs.value.find((t) => t.id === activeTabId.value) || tabs.value[0])

const catalog = ref<SchemaCatalogResponse | null>(null)
const catalogLoading = ref(false)
const catalogError = ref<string | null>(null)
const expandedSchemas = ref<Record<string, boolean>>({})
const expandedTables = ref<
  Record<string, { loading?: boolean; columns?: { name: string; typeName?: string }[] }>
>({})

const history = ref<SqlHistoryEntry[]>([])
const snippets = ref<SqlSnippet[]>([])
const snippetName = ref('')
const sidePanel = ref<'tree' | 'history' | 'snippets'>('tree')

const selected = computed(() => instances.value.find((i) => i.id === instanceId.value) || null)

const tablesBySchema = computed(() => {
  const map: Record<string, { schema: string; name: string; type?: string }[]> = {}
  if (!catalog.value) return map
  for (const t of catalog.value.tables || []) {
    const s = t.schema || '(default)'
    if (!map[s]) map[s] = []
    map[s].push(t)
  }
  return map
})

function loadTabsFromStorage() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return
    const parsed = JSON.parse(raw) as { tabs?: EditorTab[]; activeTabId?: string }
    if (parsed.tabs?.length) {
      tabs.value = parsed.tabs.slice(0, 8)
      activeTabId.value = parsed.activeTabId || tabs.value[0].id
    }
  } catch {
    /* ignore */
  }
}

function persistTabs() {
  try {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ tabs: tabs.value, activeTabId: activeTabId.value }),
    )
  } catch {
    /* ignore */
  }
}

watch([tabs, activeTabId], persistTabs, { deep: true })

async function loadInstances() {
  try {
    const body = await listInstances()
    instances.value = body.items || []
    if (!instanceId.value && instances.value.length) instanceId.value = instances.value[0].id
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}

async function loadCatalog() {
  if (!instanceId.value) return
  catalogLoading.value = true
  catalogError.value = null
  try {
    catalog.value = await getSchemaCatalog(instanceId.value)
    expandedSchemas.value = {}
    expandedTables.value = {}
  } catch (e) {
    catalogError.value = e instanceof Error ? e.message : String(e)
    catalog.value = null
  } finally {
    catalogLoading.value = false
  }
}

async function loadHistory() {
  try {
    const body = await listSqlHistory(instanceId.value || undefined, 50)
    history.value = body.items || []
  } catch {
    history.value = []
  }
}

async function loadSnippets() {
  try {
    const body = await listSqlSnippets()
    snippets.value = body.items || []
  } catch {
    snippets.value = []
  }
}

watch(instanceId, () => {
  loadCatalog()
  loadHistory()
})

function quoteIdent(dbType: string | undefined, name: string): string {
  const t = (dbType || '').toLowerCase()
  if (t.includes('mysql') || t.includes('mariadb')) return `\`${name.replace(/`/g, '``')}\``
  if (t.includes('sqlserver') || t.includes('mssql')) return `[${name.replace(/]/g, ']]')}]`
  return `"${name.replace(/"/g, '""')}"`
}

function insertSql(fragment: string) {
  const tab = activeTab.value
  if (!tab) return
  const cur = tab.sql || ''
  tab.sql = cur && !cur.endsWith('\n') ? `${cur}\n${fragment}` : `${cur}${fragment}`
}

function onTableClick(schema: string, table: string) {
  const db = selected.value?.dbType
  const s = schema && schema !== '(default)' ? `${quoteIdent(db, schema)}.` : ''
  insertSql(`SELECT * FROM ${s}${quoteIdent(db, table)} LIMIT 100;`)
  toast(`已插入 SELECT · ${schema}.${table}`)
}

async function toggleTable(schema: string, table: string) {
  const key = `${schema}.${table}`
  if (expandedTables.value[key]?.columns) {
    const copy = { ...expandedTables.value }
    delete copy[key]
    expandedTables.value = copy
    return
  }
  expandedTables.value = { ...expandedTables.value, [key]: { loading: true } }
  try {
    const schemaParam = schema === '(default)' ? undefined : schema
    const body = await getSchemaColumns(instanceId.value, table, schemaParam)
    expandedTables.value = {
      ...expandedTables.value,
      [key]: {
        columns: (body.columns || []).map((c) => ({ name: c.name, typeName: c.typeName })),
      },
    }
  } catch (e) {
    expandedTables.value = { ...expandedTables.value, [key]: { columns: [] } }
    toast(e instanceof Error ? e.message : String(e))
  }
}

function addTab() {
  const id = `t${Date.now()}`
  tabs.value.push({ id, title: `查询 ${tabs.value.length + 1}`, sql: '' })
  activeTabId.value = id
}

function closeTab(id: string) {
  if (tabs.value.length <= 1) return
  tabs.value = tabs.value.filter((t) => t.id !== id)
  if (activeTabId.value === id) activeTabId.value = tabs.value[0].id
}

function newExecutionId() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
  return `exec-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

async function run(sqlOverride?: string) {
  if (!instanceId.value) {
    toast('请先选择网关实例')
    return
  }
  const sql = (sqlOverride ?? activeTab.value?.sql ?? '').trim()
  if (!sql) {
    toast('请输入 SQL')
    return
  }
  if (abortController) {
    abortController.abort()
  }
  abortController = new AbortController()
  const executionId = newExecutionId()
  currentExecutionId.value = executionId
  running.value = true
  error.value = null
  result.value = null
  resultTab.value = 0
  try {
    result.value = await executeSql(
      instanceId.value,
      {
        sql,
        maxRows: Number(maxRows.value) || 200,
        executionId,
      },
      { signal: abortController.signal },
    )
    if (result.value.results?.length) {
      const failIdx = result.value.results.findIndex((r) => !r.ok)
      resultTab.value = failIdx >= 0 ? failIdx : 0
    }
    toast(result.value.message || `完成 · ${result.value.durationMs}ms`)
    loadHistory()
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      error.value = '请求已中止（浏览器侧）；服务端取消为尽力而为'
    } else {
      error.value = e instanceof Error ? e.message : String(e)
    }
    loadHistory()
  } finally {
    running.value = false
    currentExecutionId.value = null
    abortController = null
  }
}

async function cancelRunning() {
  const execId = currentExecutionId.value
  if (!instanceId.value || !execId) {
    toast('当前无进行中的执行')
    return
  }
  try {
    const body = await cancelSql(instanceId.value, execId)
    toast(body.message || '已请求取消')
    // Also abort the fetch so UI unblocks if the server hangs
    abortController?.abort()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}

function runExplain() {
  const db = (selected.value?.dbType || '').toLowerCase()
  const sql = (activeTab.value?.sql || '').trim().replace(/;$/, '')
  if (!sql) {
    toast('请输入 SQL')
    return
  }
  if (db.includes('sqlserver') || db.includes('mssql')) {
    toast('SQL Server 暂不支持一键 EXPLAIN 包装')
    return
  }
  run(`EXPLAIN ${sql}`)
}

function onKey(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
    e.preventDefault()
    run()
  }
}

function exportCsv() {
  const cols = activeResult.value?.columns
  const rows = activeResult.value?.rows
  if (!cols?.length) {
    toast('无结果可导出')
    return
  }
  const lines = [cols.map(csvEscape).join(',')]
  for (const row of rows || []) {
    lines.push(row.map((c) => csvEscape(c == null ? '' : String(c))).join(','))
  }
  downloadBlob(lines.join('\n'), `sql-result-${Date.now()}.csv`, 'text/csv;charset=utf-8')
}

function exportJson() {
  if (!result.value) {
    toast('无结果可导出')
    return
  }
  const payload = activeResult.value || result.value
  downloadBlob(JSON.stringify(payload, null, 2), `sql-result-${Date.now()}.json`, 'application/json')
}

function csvEscape(v: string) {
  if (/[",\n]/.test(v)) return `"${v.replace(/"/g, '""')}"`
  return v
}

function downloadBlob(text: string, filename: string, type: string) {
  const blob = new Blob([text], { type })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

async function saveSnippet() {
  const name = snippetName.value.trim() || activeTab.value?.title || '未命名'
  const sql = activeTab.value?.sql || ''
  if (!sql.trim()) {
    toast('当前 Tab 无 SQL')
    return
  }
  try {
    await createSqlSnippet({ name, sql })
    snippetName.value = ''
    toast('片段已保存')
    loadSnippets()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}

function loadSnippet(s: SqlSnippet) {
  if (activeTab.value) {
    activeTab.value.sql = s.sql
    activeTab.value.title = s.name
  }
  toast(`已加载片段：${s.name}`)
}

async function removeSnippet(id: string) {
  try {
    await deleteSqlSnippet(id)
    loadSnippets()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}

async function clearHistory() {
  try {
    await clearSqlHistory()
    loadHistory()
    toast('历史已清空')
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}

onMounted(async () => {
  loadTabsFromStorage()
  await loadInstances()
  await Promise.all([loadCatalog(), loadHistory(), loadSnippets()])
})
</script>

<template>
  <div class="sql-ide">
    <p class="lead">
      SQL IDE 挂在<strong>网关实例</strong>上：执行经<strong>代理 listenPort</strong>（脱敏/观测/风控生效）；
      左侧对象树为<strong>直连目标 JDBC 元数据</strong>。多语句（;）· 取消（尽力而为）· 多 Tab · 历史 · 片段 · 导出 · EXPLAIN。
    </p>

    <div class="bar">
      <label>
        网关实例
        <select v-model="instanceId">
          <option disabled value="">请选择…</option>
          <option v-for="i in instances" :key="i.id" :value="i.id">
            {{ i.name }} ({{ i.id }}) · {{ i.dbType }}
            {{ i.proxyModeLabel ? '· ' + i.proxyModeLabel : '' }}
            · {{ i.targetHost }}:{{ i.targetPort }}
          </option>
        </select>
      </label>
      <label>
        maxRows
        <input v-model.number="maxRows" type="number" min="1" max="1000" />
      </label>
      <button class="primary" :disabled="running || !instanceId" @click="run()">
        {{ running ? '执行中…' : '运行 (Ctrl/⌘+Enter)' }}
      </button>
      <button
        v-if="running"
        type="button"
        class="danger-btn"
        :disabled="!currentExecutionId"
        @click="cancelRunning"
      >
        取消
      </button>
      <button type="button" :disabled="running || !instanceId" @click="runExplain">EXPLAIN</button>
      <button type="button" :disabled="!activeResult?.columns?.length" @click="exportCsv">导出 CSV</button>
      <button type="button" :disabled="!result" @click="exportJson">导出 JSON</button>
      <button type="button" @click="loadInstances">刷新实例</button>
    </div>

    <div v-if="selected" class="meta muted">
      代理 {{ selected.listenHost }}:{{ selected.listenPort }}
      · 状态 {{ selected.status }}
      <template v-if="selected.proxyModeLabel"> · {{ selected.proxyModeLabel }}</template>
      · 目标 {{ selected.targetHost }}:{{ selected.targetPort }}
      · 库 {{ selected.targetDatabase || '—' }}
      · 密码 {{ selected.passwordConfigured ? '已配置' : '未配置' }}
    </div>

    <div class="ide-grid">
      <aside class="side">
        <div class="side-tabs">
          <button type="button" :class="{ on: sidePanel === 'tree' }" @click="sidePanel = 'tree'">对象树</button>
          <button type="button" :class="{ on: sidePanel === 'history' }" @click="sidePanel = 'history'">历史</button>
          <button type="button" :class="{ on: sidePanel === 'snippets' }" @click="sidePanel = 'snippets'">片段</button>
        </div>

        <div v-if="sidePanel === 'tree'" class="panel">
          <div class="panel-head">
            <span>Schema</span>
            <button class="link" type="button" :disabled="!instanceId || catalogLoading" @click="loadCatalog">
              {{ catalogLoading ? '加载中…' : '刷新' }}
            </button>
          </div>
          <p v-if="catalogError" class="err tiny">{{ catalogError }}</p>
          <p v-else-if="!catalog" class="muted tiny">选择实例后加载对象树</p>
          <ul v-else class="tree">
            <li v-for="(tables, schema) in tablesBySchema" :key="String(schema)">
              <button
                class="tree-node"
                type="button"
                @click="expandedSchemas[String(schema)] = !expandedSchemas[String(schema)]"
              >
                {{ expandedSchemas[String(schema)] ? '▼' : '▶' }} {{ schema }}
                <span class="muted tiny">({{ tables.length }})</span>
              </button>
              <ul v-if="expandedSchemas[String(schema)]">
                <li v-for="t in tables" :key="t.schema + '.' + t.name">
                  <div class="tree-row">
                    <button class="tree-node" type="button" @click="toggleTable(String(schema), t.name)">
                      {{ expandedTables[`${schema}.${t.name}`]?.columns ? '▼' : '▶' }}
                    </button>
                    <button class="tree-link" type="button" @click="onTableClick(String(schema), t.name)">
                      {{ t.name }}
                    </button>
                  </div>
                  <ul v-if="expandedTables[`${schema}.${t.name}`]?.columns" class="cols">
                    <li v-for="c in expandedTables[`${schema}.${t.name}`]!.columns" :key="c.name">
                      <button
                        class="tree-link tiny"
                        type="button"
                        @click="insertSql(quoteIdent(selected?.dbType, c.name))"
                      >
                        {{ c.name }}
                        <span class="muted">{{ c.typeName }}</span>
                      </button>
                    </li>
                  </ul>
                </li>
              </ul>
            </li>
          </ul>
          <p v-if="catalog?.note" class="muted tiny">{{ catalog.note }}</p>
        </div>

        <div v-else-if="sidePanel === 'history'" class="panel">
          <div class="panel-head">
            <span>执行历史</span>
            <button class="link" type="button" @click="clearHistory">清空</button>
          </div>
          <ul class="list">
            <li v-for="h in history" :key="h.id">
              <button class="list-item" type="button" @click="insertSql(h.sql)">
                <span :class="h.ok ? 'ok-tag' : 'err'">{{ h.ok ? 'OK' : 'ERR' }}</span>
                <span class="sql-preview">{{ h.sql }}</span>
                <span class="muted tiny">{{ h.durationMs ?? '—' }}ms · {{ h.rowCount ?? '—' }} 行</span>
              </button>
            </li>
            <li v-if="!history.length" class="muted tiny">暂无历史</li>
          </ul>
        </div>

        <div v-else class="panel">
          <div class="panel-head"><span>已存片段</span></div>
          <div class="snippet-save">
            <input v-model="snippetName" placeholder="片段名称" />
            <button type="button" @click="saveSnippet">保存当前 Tab</button>
          </div>
          <ul class="list">
            <li v-for="s in snippets" :key="s.id" class="snippet-row">
              <button class="list-item" type="button" @click="loadSnippet(s)">
                <strong>{{ s.name }}</strong>
                <span class="sql-preview">{{ s.sql }}</span>
              </button>
              <button class="link danger" type="button" @click="removeSnippet(s.id)">删</button>
            </li>
            <li v-if="!snippets.length" class="muted tiny">暂无片段</li>
          </ul>
        </div>
      </aside>

      <section class="main">
        <div class="tabs">
          <button
            v-for="t in tabs"
            :key="t.id"
            type="button"
            class="tab"
            :class="{ on: t.id === activeTabId }"
            @click="activeTabId = t.id"
          >
            {{ t.title }}
            <span v-if="tabs.length > 1" class="x" @click.stop="closeTab(t.id)">×</span>
          </button>
          <button type="button" class="tab add" @click="addTab">+</button>
        </div>

        <textarea
          v-if="activeTab"
          v-model="activeTab.sql"
          class="editor"
          rows="12"
          spellcheck="false"
          placeholder="输入 SQL（可用 ; 分隔多条，最多 20 条；遇错默认停止）…"
          @keydown="onKey"
        />

        <p v-if="error" class="err">{{ error }}</p>
        <div v-if="result" class="result">
          <div class="result-head">
            <span v-if="result.statementCount != null">{{ result.statementCount }} 条语句</span>
            <span>{{ result.durationMs }} ms</span>
            <span v-if="result.stoppedAt != null" class="warn">停于 [{{ result.stoppedAt }}]</span>
            <span v-if="result.cancelled" class="warn">已取消</span>
            <span v-if="result.viaProxy" class="ok-tag">
              经代理 {{ result.proxyHost }}:{{ result.proxyPort }}
            </span>
            <span class="muted tiny">{{ result.note }}</span>
          </div>
          <p v-if="result.cancelDisclaimer" class="muted tiny">{{ result.cancelDisclaimer }}</p>
          <div v-if="statementResults.length > 1" class="result-tabs">
            <button
              v-for="r in statementResults"
              :key="r.index"
              type="button"
              class="tab"
              :class="{ on: resultTab === r.index, 'tab-err': !r.ok }"
              @click="resultTab = r.index"
            >
              #{{ r.index }} {{ r.ok ? 'OK' : 'ERR' }}
            </button>
          </div>
          <template v-if="activeResult">
            <div class="result-head">
              <span v-if="activeResult.ok">{{ activeResult.rowCount ?? 0 }} 行</span>
              <span v-if="activeResult.durationMs != null">{{ activeResult.durationMs }} ms</span>
              <span v-if="activeResult.truncated" class="warn">已截断</span>
              <span v-if="activeResult.updateCount != null">updateCount={{ activeResult.updateCount }}</span>
              <span v-if="!activeResult.ok" class="err">{{ activeResult.error }}</span>
            </div>
            <p v-for="(w, i) in activeResult.warnings || []" :key="i" class="muted tiny">{{ w }}</p>
            <div class="table-wrap">
              <table v-if="activeResult.ok && activeResult.columns?.length">
                <thead>
                  <tr>
                    <th v-for="c in activeResult.columns" :key="c">{{ c }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, ri) in activeResult.rows || []" :key="ri">
                    <td v-for="(cell, ci) in row" :key="ci">
                      <span v-if="cell === null" class="null">NULL</span>
                      <span v-else>{{ cell }}</span>
                    </td>
                  </tr>
                </tbody>
              </table>
              <p v-else-if="activeResult.ok" class="muted">无结果集</p>
            </div>
          </template>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.lead { color: var(--text-muted); max-width: 56rem; }
.bar {
  display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: flex-end;
  margin: 1rem 0; padding: 0.75rem;
  background: var(--bg-elevated); border: 1px solid var(--border); border-radius: var(--radius);
}
.bar label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8rem; color: var(--text-muted); }
.bar select { min-width: 18rem; }
.bar input[type='number'] { width: 6rem; }
.ide-grid { display: grid; grid-template-columns: 260px 1fr; gap: 0.75rem; min-height: 28rem; }
@media (max-width: 960px) { .ide-grid { grid-template-columns: 1fr; } }
.side {
  border: 1px solid var(--border); border-radius: var(--radius);
  background: var(--bg-elevated); display: flex; flex-direction: column; max-height: 70vh;
}
.side-tabs { display: flex; border-bottom: 1px solid var(--border); }
.side-tabs button {
  flex: 1; padding: 0.45rem; background: transparent; border: 0; color: var(--text-muted); cursor: pointer;
}
.side-tabs button.on { color: var(--text); background: var(--accent-soft, rgba(59,130,246,.15)); }
.panel { padding: 0.5rem; overflow: auto; flex: 1; }
.panel-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.35rem; }
.tree, .cols, .list { list-style: none; padding: 0; margin: 0; }
.tree ul { list-style: none; padding-left: 0.85rem; margin: 0; }
.tree-node, .tree-link, .list-item, .link {
  background: none; border: 0; color: var(--text); cursor: pointer; text-align: left;
}
.tree-node, .tree-link { padding: 0.15rem 0; font-size: 0.82rem; }
.tree-row { display: flex; gap: 0.15rem; align-items: center; }
.tree-link:hover, .list-item:hover { color: var(--accent, #3b82f6); }
.list-item { display: flex; flex-direction: column; gap: 0.15rem; width: 100%; padding: 0.35rem 0; }
.sql-preview {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.72rem; color: var(--text-muted);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 220px;
}
.snippet-save { display: flex; gap: 0.35rem; margin-bottom: 0.5rem; }
.snippet-save input { flex: 1; }
.snippet-row { display: flex; gap: 0.25rem; align-items: flex-start; }
.tabs { display: flex; flex-wrap: wrap; gap: 0.25rem; margin-bottom: 0.35rem; }
.tab {
  padding: 0.35rem 0.65rem; border: 1px solid var(--border); border-radius: 6px 6px 0 0;
  background: var(--bg-elevated); color: var(--text-muted); cursor: pointer;
}
.tab.on { background: var(--bg-card); color: var(--text); border-bottom-color: var(--bg-card); }
.tab .x { margin-left: 0.35rem; opacity: 0.6; }
.tab.add { min-width: 2rem; }
.editor {
  width: 100%; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.9rem; line-height: 1.45; padding: 0.75rem;
  background: var(--bg-card); border: 1px solid var(--border); border-radius: var(--radius);
  color: var(--text); resize: vertical;
}
.err { color: var(--danger, #ef4444); margin-top: 0.5rem; }
.meta { margin: 0.5rem 0; }
.result { margin-top: 1rem; }
.result-head { display: flex; flex-wrap: wrap; gap: 0.75rem; margin-bottom: 0.5rem; font-size: 0.85rem; }
.warn { color: #f59e0b; }
.ok-tag { color: #10b981; font-size: 0.8rem; }
.table-wrap { overflow: auto; max-height: 28rem; border: 1px solid var(--border); border-radius: var(--radius); }
table { border-collapse: collapse; width: 100%; font-size: 0.85rem; }
th, td { border-bottom: 1px solid var(--border); padding: 0.4rem 0.6rem; text-align: left; white-space: nowrap; }
th { background: var(--bg-elevated); position: sticky; top: 0; }
.null { color: var(--text-muted); font-style: italic; }
.muted { color: var(--text-muted); }
.tiny { font-size: 0.75rem; }
.link.danger { color: var(--danger, #ef4444); }
.danger-btn {
  background: var(--danger, #ef4444); color: #fff; border: 0; border-radius: 6px;
  padding: 0.45rem 0.85rem; cursor: pointer;
}
.danger-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.result-tabs { display: flex; flex-wrap: wrap; gap: 0.25rem; margin: 0.5rem 0; }
.tab-err { color: var(--danger, #ef4444) !important; }
</style>
