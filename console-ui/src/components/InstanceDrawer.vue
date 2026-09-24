<script setup lang="ts">
import { computed, inject, onMounted, reactive, ref, watch } from 'vue'
import type {
  GatewayInstance,
  HealthCheckResult,
  MaskingRule,
  MaskingRulePayload,
  MaskingStrategy,
  RecentStatement,
  SchemaColumn,
  PoolStats,
  SessionRow,
  UpdateInstancePayload,
} from '../api/types'
import {
  createMaskingRule,
  deleteMaskingRule,
  getInstanceMetrics,
  getSchemaColumns,
  healthCheck,
  killSession,
  listMaskingRules,
  listRecentStatements,
  listSessions,
  updateMaskingRule,
} from '../api/consoleApi'

const props = defineProps<{ instance: GatewayInstance | null }>()
const emit = defineEmits<{ close: []; delete: []; save: [UpdateInstancePayload]; clone: [] }>()

const toast = inject<(m: string) => void>('toast', () => {})
const tab = ref<'info' | 'masking' | 'sessions' | 'recent'>('info')
const metrics = ref<Record<string, number>>({})
const pool = ref<PoolStats | null>(null)
const rules = ref<MaskingRule[]>([])
const rulesError = ref<string | null>(null)
const rulesLoading = ref(false)
const editingId = ref<string | null>(null)
const saving = ref(false)
const schemaColumns = ref<SchemaColumn[]>([])
const schemaLoading = ref(false)
const schemaError = ref<string | null>(null)
const schemaTableFilter = ref('')
const sessions = ref<SessionRow[]>([])
const sessionsLoading = ref(false)
const sessionsError = ref<string | null>(null)
const recent = ref<RecentStatement[]>([])
const recentLoading = ref(false)
const recentError = ref<string | null>(null)
const recentNote = ref<string | null>(null)
const health = ref<HealthCheckResult | null>(null)
const healthBusy = ref(false)
const editSaving = ref(false)
const editForm = reactive({
  name: '',
  listenHost: '',
  listenPort: 0,
  targetHost: '',
  targetPort: 0,
  targetDatabase: '',
  targetUsername: '',
  targetPassword: '',
  enabled: true,
})

function syncEditForm() {
  const i = props.instance
  if (!i) return
  editForm.name = i.name || ''
  editForm.listenHost = i.listenHost || '0.0.0.0'
  editForm.listenPort = i.listenPort
  editForm.targetHost = i.targetHost || ''
  editForm.targetPort = i.targetPort
  editForm.targetDatabase = i.targetDatabase || ''
  editForm.targetUsername = i.targetUsername || ''
  editForm.targetPassword = ''
  editForm.enabled = i.enabled
}

async function submitEdit() {
  if (!props.instance || props.instance.source !== 'console') return
  editSaving.value = true
  try {
    const payload: UpdateInstancePayload = {
      name: editForm.name,
      listenHost: editForm.listenHost,
      listenPort: Number(editForm.listenPort),
      targetHost: editForm.targetHost,
      targetPort: Number(editForm.targetPort),
      targetDatabase: editForm.targetDatabase,
      targetUsername: editForm.targetUsername,
      enabled: editForm.enabled,
    }
    if (editForm.targetPassword && editForm.targetPassword.trim()) {
      payload.targetPassword = editForm.targetPassword
    }
    emit('save', payload)
    editForm.targetPassword = ''
  } finally {
    editSaving.value = false
  }
}

const strategyOptions: { value: MaskingStrategy; label: string }[] = [
  { value: 'null', label: '置空' },
  { value: 'fixed', label: '固定值' },
  { value: 'partial', label: '部分遮罩' },
  { value: 'hash', label: '哈希' },
  { value: 'encrypt', label: '加密' },
]

const form = reactive<MaskingRulePayload>({
  name: '',
  strategy: 'null',
  priority: 0,
  columnName: '',
  tableName: '',
  namePattern: '',
  fixedValue: 'REDACTED',
  keepPrefix: 3,
  keepSuffix: 4,
  hashHexLength: 32,
  enabled: true,
})

const showStrategyFields = computed(() => form.strategy)

async function loadMetrics() {
  if (!props.instance) return
  try {
    const body = await getInstanceMetrics(props.instance.id)
    metrics.value = body.metrics || {}
    pool.value = body.pool || null
  } catch {
    metrics.value = props.instance.metrics || {}
    pool.value = null
  }
}

