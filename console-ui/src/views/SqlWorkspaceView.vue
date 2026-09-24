<script setup lang="ts">
import { computed, inject, onMounted, ref } from 'vue'
import { executeSql, listInstances } from '../api/consoleApi'
import type { GatewayInstance, SqlExecuteResult } from '../api/types'

const toast = inject<(m: string) => void>('toast', () => {})
const instances = ref<GatewayInstance[]>([])
const instanceId = ref('')
const sql = ref('SELECT 1')
const maxRows = ref(200)
const running = ref(false)
const error = ref<string | null>(null)
const result = ref<SqlExecuteResult | null>(null)

const selected = computed(() => instances.value.find((i) => i.id === instanceId.value) || null)

async function loadInstances() {
  try {
    const body = await listInstances()
    instances.value = body.instances || []
    if (!instanceId.value && instances.value.length) {
      instanceId.value = instances.value[0].id
    }
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}

async function run() {
  if (!instanceId.value) {
    toast('请先选择网关实例')
    return
  }
  if (!sql.value.trim()) {
    toast('请输入 SQL')
    return
  }
  running.value = true
  error.value = null
  result.value = null
  try {
    result.value = await executeSql(instanceId.value, {
      sql: sql.value,
      maxRows: Number(maxRows.value) || 200,
    })
    toast(result.value.message || `完成 · ${result.value.durationMs}ms`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    running.value = false
  }
}

function onKey(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
    e.preventDefault()
    run()
  }
}

onMounted(loadInstances)
</script>

<template>
  <div class="sql-ws">
    <p class="lead">
      SQL 工作台挂在<strong>网关实例</strong>上：服务端 JDBC <em>直连目标库</em>（与列提示同源凭据），
      <strong>不是</strong>经代理监听口的协议客户端。受风控策略约束；仅单条语句。
    </p>

    <div class="bar">
      <label>
        网关实例
        <select v-model="instanceId">
          <option disabled value="">请选择…</option>
          <option v-for="i in instances" :key="i.id" :value="i.id">
            {{ i.name }} ({{ i.id }}) · {{ i.dbType }} · {{ i.targetHost }}:{{ i.targetPort }}
          </option>
        </select>
      </label>
      <label>
        maxRows
        <input v-model.number="maxRows" type="number" min="1" max="1000" />
      </label>
      <button class="primary" :disabled="running || !instanceId" @click="run">
        {{ running ? '执行中…' : '运行 (Ctrl/⌘+Enter)' }}
      </button>
      <button type="button" @click="loadInstances">刷新实例</button>
    </div>

    <div v-if="!instances.length" class="empty">暂无网关实例。请先在「网关实例」页创建或导入。</div>
    <div v-else-if="!instanceId" class="empty">请选择一个网关实例以执行 SQL。</div>

    <template v-else>
      <p v-if="selected" class="meta muted">
        目标 {{ selected.targetHost }}:{{ selected.targetPort }}
        · 库 {{ selected.targetDatabase || '—' }}
        · 密码 {{ selected.passwordConfigured ? '已配置' : '未配置（需先编辑实例）' }}
        · 来源 {{ selected.source === 'console' ? '管控台' : 'YAML' }}
      </p>
      <textarea
        v-model="sql"
        class="editor"
        rows="10"
        spellcheck="false"
        placeholder="输入单条 SQL…"
        @keydown="onKey"
      />
      <p v-if="error" class="err">{{ error }}</p>
      <div v-if="result" class="result">
        <div class="result-head">
          <span>{{ result.rowCount }} 行</span>
          <span>{{ result.durationMs }} ms</span>
          <span v-if="result.truncated" class="warn">已截断</span>
          <span v-if="result.updateCount != null">updateCount={{ result.updateCount }}</span>
          <span class="muted tiny">{{ result.note }}</span>
        </div>
        <p v-for="(w, i) in result.warnings || []" :key="i" class="muted tiny">{{ w }}</p>
        <div class="table-wrap">
          <table v-if="result.columns.length">
            <thead>
              <tr>
                <th v-for="c in result.columns" :key="c">{{ c }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, ri) in result.rows" :key="ri">
                <td v-for="(cell, ci) in row" :key="ci">
                  <span v-if="cell === null" class="null">NULL</span>
                  <span v-else>{{ cell }}</span>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="muted">无结果集</p>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.lead { color: var(--text-muted); max-width: 52rem; }
.bar {
  display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: flex-end;
  margin: 1rem 0; padding: 0.75rem;
  background: var(--bg-elevated); border: 1px solid var(--border); border-radius: var(--radius);
}
.bar label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8rem; color: var(--text-muted); }
.bar select { min-width: 18rem; }
.bar input[type='number'] { width: 6rem; }
.editor {
  width: 100%; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.9rem; line-height: 1.45; padding: 0.75rem;
  background: var(--bg-card); border: 1px solid var(--border); border-radius: var(--radius);
  color: var(--text); resize: vertical;
}
.err { color: var(--danger); margin-top: 0.75rem; }
.empty {
  padding: 2rem; text-align: center; color: var(--text-muted);
  border: 1px dashed var(--border); border-radius: var(--radius); margin-top: 1rem;
}
.meta { margin: 0.5rem 0; }
.result { margin-top: 1rem; }
.result-head { display: flex; flex-wrap: wrap; gap: 0.75rem; margin-bottom: 0.5rem; font-size: 0.85rem; }
.warn { color: #f59e0b; }
.table-wrap { overflow: auto; max-height: 28rem; border: 1px solid var(--border); border-radius: var(--radius); }
table { border-collapse: collapse; width: 100%; font-size: 0.85rem; }
th, td { border-bottom: 1px solid var(--border); padding: 0.4rem 0.6rem; text-align: left; white-space: nowrap; }
th { background: var(--bg-elevated); position: sticky; top: 0; }
.null { color: var(--text-muted); font-style: italic; }
.muted { color: var(--text-muted); }
.tiny { font-size: 0.75rem; }
</style>
