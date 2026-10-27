<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import type { ConfirmFn } from '../composables/useConfirm'
import {
  createAlertChannel,
  createAlertThreshold,
  deleteAlertChannel,
  deleteAlertThreshold,
  listActiveAlerts,
  listAlertChannels,
  listAlertThresholds,
  listInstances,
  testAlertChannel,
  updateAlertChannel,
  updateAlertThreshold,
} from '../api/consoleApi'
import type { ActiveAlert, AlertChannel, AlertThreshold, GatewayInstance } from '../api/types'
import { usePolling } from '../composables/usePolling'
import { usePermissions, Perm } from '../composables/usePermissions'

const { has } = usePermissions()
const askConfirm = inject<ConfirmFn>('confirm', async () => false)

const thresholds = ref<AlertThreshold[]>([])
const active = ref<ActiveAlert[]>([])
const channels = ref<AlertChannel[]>([])
const metricKeys = ref<string[]>([])
const comparators = ref<string[]>(['GT', 'GTE', 'LT', 'LTE', 'EQ'])
const severities = ref<string[]>(['INFO', 'WARN', 'CRITICAL'])
const channelTypes = ref<string[]>(['WEBHOOK', 'EMAIL'])
const instances = ref<GatewayInstance[]>([])
const note = ref<string | null>(null)
const channelNote = ref<string | null>(null)
const evaluatedAt = ref<string | null>(null)
const error = ref<string | null>(null)
const msg = ref<string | null>(null)
const busy = ref(false)

const editingId = ref<string | null>(null)
const form = ref({
  name: '',
  metricKey: 'deny_count',
  comparator: 'GTE',
  thresholdValue: 1,
  windowSeconds: null as number | null,
  instanceId: '',
  enabled: true,
  severity: 'WARN',
})

const editingChannelId = ref<string | null>(null)
const channelForm = ref({
  name: '',
  type: 'WEBHOOK',
  enabled: true,
  minSeverity: 'WARN',
  cooldownSeconds: 300,
  url: '',
  authHeader: '',
  timeoutMs: 8000,
  smtpHost: '',
  smtpPort: 587,
  username: '',
  password: '',
  from: '',
  to: '',
  startTls: true,
  ssl: false,
  subjectPrefix: '[db-gateway-alert]',
})

const metricLabels: Record<string, string> = {
  active_sessions: '活跃会话 (gauge)',
  deny_count: '策略拒绝次数',
  backend_fail: '后端 failover',
  error_rate: '错误合计（拒绝类）',
  connections_accepted: '接受连接',
  connections_rejected_limit: '限流拒绝',
  connections_rejected_policy: 'CIDR 策略拒绝',
  opaque_tunnels_denied: 'opaque 隧道拒绝',
  opaque_tunnels_entered: 'opaque 隧道进入',
}

const activeCritical = computed(() => active.value.filter((a) => a.severity === 'CRITICAL').length)

function resetForm() {
  editingId.value = null
  form.value = {
    name: '',
    metricKey: metricKeys.value[0] || 'deny_count',
    comparator: 'GTE',
    thresholdValue: 1,
    windowSeconds: null,
    instanceId: '',
    enabled: true,
    severity: 'WARN',
  }
}

function resetChannelForm() {
  editingChannelId.value = null
  channelForm.value = {
    name: '',
    type: 'WEBHOOK',
    enabled: true,
    minSeverity: 'WARN',
    cooldownSeconds: 300,
    url: '',
    authHeader: '',
    timeoutMs: 8000,
    smtpHost: '',
    smtpPort: 587,
    username: '',
    password: '',
    from: '',
    to: '',
    startTls: true,
    ssl: false,
    subjectPrefix: '[db-gateway-alert]',
  }
}

function editRow(t: AlertThreshold) {
  editingId.value = t.id
  form.value = {
    name: t.name || '',
    metricKey: t.metricKey,
    comparator: t.comparator,
    thresholdValue: Number(t.thresholdValue),
    windowSeconds: t.windowSeconds ?? null,
    instanceId: t.instanceId || '',
    enabled: !!t.enabled,
    severity: t.severity || 'WARN',
  }
}

