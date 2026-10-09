<script setup lang="ts">
import { inject, ref, watch } from 'vue'
import { cancelSql, killSession, listSessions, listSqlExecutions } from '../api/consoleApi'
import type { SessionRow, SqlExecutionRow } from '../api/types'
import { Perm, usePermissions } from '../composables/usePermissions'
import { usePolling } from '../composables/usePolling'

/**
 * SQL 工作台「会话」面板：
 * - 工作台执行中：管控台 JDBC 执行（取消注册表，executionId），取消需 sql:execute
 * - 代理会话：数据面 wire 客户端连接（listenPort），断开需 sessions:kill
 * 两者语义不同，分开展示。
 */
const props = defineProps<{
  instanceId: string
  instanceRunning: boolean
  /** executionId of the run started from this browser tab (marked「本页」). */
  localExecutionId?: string | null
}>()
const emit = defineEmits<{ (e: 'cancelled', executionId: string): void }>()

const { has } = usePermissions()
const toast = inject<(m: string) => void>('toast', () => {})

const executions = ref<SqlExecutionRow[]>([])
const sessions = ref<SessionRow[]>([])
const loading = ref(false)
const execError = ref<string | null>(null)
const sessError = ref<string | null>(null)
const autoRefresh = ref(true)

async function refresh() {
  if (!props.instanceId) {
    executions.value = []
    sessions.value = []
    return
  }
  loading.value = true
  const id = props.instanceId
  const [ex, se] = await Promise.allSettled([listSqlExecutions(id), listSessions(id)])
  if (id !== props.instanceId) return
  if (ex.status === 'fulfilled') {
    executions.value = ex.value.items || []
    execError.value = null
  } else {
    executions.value = []
    execError.value = ex.reason instanceof Error ? ex.reason.message : String(ex.reason)
  }
  if (se.status === 'fulfilled') {
    sessions.value = se.value.items || []
    sessError.value = null
  } else {
    sessions.value = []
    sessError.value = se.reason instanceof Error ? se.reason.message : String(se.reason)
  }
  loading.value = false
}

const polling = usePolling(async () => {
  if (autoRefresh.value) await refresh()
}, 5000)

watch(
  () => props.instanceId,
  () => void refresh(),
)
watch(
  () => props.localExecutionId,
  () => void refresh(),
)