async function loadRules() {
  if (!props.instance) return
  rulesLoading.value = true
  rulesError.value = null
  try {
    const body = await listMaskingRules(props.instance.id)
    rules.value = body.rules || []
  } catch (e) {
    rulesError.value = e instanceof Error ? e.message : String(e)
    rules.value = []
  } finally {
    rulesLoading.value = false
  }
}

function resetForm() {
  editingId.value = null
  form.name = ''
  form.strategy = 'null'
  form.priority = 0
  form.columnName = ''
  form.tableName = ''
  form.namePattern = ''
  form.fixedValue = 'REDACTED'
  form.keepPrefix = 3
  form.keepSuffix = 4
  form.hashHexLength = 32
  form.enabled = true
}

function startEdit(rule: MaskingRule) {
  editingId.value = rule.id
  form.name = rule.name
  form.strategy = (rule.strategy as MaskingStrategy) || 'null'
  form.priority = rule.priority ?? 0
  form.columnName = rule.columnName || ''
  form.tableName = rule.tableName || ''
  form.namePattern = rule.namePattern || ''
  form.fixedValue = rule.fixedValue ?? 'REDACTED'
  form.keepPrefix = rule.keepPrefix ?? 3
  form.keepSuffix = rule.keepSuffix ?? 4
  form.hashHexLength = rule.hashHexLength ?? 32
  form.enabled = rule.enabled !== false
  tab.value = 'masking'
}

function strategyLabel(s: string) {
  return strategyOptions.find((o) => o.value === s)?.label || s
}

async function submitRule() {
  if (!props.instance) return
  saving.value = true
  try {
    const payload: MaskingRulePayload = {
      name: form.name.trim(),
      strategy: form.strategy,
      priority: Number(form.priority) || 0,
      columnName: form.columnName?.trim() || undefined,
      tableName: form.tableName?.trim() || undefined,
      namePattern: form.namePattern?.trim() || undefined,
      enabled: form.enabled !== false,
    }
    if (form.strategy === 'fixed') payload.fixedValue = form.fixedValue ?? ''
    if (form.strategy === 'partial') {
      payload.keepPrefix = Number(form.keepPrefix) || 0
      payload.keepSuffix = Number(form.keepSuffix) || 0
    }
    if (form.strategy === 'hash') {
      payload.hashHexLength = Number(form.hashHexLength) || 32
    }
    if (editingId.value) {
      await updateMaskingRule(props.instance.id, editingId.value, payload)
      toast('脱敏规则已更新（已热挂载）')
    } else {
      await createMaskingRule(props.instance.id, payload)
      toast('脱敏规则已新增（已热挂载）')
    }
    resetForm()
    await loadRules()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  } finally {
    saving.value = false
  }
}