function editChannel(c: AlertChannel) {
  editingChannelId.value = c.id
  const cfg = c.config || {}
  channelForm.value = {
    name: c.name || '',
    type: String(c.type || 'WEBHOOK'),
    enabled: !!c.enabled,
    minSeverity: c.minSeverity || 'WARN',
    cooldownSeconds: c.cooldownSeconds ?? 300,
    url: String(cfg.url || ''),
    authHeader: '',
    timeoutMs: Number(cfg.timeoutMs ?? 8000),
    smtpHost: String(cfg.smtpHost || ''),
    smtpPort: Number(cfg.smtpPort ?? 587),
    username: String(cfg.username || ''),
    password: '',
    from: String(cfg.from || ''),
    to: Array.isArray(cfg.to) ? (cfg.to as string[]).join(', ') : String(cfg.to || ''),
    startTls: cfg.startTls !== false,
    ssl: !!cfg.ssl,
    subjectPrefix: String(cfg.subjectPrefix || '[db-gateway-alert]'),
  }
}

async function load() {
  try {
    const [thr, act, inst, ch] = await Promise.all([
      listAlertThresholds(),
      listActiveAlerts(true),
      listInstances(),
      listAlertChannels(),
    ])
    thresholds.value = thr.items || []
    metricKeys.value = thr.metricKeys || metricKeys.value
    comparators.value = thr.comparators || comparators.value
    severities.value = thr.severities || severities.value
    note.value = thr.note || act.note || null
    active.value = act.items || []
    evaluatedAt.value = act.evaluatedAt || null
    instances.value = inst.items || []
    channels.value = ch.items || []
    channelTypes.value = ch.types || channelTypes.value
    channelNote.value = ch.note || null
    error.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

usePolling(load, 6000)

async function save() {
  busy.value = true
  msg.value = null
  try {
    const payload = {
      name: form.value.name.trim() || undefined,
      metricKey: form.value.metricKey,
      comparator: form.value.comparator,
      thresholdValue: Number(form.value.thresholdValue),
      windowSeconds: form.value.windowSeconds != null && form.value.windowSeconds > 0
        ? Number(form.value.windowSeconds)
        : null,
      instanceId: form.value.instanceId.trim() || null,
      enabled: form.value.enabled,
      severity: form.value.severity,
    }
    if (editingId.value) {
      await updateAlertThreshold(editingId.value, payload)
      msg.value = '阈值已更新'
    } else {
      await createAlertThreshold(payload)
      msg.value = '阈值已创建'
    }
    resetForm()
    await load()
  } catch (e) {
    msg.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

function buildChannelPayload() {
  const f = channelForm.value
  const config: Record<string, unknown> = {}
  if (f.type === 'EMAIL') {
    config.smtpHost = f.smtpHost.trim()
    config.smtpPort = Number(f.smtpPort) || 587
    config.from = f.from.trim()
    config.to = f.to.split(/[,;\s]+/).map((s) => s.trim()).filter(Boolean)
    config.username = f.username.trim() || undefined
    if (f.password.trim()) config.password = f.password.trim()
    config.startTls = !!f.startTls
    config.ssl = !!f.ssl
    config.subjectPrefix = f.subjectPrefix.trim() || '[db-gateway-alert]'
  } else {
    config.url = f.url.trim()
    config.method = 'POST'
    config.timeoutMs = Number(f.timeoutMs) || 8000
    if (f.authHeader.trim()) config.authHeader = f.authHeader.trim()
  }
  return {
    name: f.name.trim() || undefined,
    type: f.type,
    enabled: f.enabled,
    minSeverity: f.minSeverity,
    cooldownSeconds: Number(f.cooldownSeconds) || 0,
    config,
  }
}

async function saveChannel() {
  if (!has(Perm.ALERTS_WRITE)) { msg.value = '需要 alerts:write'; return }
  busy.value = true
  msg.value = null
  try {
    const payload = buildChannelPayload()
    if (editingChannelId.value) {
      await updateAlertChannel(editingChannelId.value, payload)
      msg.value = '通道已更新'
    } else {
      await createAlertChannel(payload)
      msg.value = '通道已创建'
    }
    resetChannelForm()
    await load()
  } catch (e) {
    msg.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function remove(id: string) {
  if (!has(Perm.ALERTS_WRITE)) { msg.value = '需要 alerts:write'; return }
  const ok = await askConfirm({
    title: '删除告警阈值',
    message: '删除该告警阈值？',
    danger: true,
    confirmLabel: '删除',
  })
  if (!ok) return
  busy.value = true
  try {
    await deleteAlertThreshold(id)
    if (editingId.value === id) resetForm()
    await load()
    msg.value = '已删除'
  } catch (e) {
    msg.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function removeChannel(id: string) {
  if (!has(Perm.ALERTS_WRITE)) { msg.value = '需要 alerts:write'; return }
  const ok = await askConfirm({
    title: '删除告警通道',
    message: '删除该通知通道？',
    danger: true,
    confirmLabel: '删除',
  })
  if (!ok) return
  busy.value = true
  try {
    await deleteAlertChannel(id)
    if (editingChannelId.value === id) resetChannelForm()
    await load()
    msg.value = '通道已删除'
  } catch (e) {
    msg.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function runChannelTest(id: string) {
  if (!has(Perm.ALERTS_WRITE)) { msg.value = '需要 alerts:write'; return }
  busy.value = true
  try {
    const r = await testAlertChannel(id)
    msg.value = r.ok ? `自检成功：${r.message || 'ok'}` : `自检失败：${r.message || 'error'}`
    await load()
  } catch (e) {
    msg.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

function sevClass(s?: string) {
  if (s === 'CRITICAL') return 'sev-crit'
  if (s === 'WARN') return 'sev-warn'
  return 'sev-info'
}
</script>

<template>
  <div>
    <p v-if="error" class="err">加载失败：{{ error }}</p>

    <div class="panel banner" :class="{ hot: active.length > 0 }">
      <div>
        <strong>当前触发</strong>
        <span class="badge" :class="{ on: active.length > 0 }">{{ active.length }}</span>
        <span v-if="activeCritical" class="badge crit">严重 {{ activeCritical }}</span>
        <span class="muted tiny" v-if="evaluatedAt">评估于 {{ evaluatedAt }}</span>
      </div>
      <p class="muted tiny">
        {{ note || '进程内评估；阈值上升沿可外发 webhook / 邮箱；非 Prometheus / 非 PagerDuty。' }}
      </p>
    </div>

    <div class="panel">
      <h3>当前触发告警</h3>
      <table v-if="active.length" class="tbl">
        <thead>
          <tr>
            <th>级别</th>
            <th>名称</th>
            <th>指标</th>
            <th>当前值</th>
            <th>阈值</th>
            <th>作用域</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in active" :key="a.thresholdId">
            <td><span class="pill" :class="sevClass(a.severity)">{{ a.severity }}</span></td>
            <td>{{ a.name }}</td>
            <td class="mono">{{ a.metricKey }}</td>
            <td>{{ a.value }} <span class="muted tiny">({{ a.valueMode }})</span></td>
            <td class="mono">{{ a.comparator }} {{ a.thresholdValue }}</td>
            <td>{{ a.instanceId || '全局' }}</td>
            <td class="tiny">{{ a.message }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">暂无触发中的告警</p>
    </div>

    <div class="grid">
      <div class="panel">
        <h3>阈值列表</h3>
        <table v-if="thresholds.length" class="tbl">
          <thead>
            <tr>
              <th>启用</th>
              <th>名称</th>
              <th>指标</th>
              <th>条件</th>
              <th>窗口</th>
              <th>作用域</th>
              <th>上次</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in thresholds" :key="t.id" :class="{ dim: !t.enabled }">
              <td>{{ t.enabled ? '是' : '否' }}</td>
              <td>
                <span class="pill" :class="sevClass(t.severity)">{{ t.severity }}</span>
                {{ t.name }}
              </td>
              <td class="mono">{{ t.metricKey }}</td>
              <td class="mono">{{ t.comparator }} {{ t.thresholdValue }}</td>
              <td>{{ t.windowSeconds ? t.windowSeconds + 's' : '—' }}</td>
              <td>{{ t.instanceId || '全局' }}</td>
              <td class="tiny">
                <span v-if="t.lastFiring" class="hot-dot">触发中</span>
                <span v-else-if="t.lastFiredAt">曾触发</span>
                <span v-else>—</span>
                <div v-if="t.lastValue != null">值 {{ t.lastValue }}</div>
              </td>
              <td class="row">
                <button type="button" @click="editRow(t)">编辑</button>
                <button type="button" @click="remove(t.id)">删</button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted">尚未配置阈值。右侧表单可新建（对齐 OPS 告警启发式）。</p>
      </div>

      <div class="panel">
        <h3>{{ editingId ? '编辑阈值' : '新建阈值' }}</h3>
        <p v-if="!has(Perm.ALERTS_WRITE)" class="muted">只读：需要 alerts:write 才能修改阈值。</p>
        <form class="form" @submit.prevent="save">
          <label>
            名称
            <input v-model="form.name" placeholder="可选，默认自动生成" />
          </label>
          <label>
            指标
            <select v-model="form.metricKey">
              <option v-for="k in metricKeys" :key="k" :value="k">
                {{ metricLabels[k] || k }}
              </option>
            </select>
          </label>
          <div class="row2">
            <label>
              比较
              <select v-model="form.comparator">
                <option v-for="c in comparators" :key="c" :value="c">{{ c }}</option>
              </select>
            </label>
            <label>
              阈值
              <input v-model.number="form.thresholdValue" type="number" step="any" required />
            </label>
          </div>
          <label>
            窗口秒数（计数器增量；空=绝对值 / gauge）
            <input v-model.number="form.windowSeconds" type="number" min="0" placeholder="可选" />
          </label>
          <label>
            作用域实例（空=全局求和）
            <select v-model="form.instanceId">
              <option value="">全局（overview）</option>
              <option v-for="i in instances" :key="i.id" :value="i.id">
                {{ i.name }} ({{ i.id }})
              </option>
            </select>
          </label>
          <label>
            严重级别
            <select v-model="form.severity">
              <option v-for="s in severities" :key="s" :value="s">{{ s }}</option>
            </select>
          </label>
          <label class="check">
            <input v-model="form.enabled" type="checkbox" />
            启用
          </label>
          <div class="row">
            <button type="submit" class="primary" :disabled="busy || !has(Perm.ALERTS_WRITE)" :title="!has(Perm.ALERTS_WRITE) ? '需要 alerts:write' : ''">
              {{ editingId ? '保存' : '创建' }}
            </button>
            <button v-if="editingId" type="button" :disabled="busy" @click="resetForm">取消</button>
          </div>
        </form>
        <p v-if="msg" class="msg">{{ msg }}</p>
        <p class="muted tiny" style="margin-top: 0.75rem">
          建议对齐 <code>docs/OPS.md</code>：如 <code>deny_count</code> /
          <code>backend_fail</code> / <code>connections_rejected_*</code> 持续上升时告警。
        </p>
      </div>
    </div>

    <div class="grid">
      <div class="panel">
        <h3>通知通道</h3>
        <p class="muted tiny">{{ channelNote || 'WEBHOOK=HTTP JSON；EMAIL=SMTP；阈值上升沿触发，冷却期内不重复。' }}</p>
        <table v-if="channels.length" class="tbl">
          <thead>
            <tr>
              <th>启用</th>
              <th>名称</th>
              <th>类型</th>
              <th>最低级别</th>
              <th>冷却</th>
              <th>上次投递</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in channels" :key="c.id" :class="{ dim: !c.enabled }">
              <td>{{ c.enabled ? '是' : '否' }}</td>
              <td>{{ c.name }}</td>
              <td class="mono">{{ c.type }}</td>
              <td><span class="pill" :class="sevClass(c.minSeverity)">{{ c.minSeverity }}</span></td>
              <td>{{ c.cooldownSeconds }}s</td>
              <td class="tiny">
                <span v-if="c.lastOk === true" class="ok-dot">成功</span>
                <span v-else-if="c.lastOk === false" class="hot-dot">失败</span>
                <span v-else>—</span>
                <div v-if="c.lastSentAt">{{ c.lastSentAt }}</div>
                <div v-if="c.lastError" class="err">{{ c.lastError }}</div>
              </td>
              <td class="row">
                <button type="button" @click="editChannel(c)">编辑</button>
                <button type="button" :disabled="busy || !has(Perm.ALERTS_WRITE)" @click="runChannelTest(c.id)">自检</button>
                <button type="button" @click="removeChannel(c.id)">删</button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted">尚未配置通知通道。右侧可新建 Webhook 或邮箱。</p>
      </div>

      <div class="panel">
        <h3>{{ editingChannelId ? '编辑通道' : '新建通道' }}</h3>
        <form class="form" @submit.prevent="saveChannel">
          <label>
            名称
            <input v-model="channelForm.name" placeholder="ops-webhook / ops-mail" />
          </label>
          <label>
            类型
            <select v-model="channelForm.type">
              <option v-for="t in channelTypes" :key="t" :value="t">{{ t }}</option>
            </select>
          </label>
          <div class="row2">
            <label>
              最低级别
              <select v-model="channelForm.minSeverity">
                <option v-for="s in severities" :key="s" :value="s">{{ s }}</option>
              </select>
            </label>
            <label>
              冷却秒数
              <input v-model.number="channelForm.cooldownSeconds" type="number" min="0" />
            </label>
          </div>

          <template v-if="channelForm.type === 'WEBHOOK'">
            <label>
              Webhook URL
              <input v-model="channelForm.url" placeholder="https://hooks.example/alert" required />
            </label>
            <label>
              Authorization（可选；留空则保留原值）
              <input v-model="channelForm.authHeader" placeholder="Bearer …" autocomplete="off" />
            </label>
            <label>
              超时毫秒
              <input v-model.number="channelForm.timeoutMs" type="number" min="1000" />
            </label>
          </template>

          <template v-else>
            <label>
              SMTP Host
              <input v-model="channelForm.smtpHost" placeholder="smtp.example.com" required />
            </label>
            <div class="row2">
              <label>
                端口
                <input v-model.number="channelForm.smtpPort" type="number" min="1" />
              </label>
              <label>
                发件人 From
                <input v-model="channelForm.from" placeholder="alerts@example.com" required />
              </label>
            </div>
            <label>
              收件人 To（逗号分隔）
              <input v-model="channelForm.to" placeholder="ops@example.com" required />
            </label>
            <div class="row2">
              <label>
                SMTP 用户名
                <input v-model="channelForm.username" autocomplete="off" />
              </label>
              <label>
                SMTP 密码（留空保留原值）
                <input v-model="channelForm.password" type="password" autocomplete="new-password" />
              </label>
            </div>
            <label>
              主题前缀
              <input v-model="channelForm.subjectPrefix" />
            </label>
            <label class="check">
              <input v-model="channelForm.startTls" type="checkbox" />
              STARTTLS
            </label>
            <label class="check">
              <input v-model="channelForm.ssl" type="checkbox" />
              SSL（smtps）
            </label>
          </template>

          <label class="check">
            <input v-model="channelForm.enabled" type="checkbox" />
            启用
          </label>
          <div class="row">
            <button type="submit" class="primary" :disabled="busy || !has(Perm.ALERTS_WRITE)">
              {{ editingChannelId ? '保存通道' : '创建通道' }}
            </button>
            <button v-if="editingChannelId" type="button" :disabled="busy" @click="resetChannelForm">取消</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<style scoped>
.panel {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 1rem 1.1rem;
  margin-bottom: 1rem;
  box-shadow: var(--shadow-sm);
}
.banner.hot { border-color: var(--danger); background: var(--danger-soft); }
.badge {
  display: inline-block;
  margin-left: 0.5rem;
  padding: 0.1rem 0.55rem;
  border-radius: 999px;
  background: var(--bg-elevated, #1e293b);
  font-size: 0.8rem;
}
.badge.on { background: var(--danger); color: #fff; }
.badge.crit { background: var(--danger); color: #fff; }
.grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 1rem; }
@media (max-width: 1000px) { .grid { grid-template-columns: 1fr; } }
.tbl { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
.tbl th, .tbl td { border-bottom: 1px solid var(--border); padding: 0.4rem 0.35rem; text-align: left; vertical-align: top; }
.dim { opacity: 0.55; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
.tiny { font-size: 0.75rem; }
.muted { color: var(--text-muted); }
.err { color: var(--danger, #ef4444); }
.row { display: flex; gap: 0.4rem; flex-wrap: wrap; align-items: center; }
.row2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0.5rem; }
.form { display: grid; gap: 0.55rem; }
.form label { display: grid; gap: 0.25rem; font-size: 0.8rem; color: var(--text-muted); }
.form input, .form select { width: 100%; box-sizing: border-box; }
.check { display: flex !important; flex-direction: row !important; align-items: center; gap: 0.4rem; }
.pill {
  display: inline-block;
  padding: 0.05rem 0.4rem;
  border-radius: 999px;
  font-size: 0.7rem;
  margin-right: 0.25rem;
}
.sev-crit { background: var(--sev-crit-bg); color: var(--sev-crit-fg); }
.sev-warn { background: var(--sev-warn-bg); color: var(--sev-warn-fg); }
.sev-info { background: var(--sev-info-bg); color: var(--sev-info-fg); }
.hot-dot { color: var(--danger); font-weight: 600; }
.ok-dot { color: var(--success, #16a34a); font-weight: 600; }
.msg { margin-top: 0.5rem; }
code { font-size: 0.85em; }
</style>