async function onCancel(row: SqlExecutionRow) {
  if (!confirm(`取消工作台执行 ${row.executionId.slice(0, 8)}…？（尽力而为：Statement.cancel + 关闭连接）`)) return
  try {
    const body = await cancelSql(props.instanceId, row.executionId)
    toast(body.found === false ? body.message || '执行已结束' : body.message || '已请求取消')
    emit('cancelled', row.executionId)
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
  await refresh()
}

async function onKill(row: SessionRow) {
  if (!confirm(`断开代理会话 ${row.connectionId}？\n仅关闭客户端腿，不向后端发 KILL；若该连接正被工作台执行使用，执行会失败。`)) return
  try {
    await killSession(props.instanceId, row.connectionId)
    toast('已断开代理会话')
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
  await refresh()
}

function fmtTime(iso?: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleTimeString()
}

function fmtElapsed(ms?: number) {
  if (ms == null) return '—'
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`
}

defineExpose({ refresh, polling })
</script>

<template>
  <div class="sp">
    <div class="panel-head">
      <span>会话</span>
      <span class="actions">
        <label class="tiny muted"><input v-model="autoRefresh" type="checkbox" /> 自动刷新</label>
        <button class="link" type="button" :disabled="!instanceId || loading" @click="refresh">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </span>
    </div>
    <p v-if="!instanceId" class="muted tiny">请先选择网关实例</p>
    <template v-else>
      <h4>
        工作台执行中 <span class="muted tiny">({{ executions.length }})</span>
      </h4>
      <p class="muted tiny">管控台 SQL 执行（executionId），经代理口发起；取消为尽力而为。</p>
      <p v-if="execError" class="err tiny">{{ execError }}</p>
      <ul class="list">
        <li v-for="x in executions" :key="x.executionId" class="row">
          <div class="row-main">
            <span class="mono tiny">
              {{ x.executionId.slice(0, 8) }}
              <span v-if="x.executionId === localExecutionId" class="tag">本页</span>
              <span v-if="x.cancelRequested" class="tag warn">取消中</span>
            </span>
            <span class="sql-preview" :title="x.sqlPreview || ''">{{ x.sqlPreview || '—' }}</span>
            <span class="muted tiny">
              {{ fmtElapsed(x.elapsedMs) }}
              · 语句 {{ (x.currentStatementIndex ?? 0) + 1 }}/{{ x.statementCount ?? 1 }}
              <template v-if="x.initiatedBy"> · {{ x.initiatedBy }}</template>
              <template v-if="x.targetUser"> · 目标账号 {{ x.targetUser }}</template>
              <template v-if="x.viaProxy && x.proxyPort"> · 代理口 {{ x.proxyPort }}</template>
            </span>
          </div>
          <button
            v-if="has(Perm.SQL_EXECUTE)"
            type="button"
            class="link danger"
            :disabled="x.cancelRequested"
            @click="onCancel(x)"
          >
            取消
          </button>
        </li>
        <li v-if="!executions.length" class="muted tiny">无进行中的工作台执行</li>
      </ul>

      <h4>
        代理会话 <span class="muted tiny">({{ sessions.length }})</span>
      </h4>
      <p class="muted tiny">数据面客户端连接（listenPort），含工作台与外部客户端；断开仅关闭客户端腿。</p>
      <p v-if="!instanceRunning" class="muted tiny">实例未运行，无代理会话</p>
      <p v-if="sessError" class="err tiny">{{ sessError }}</p>
      <ul class="list">
        <li v-for="s in sessions" :key="s.connectionId" class="row">
          <div class="row-main">
            <span class="mono tiny">
              {{ s.connectionId }}
              <span v-if="s.inTransaction" class="tag warn">事务中</span>
            </span>
            <span class="muted tiny">
              {{ s.clientUser || '—' }}@{{ s.clientDatabase || '—' }} · {{ s.state || '—' }}
            </span>
            <span class="muted tiny">
              连接 {{ fmtTime(s.connectedAt) }} · 活动 {{ fmtTime(s.lastActivity) }}
            </span>
          </div>
          <button v-if="has(Perm.SESSIONS_KILL)" type="button" class="link danger" @click="onKill(s)">
            断开
          </button>
        </li>
        <li v-if="instanceRunning && !sessions.length" class="muted tiny">暂无代理会话</li>
      </ul>
    </template>
  </div>
</template>

<style scoped>
.panel-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.35rem; }
.actions { display: flex; gap: 0.5rem; align-items: center; }
h4 { margin: 0.6rem 0 0.15rem; font-size: 0.85rem; }
.list { list-style: none; padding: 0; margin: 0; }
.row {
  display: flex; gap: 0.35rem; align-items: flex-start; justify-content: space-between;
  padding: 0.35rem 0; border-bottom: 1px solid var(--border);
}
.row-main { display: flex; flex-direction: column; gap: 0.1rem; min-width: 0; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
.sql-preview {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.72rem; color: var(--text-muted);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 190px;
}
.tag {
  font-size: 0.68rem; padding: 0 0.3rem; border-radius: 4px; margin-left: 0.25rem;
  background: var(--accent-soft, rgba(59,130,246,.15)); color: var(--text);
}
.tag.warn { background: rgba(245, 158, 11, 0.18); color: #f59e0b; }
.link { background: none; border: 0; color: var(--text); cursor: pointer; white-space: nowrap; }
.link.danger { color: var(--danger, #ef4444); }
.link:disabled { opacity: 0.5; cursor: not-allowed; }
.err { color: var(--danger, #ef4444); }
.muted { color: var(--text-muted); }
.tiny { font-size: 0.75rem; }
</style>