async function removeRule(rule: MaskingRule) {
  if (!props.instance) return
  if (!confirm(`删除规则「${rule.name}」？`)) return
  try {
    await deleteMaskingRule(props.instance.id, rule.id)
    toast('已删除脱敏规则')
    if (editingId.value === rule.id) resetForm()
    await loadRules()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}


async function loadSchemaHints() {
  if (!props.instance) return
  schemaLoading.value = true
  schemaError.value = null
  try {
    const body = await getSchemaColumns(
      props.instance.id,
      schemaTableFilter.value.trim() || undefined,
    )
    schemaColumns.value = body.columns || []
  } catch (e) {
    schemaError.value = e instanceof Error ? e.message : String(e)
    schemaColumns.value = []
  } finally {
    schemaLoading.value = false
  }
}

function pickColumn(col: SchemaColumn) {
  form.columnName = col.name
  form.tableName = col.table || form.tableName
}


async function loadSessions() {
  if (!props.instance) return
  sessionsLoading.value = true
  sessionsError.value = null
  try {
    const body = await listSessions(props.instance.id)
    sessions.value = body.sessions || []
  } catch (e) {
    sessionsError.value = e instanceof Error ? e.message : String(e)
    sessions.value = []
  } finally {
    sessionsLoading.value = false
  }
}

async function onKill(row: SessionRow) {
  if (!props.instance) return
  if (!confirm(`断开会话 ${row.connectionId}？（仅关闭客户端腿）`)) return
  try {
    await killSession(props.instance.id, row.connectionId)
    toast('已断开客户端会话')
    await loadSessions()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}

async function loadRecent() {
  if (!props.instance) return
  recentLoading.value = true
  recentError.value = null
  try {
    const body = await listRecentStatements(props.instance.id, 50)
    recent.value = body.entries || []
    recentNote.value = body.note || null
  } catch (e) {
    recentError.value = e instanceof Error ? e.message : String(e)
    recent.value = []
  } finally {
    recentLoading.value = false
  }
}

async function onHealthCheck() {
  if (!props.instance) return
  healthBusy.value = true
  try {
    health.value = await healthCheck(props.instance.id)
    toast(health.value.ok ? `后端可达 ${health.value.latencyMs}ms` : (health.value.message || '探测失败'))
  } catch (e) {
    health.value = null
    toast(e instanceof Error ? e.message : String(e))
  } finally {
    healthBusy.value = false
  }
}

onMounted(() => {
  syncEditForm()
  loadMetrics()
  loadRules()
})
watch(
  () => props.instance?.id,
  () => {
    tab.value = 'info'
    resetForm()
    health.value = null
    syncEditForm()
    loadMetrics()
    loadRules()
  },
)
watch(tab, (v) => {
  if (v === 'sessions') loadSessions()
  if (v === 'recent') loadRecent()
})
</script>

<template>
  <div v-if="instance" class="overlay" @click.self="$emit('close')">
    <aside class="drawer">
      <header>
        <div>
          <h2>{{ instance.name }}</h2>
          <div class="sub">{{ instance.id }} · {{ instance.dbType }}</div>
        </div>
        <button @click="$emit('close')">关闭</button>
      </header>

      <nav class="tabs">
        <button :class="{ active: tab === 'info' }" @click="tab = 'info'">概览</button>
        <button :class="{ active: tab === 'sessions' }" @click="tab = 'sessions'">会话</button>
        <button :class="{ active: tab === 'recent' }" @click="tab = 'recent'">最近语句</button>
        <button :class="{ active: tab === 'masking' }" @click="tab = 'masking'">脱敏规则</button>
      </nav>

      <template v-if="tab === 'info'">
        <section>
          <h4>状态</h4>
          <p>
            <span class="badge" :class="'status-' + instance.status">{{ instance.status }}</span>
            <span class="badge">{{ instance.source === 'console' ? '管控台(H2)' : 'YAML' }}</span>
            <span
              v-if="pool"
              class="badge"
              :class="pool.enabled ? 'ok' : ''"
              :title="'maxIdle=' + pool.maxIdle"
            >连接池 · {{ pool.enabled ? '开' : '关' }}<template v-if="pool.enabled"> · idle={{ pool.idleCount }}</template></span>
          </p>
          <p class="muted">{{ instance.message }}</p>
          <div class="health-row">
            <button type="button" :disabled="healthBusy" @click="onHealthCheck">
              {{ healthBusy ? '探测中…' : '探测后端' }}
            </button>
            <span
              v-if="health"
              class="badge"
              :class="health.ok ? 'ok' : 'bad'"
            >{{ health.ok ? '可达' : '不可达' }} · {{ health.latencyMs }}ms</span>
            <span v-if="health" class="muted tiny">{{ health.message }}</span>
          </div>
        </section>

        <section>
          <h4>编辑实例</h4>
          <p v-if="instance.source !== 'console'" class="muted tiny">
            此实例来自配置文件（YAML），只读。如需修改请先「克隆」为管控台实例；配置实例仅可停止。
          </p>
          <form v-else class="edit-form" @submit.prevent="submitEdit">
            <div class="grid2">
              <div class="field"><label>名称</label><input v-model="editForm.name" required /></div>
              <div class="field"><label>类型（不可改）</label><input :value="instance.dbType" disabled /></div>
              <div class="field"><label>监听 Host</label><input v-model="editForm.listenHost" /></div>
              <div class="field"><label>监听 Port</label><input v-model.number="editForm.listenPort" type="number" required min="1" max="65535" /></div>
              <div class="field"><label>目标 Host</label><input v-model="editForm.targetHost" required /></div>
              <div class="field"><label>目标 Port</label><input v-model.number="editForm.targetPort" type="number" required /></div>
              <div class="field"><label>目标库名</label><input v-model="editForm.targetDatabase" /></div>
              <div class="field"><label>目标用户</label><input v-model="editForm.targetUsername" /></div>
              <div class="field"><label>目标密码</label>
                <input v-model="editForm.targetPassword" type="password" autocomplete="new-password"
                       :placeholder="instance.passwordConfigured ? '留空则保留原密码' : '未配置，可在此设置'" />
              </div>
            </div>
            <label class="check"><input v-model="editForm.enabled" type="checkbox" /> 启用</label>
            <p class="muted tiny">运行中保存将：停止 → 按新配置重建 → 同 id 再启动。密码留空保留原值。</p>
            <div class="edit-actions">
              <button class="primary" type="submit" :disabled="editSaving">{{ editSaving ? '保存中…' : '保存修改' }}</button>
            </div>
          </form>
          <dl v-if="instance.source !== 'console'">
            <div><dt>监听</dt><dd>{{ instance.listenHost }}:{{ instance.listenPort }}</dd></div>
            <div><dt>目标</dt><dd>{{ instance.targetHost }}:{{ instance.targetPort }}</dd></div>
            <div><dt>库名</dt><dd>{{ instance.targetDatabase || '—' }}</dd></div>
            <div><dt>用户</dt><dd>{{ instance.targetUsername || '—' }}</dd></div>
            <div><dt>密码</dt><dd>{{ instance.passwordConfigured ? '已配置' : '未配置' }}</dd></div>
          </dl>
        </section>

        <section>
          <h4>实例指标</h4>
          <dl>
            <div v-for="(v, k) in metrics" :key="k">
              <dt>{{ k }}</dt><dd>{{ v }}</dd>
            </div>
            <p v-if="!Object.keys(metrics).length" class="muted">暂无计数</p>
          </dl>
        </section>

        <footer class="drawer-foot">
          <button type="button" @click="emit('clone')">克隆</button>
          <button v-if="instance.source === 'console'" class="danger" @click="emit('delete')">删除实例</button>
          <p class="muted tiny">克隆会生成新的管控台实例（可改端口）；删除仅管控台来源可用。</p>
        </footer>
      </template>

      <template v-else-if="tab === 'sessions'">
        <section>
          <div class="schema-head">
            <h4>活跃会话</h4>
            <button type="button" :disabled="sessionsLoading" @click="loadSessions">
              {{ sessionsLoading ? '刷新中…' : '刷新' }}
            </button>
          </div>
          <p class="muted tiny">对标 MaxGUI processlist / PgBouncer SHOW CLIENTS；Kill 仅关闭客户端腿。</p>
          <p v-if="sessionsError" class="err">{{ sessionsError }}</p>
          <p v-else-if="!sessions.length" class="muted">暂无会话</p>
          <table v-else class="mini">
            <thead>
              <tr>
                <th>连接 ID</th><th>用户</th><th>库</th><th>状态</th><th>事务</th><th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in sessions" :key="s.connectionId">
                <td class="mono">{{ s.connectionId }}</td>
                <td>{{ s.clientUser || '—' }}</td>
                <td>{{ s.clientDatabase || '—' }}</td>
                <td>{{ s.state }}</td>
                <td>{{ s.inTransaction ? '是' : '否' }}</td>
                <td><button type="button" class="danger" @click="onKill(s)">断开</button></td>
              </tr>
            </tbody>
          </table>
        </section>
      </template>

      <template v-else-if="tab === 'recent'">
        <section>
          <div class="schema-head">
            <h4>最近语句</h4>
            <button type="button" :disabled="recentLoading" @click="loadRecent">
              {{ recentLoading ? '刷新中…' : '刷新' }}
            </button>
          </div>
          <p class="muted tiny">{{ recentNote || '内存环，重启丢失；不能替代审计 spool。' }}</p>
          <p v-if="recentError" class="err">{{ recentError }}</p>
          <p v-else-if="!recent.length" class="muted">暂无观测事件</p>
          <ul v-else class="rule-list">
            <li v-for="(e, idx) in recent" :key="idx">
              <div class="rule-head">
                <span class="badge">{{ e.operation || e.eventType }}</span>
                <span class="muted tiny">{{ e.observedAt }}</span>
              </div>
              <pre class="stmt">{{ e.statement }}</pre>
              <div class="muted tiny">session {{ e.sessionId }}</div>
            </li>
          </ul>
        </section>
      </template>

      <template v-else-if="tab === 'masking'">
        <section>
          <h4>已配置规则</h4>
          <p class="muted tiny">
            协议无关 · 挂在本实例 MaskingEngine；写操作后热更新（不停监听端口；已有会话保持旧规则至重连）。
            加密策略需脱敏密钥（yaml 或「运维 → 安全」）。
          </p>
          <p v-if="rulesLoading" class="muted">加载中…</p>
          <p v-else-if="rulesError" class="err">{{ rulesError }}</p>
          <div v-else-if="!rules.length" class="muted">暂无规则（透明转发）</div>
          <ul v-else class="rule-list">
            <li v-for="r in rules" :key="r.id">
              <div class="rule-head">
                <strong>{{ r.name }}</strong>
                <span class="badge">{{ strategyLabel(r.strategy) }}</span>
                <span v-if="!r.enabled" class="badge">停用</span>
              </div>
              <div class="muted tiny">
                列 {{ r.columnName || r.namePattern || '—' }}
                <template v-if="r.tableName"> · 表 {{ r.tableName }}</template>
                · 优先级 {{ r.priority }}
              </div>
              <div class="rule-actions">
                <button type="button" @click="startEdit(r)">编辑</button>
                <button type="button" class="danger" @click="removeRule(r)">删除</button>
              </div>
            </li>
          </ul>
        </section>

        <section>
          <h4>{{ editingId ? '编辑规则' : '新增规则' }}</h4>
          <form class="rule-form" @submit.prevent="submitRule">
            <div class="field">
              <label>名称</label>
              <input v-model="form.name" required placeholder="如 email-null" />
            </div>
            <div class="field">
              <label>策略</label>
              <select v-model="form.strategy" required>
                <option v-for="o in strategyOptions" :key="o.value" :value="o.value">
                  {{ o.label }} ({{ o.value }})
                </option>
              </select>
            </div>
            <div class="grid2">
              <div class="field">
                <label>列名（精确）</label>
                <input v-model="form.columnName" placeholder="email" />
              </div>
              <div class="field">
                <label>表名（可选）</label>
                <input v-model="form.tableName" placeholder="users" />
              </div>
            </div>
            <div class="field">
              <label>列名正则（与列名二选一）</label>
              <input v-model="form.namePattern" placeholder=".*phone.*" />
            </div>
            <div class="grid2">
              <div class="field">
                <label>优先级</label>
                <input v-model.number="form.priority" type="number" />
              </div>
              <div class="field check">
                <label><input v-model="form.enabled" type="checkbox" /> 启用</label>
              </div>
            </div>

            <div v-if="showStrategyFields === 'fixed'" class="field">
              <label>固定值</label>
              <input v-model="form.fixedValue" placeholder="REDACTED" />
            </div>
            <div v-if="showStrategyFields === 'partial'" class="grid2">
              <div class="field">
                <label>保留前缀</label>
                <input v-model.number="form.keepPrefix" type="number" min="0" />
              </div>
              <div class="field">
                <label>保留后缀</label>
                <input v-model.number="form.keepSuffix" type="number" min="0" />
              </div>
            </div>
            <div v-if="showStrategyFields === 'hash'" class="field">
              <label>哈希十六进制长度 (1–64)</label>
              <input v-model.number="form.hashHexLength" type="number" min="1" max="64" />
            </div>
            <p v-if="showStrategyFields === 'encrypt'" class="muted tiny">
              使用脱敏密钥加密（AES-GCM）：yaml <code>gateway.masking.key-base64</code> 或「运维 → 安全」配置；控制台不回显密钥。
            </p>

            <div class="schema-hint">
              <div class="schema-head">
                <strong>列提示</strong>
                <span class="muted tiny">服务端 JDBC 元数据（协议无关）</span>
              </div>
              <div class="schema-row">
                <input v-model="schemaTableFilter" placeholder="表名过滤（可选）" />
                <button type="button" :disabled="schemaLoading" @click="loadSchemaHints">
                  {{ schemaLoading ? '拉取中…' : '拉取列' }}
                </button>
              </div>
              <p v-if="schemaError" class="err tiny">{{ schemaError }}</p>
              <div v-else-if="schemaColumns.length" class="schema-list">
                <button
                  v-for="c in schemaColumns.slice(0, 40)"
                  :key="c.table + '.' + c.name"
                  type="button"
                  class="chip"
                  @click="pickColumn(c)"
                >
                  {{ c.table }}.{{ c.name }}
                  <span class="muted">{{ c.typeName }}</span>
                </button>
                <p v-if="schemaColumns.length > 40" class="muted tiny">仅显示前 40 列，请缩小表过滤</p>
              </div>
            </div>

            <div class="form-actions">
              <button type="submit" class="primary" :disabled="saving">
                {{ editingId ? '保存' : '新增' }}
              </button>
              <button v-if="editingId" type="button" @click="resetForm">取消编辑</button>
            </div>
          </form>
        </section>
      </template>
    </aside>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.45);
  display: flex; justify-content: flex-end; z-index: 40;
}
.drawer {
  width: min(480px, 100%);
  background: var(--bg-elevated);
  border-left: 1px solid var(--border);
  padding: 1.25rem;
  overflow: auto;
  box-shadow: var(--shadow);
}
header { display: flex; justify-content: space-between; align-items: flex-start; }
h2 { margin: 0; }
.sub, .muted { color: var(--text-muted); font-size: 0.85rem; }
.tabs { display: flex; gap: 0.5rem; margin-top: 1rem; }
.tabs button.active {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
section { margin-top: 1.25rem; }
h4 { margin: 0 0 0.5rem; font-size: 0.9rem; color: var(--text-muted); }
dl { margin: 0; }
dl > div { display: flex; justify-content: space-between; gap: 1rem; padding: 0.35rem 0; border-bottom: 1px solid var(--border); }
dt { color: var(--text-muted); }
dd { margin: 0; }
footer { margin-top: 1.5rem; }
.tiny { font-size: 0.75rem; }
.err { color: var(--danger); }
.rule-list { list-style: none; margin: 0; padding: 0; }
.rule-list li {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 0.65rem 0.75rem;
  margin-bottom: 0.5rem;
}
.rule-head { display: flex; gap: 0.4rem; align-items: center; flex-wrap: wrap; }
.rule-actions { display: flex; gap: 0.4rem; margin-top: 0.5rem; }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0.65rem; }
.form-actions { display: flex; gap: 0.5rem; margin-top: 0.5rem; }
.check { display: flex; align-items: flex-end; padding-bottom: 0.4rem; }
.check label { display: flex; gap: 0.4rem; align-items: center; color: var(--text); }
code { font-size: 0.8em; }
.schema-hint {
  margin-top: 0.75rem;
  padding: 0.65rem;
  border: 1px dashed var(--border);
  border-radius: 8px;
}
.schema-head { display: flex; justify-content: space-between; gap: 0.5rem; margin-bottom: 0.4rem; }
.schema-row { display: flex; gap: 0.4rem; }
.schema-row input { flex: 1; }
.schema-list { display: flex; flex-wrap: wrap; gap: 0.35rem; margin-top: 0.5rem; }
.chip {
  font-size: 0.75rem;
  padding: 0.25rem 0.45rem;
  border-radius: 999px;
  background: var(--accent-soft);
}

.health-row { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; margin-top: 0.5rem; }
.badge.ok { background: #1b5e20; color: #c8e6c9; }
.badge.bad { background: #b71c1c; color: #ffcdd2; }
table.mini { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
table.mini th, table.mini td { border-bottom: 1px solid var(--border); padding: 0.35rem 0.25rem; text-align: left; }
.mono { font-family: ui-monospace, monospace; font-size: 0.75rem; word-break: break-all; }
.stmt {
  background: var(--bg);
  border-radius: 6px;
  padding: 0.4rem 0.5rem;
  font-size: 0.75rem;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0.35rem 0;
}

.edit-form .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0.45rem 0.75rem; }
.edit-form .field label { display: block; font-size: 0.75rem; color: var(--text-muted); margin-bottom: 0.15rem; }
.edit-form .check { display: flex; align-items: center; gap: 0.4rem; margin: 0.6rem 0; }
.edit-form .check input { width: auto; }
.edit-actions { display: flex; gap: 0.5rem; }
.drawer-foot { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; margin-top: 1rem; }
@media (max-width: 640px) { .edit-form .grid2 { grid-template-columns: 1fr; } }
</style>
