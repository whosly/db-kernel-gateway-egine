<script setup lang="ts">
import { computed, inject, onMounted, reactive, ref, watch } from 'vue'
import type { GatewayInstance, MaskingRule, MaskingRulePayload, MaskingStrategy } from '../api/types'
import {
  createMaskingRule,
  deleteMaskingRule,
  getInstanceMetrics,
  listMaskingRules,
  updateMaskingRule,
} from '../api/consoleApi'

const props = defineProps<{ instance: GatewayInstance | null }>()
defineEmits<{ close: []; delete: [] }>()

const toast = inject<(m: string) => void>('toast', () => {})
const tab = ref<'info' | 'masking'>('info')
const metrics = ref<Record<string, number>>({})
const rules = ref<MaskingRule[]>([])
const rulesError = ref<string | null>(null)
const rulesLoading = ref(false)
const editingId = ref<string | null>(null)
const saving = ref(false)

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
  } catch {
    metrics.value = props.instance.metrics || {}
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

onMounted(() => {
  loadMetrics()
  loadRules()
})
watch(
  () => props.instance?.id,
  () => {
    tab.value = 'info'
    resetForm()
    loadMetrics()
    loadRules()
  },
)
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
        <button :class="{ active: tab === 'masking' }" @click="tab = 'masking'">脱敏规则</button>
      </nav>

      <template v-if="tab === 'info'">
        <section>
          <h4>状态</h4>
          <p>
            <span class="badge" :class="'status-' + instance.status">{{ instance.status }}</span>
            <span class="badge">{{ instance.source === 'console' ? '管控台(H2)' : 'YAML' }}</span>
          </p>
          <p class="muted">{{ instance.message }}</p>
        </section>

        <section>
          <h4>监听 / 目标</h4>
          <dl>
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

        <footer v-if="instance.source === 'console'">
          <button class="danger" @click="$emit('delete')">删除实例</button>
          <p class="muted tiny">仅管控台创建的实例可删；将停止 listener、级联删除脱敏规则并移除 H2 行。</p>
        </footer>
      </template>

      <template v-else>
        <section>
          <h4>已配置规则</h4>
          <p class="muted tiny">
            协议无关 · 挂在本实例 MaskingEngine；写操作后热更新（不停监听端口；已有会话保持旧规则至重连）。
            加密策略需进程配置 <code>gateway.masking.key-base64</code>。
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
              使用网关配置密钥加密（AES-GCM）；控制台不展示密钥。未配置密钥时保存将返回 400。
            </p>

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
</style>
