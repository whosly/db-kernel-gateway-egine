<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  createAlertThreshold,
  deleteAlertThreshold,
  listActiveAlerts,
  listAlertThresholds,
  listInstances,
  updateAlertThreshold,
} from '../api/consoleApi'
import type { ActiveAlert, AlertThreshold, GatewayInstance } from '../api/types'
import { usePolling } from '../composables/usePolling'

const thresholds = ref<AlertThreshold[]>([])
const active = ref<ActiveAlert[]>([])
const metricKeys = ref<string[]>([])
const comparators = ref<string[]>(['GT', 'GTE', 'LT', 'LTE', 'EQ'])
const severities = ref<string[]>(['INFO', 'WARN', 'CRITICAL'])
const instances = ref<GatewayInstance[]>([])
const note = ref<string | null>(null)
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

async function load() {
  try {
    const [thr, act, inst] = await Promise.all([
      listAlertThresholds(),
      listActiveAlerts(true),
      listInstances(),
    ])
    thresholds.value = thr.items || []
    metricKeys.value = thr.metricKeys || metricKeys.value
    comparators.value = thr.comparators || comparators.value
    severities.value = thr.severities || severities.value
    note.value = thr.note || act.note || null
    active.value = act.items || []
    evaluatedAt.value = act.evaluatedAt || null
    instances.value = inst.items || []
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

async function remove(id: string) {
  if (!confirm('删除该告警阈值？')) return
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
        {{ note || '进程内评估；非 Prometheus / 非 PagerDuty。重启后需再采样刷新触发态。' }}
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
            <button type="submit" class="primary" :disabled="busy">
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
  </div>
</template>

<style scoped>
.panel {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 1rem 1.1rem;
  margin-bottom: 1rem;
}
.banner.hot { border-color: #ef4444; background: rgba(239, 68, 68, 0.08); }
.badge {
  display: inline-block;
  margin-left: 0.5rem;
  padding: 0.1rem 0.55rem;
  border-radius: 999px;
  background: var(--bg-elevated, #1e293b);
  font-size: 0.8rem;
}
.badge.on { background: #ef4444; color: #fff; }
.badge.crit { background: #b91c1c; color: #fff; }
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
.sev-crit { background: #7f1d1d; color: #fecaca; }
.sev-warn { background: #78350f; color: #fde68a; }
.sev-info { background: #1e3a5f; color: #bfdbfe; }
.hot-dot { color: #ef4444; font-weight: 600; }
.msg { margin-top: 0.5rem; }
code { font-size: 0.85em; }
</style>
