<script setup lang="ts">
import { computed, inject, reactive, ref } from 'vue'
import InstanceCard from '../components/InstanceCard.vue'
import InstanceDrawer from '../components/InstanceDrawer.vue'
import {
  bulkInstances,
  cloneInstance,
  createInstance,
  deleteInstance,
  downloadConfigExport,
  downloadInstancesExport,
  getSecretEncryptionStatus,
  importInstances,
  listInstances,
  listSupportedDatabases,
  startInstance,
  stopInstance,
  updateInstance,
} from '../api/consoleApi'
import type {
  CatalogEntry,
  CreateInstancePayload,
  GatewayInstance,
  SecretEncryptionStatus,
  UpdateInstancePayload,
} from '../api/types'
import { proxyModeBadgeClass, proxyModeHint } from '../api/proxyMode'
import { usePolling } from '../composables/usePolling'

const toast = inject<(m: string) => void>('toast', () => {})
const instances = ref<GatewayInstance[]>([])
const catalog = ref<CatalogEntry[]>([])
const error = ref<string | null>(null)
const selected = ref<GatewayInstance | null>(null)
const showCreate = ref(false)
const secretEnc = ref<SecretEncryptionStatus | null>(null)
const submitting = ref(false)
const selectedIds = ref<Set<string>>(new Set())
const filterQ = ref('')
const filterStatus = ref('')
const filterDbType = ref('')
const sortBy = ref<'name' | 'port' | 'status'>('name')
const importInput = ref<HTMLInputElement | null>(null)

const form = reactive<CreateInstancePayload>({
  id: '',
  name: '',
  dbType: 'mysql',
  listenHost: '0.0.0.0',
  listenPort: 33307,
  targetHost: '127.0.0.1',
  targetPort: 3306,
  targetDatabase: '',
  targetUsername: '',
  targetPassword: '',
  enabled: true,
})

const creatableTypes = computed(() =>
  catalog.value.filter((c) => c.creatable !== false && c.enabled !== false),
)

const dbTypeOptions = computed(() => {
  const set = new Set(instances.value.map((i) => i.dbType).filter(Boolean))
  return Array.from(set).sort()
})

const selectedCreateType = computed(() =>
  catalog.value.find((c) => c.id === form.dbType) || null,
)

const portConflictHint = computed(() => {
  const port = Number(form.listenPort)
  if (!port) return ''
  const hit = instances.value.find((i) => i.enabled && i.listenPort === port)
  return hit ? `端口 ${port} 已被实例「${hit.name}」(${hit.id}) 占用` : ''
})

const filtered = computed(() => {
  let list = [...instances.value]
  const q = filterQ.value.trim().toLowerCase()
  if (q) {
    list = list.filter(
      (i) =>
        i.id.toLowerCase().includes(q) ||
        (i.name || '').toLowerCase().includes(q) ||
        (i.listenHost || '').toLowerCase().includes(q) ||
        (i.targetHost || '').toLowerCase().includes(q),
    )
  }
  if (filterStatus.value) {
    list = list.filter((i) => i.status === filterStatus.value)
  }
  if (filterDbType.value) {
    list = list.filter((i) => i.dbType === filterDbType.value)
  }
  list.sort((a, b) => {
    if (sortBy.value === 'port') return a.listenPort - b.listenPort
    if (sortBy.value === 'status') return a.status.localeCompare(b.status)
    return (a.name || a.id).localeCompare(b.name || b.id, 'zh')
  })
  return list
})

async function load() {
  try {
    try {
      secretEnc.value = await getSecretEncryptionStatus()
    } catch {
      /* optional */
    }
    const [inst, cat] = await Promise.all([listInstances(), listSupportedDatabases()])
    instances.value = inst.items || []
    catalog.value = cat.items || []
    if (selected.value) {
      selected.value = instances.value.find((i) => i.id === selected.value!.id) || null
    }
    // prune selection
    const alive = new Set(instances.value.map((i) => i.id))
    selectedIds.value = new Set([...selectedIds.value].filter((id) => alive.has(id)))
    error.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}
usePolling(load, 6000)

function openCreate() {
  const first = creatableTypes.value[0]
  if (first) {
    form.dbType = first.id
    form.listenPort = first.defaultProxyPort || 33307
    form.targetPort = first.defaultTargetPort || 3306
  }
  showCreate.value = true
}

function onDbTypeChange() {
  const t = catalog.value.find((c) => c.id === form.dbType)
  if (t) {
    form.listenPort = t.defaultProxyPort || form.listenPort
    form.targetPort = t.defaultTargetPort || form.targetPort
  }
}

async function submitCreate() {
  if (portConflictHint.value) {
    toast(portConflictHint.value)
    return
  }
  submitting.value = true
  try {
    const payload: CreateInstancePayload = {
      name: form.name || form.id || '未命名实例',
      dbType: form.dbType,
      listenHost: form.listenHost || '0.0.0.0',
      listenPort: Number(form.listenPort),
      targetHost: form.targetHost,
      targetPort: Number(form.targetPort),
      targetDatabase: form.targetDatabase || undefined,
      targetUsername: form.targetUsername || undefined,
      targetPassword: form.targetPassword || undefined,
      enabled: form.enabled !== false,
    }
    if (form.id && form.id.trim()) payload.id = form.id.trim()
    const created = await createInstance(payload)
    toast(`已创建实例 ${created.id}（已写入 H2）`)
    showCreate.value = false
    form.targetPassword = ''
    await load()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  } finally {
    submitting.value = false
  }
}

async function onStart(id: string) {
  try {
    const r = await startInstance(id)
    toast(`已启动 ${r.id}`)
    await load()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}
async function onStop(id: string) {
  try {
    const r = await stopInstance(id)
    toast(`已停止 ${r.id}`)
    await load()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}
async function onDelete() {
  if (!selected.value) return
  if (!confirm(`确认删除实例「${selected.value.name}」？此操作不可恢复。`)) return
  try {
    const r = await deleteInstance(selected.value.id)
    toast(r.message || '已删除')
    selected.value = null
    await load()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}

async function onSaveEdit(payload: UpdateInstancePayload) {
  if (!selected.value) return
  try {
    const updated = await updateInstance(selected.value.id, payload)
    toast(`已保存 ${updated.id}`)
    await load()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}

async function onClone(id: string) {
  try {
    const cloned = await cloneInstance(id, {})
    toast(`已克隆为 ${cloned.id}（端口 ${cloned.listenPort}）`)
    await load()
    selected.value = instances.value.find((i) => i.id === cloned.id) || null
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}

function toggleSelect(id: string, on: boolean) {
  const next = new Set(selectedIds.value)
  if (on) next.add(id)
  else next.delete(id)
  selectedIds.value = next
}

function toggleSelectAll(on: boolean) {
  selectedIds.value = on ? new Set(filtered.value.map((i) => i.id)) : new Set()
}

async function onBulk(action: 'start' | 'stop') {
  const ids = [...selectedIds.value]
  if (!ids.length) {
    toast('请先勾选实例')
    return
  }
  if (action === 'stop' && !confirm(`确认批量停止 ${ids.length} 个实例？`)) return
  try {
    const r = await bulkInstances(action, ids)
    toast(`批量${action === 'start' ? '启动' : '停止'}：成功 ${r.okCount}，失败 ${r.failCount}`)
    await load()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}

async function onImportFile(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const text = await file.text()
    const parsed = JSON.parse(text)
    const list = Array.isArray(parsed) ? parsed : (parsed.items || parsed.instances)
    if (!Array.isArray(list)) throw new Error('JSON 需为实例数组或含 instances 字段')
    const r = await importInstances({ instances: list, skipExisting: true })
    toast(r.message || `导入：创建 ${r.created}，跳过 ${r.skipped}，失败 ${r.failed}`)
    await load()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  } finally {
    input.value = ''
  }
}
</script>

<template>
  <div>
    <div class="toolbar">
      <p class="lead">网关实例为一等实体；可编辑 / 克隆 / 导入导出；类型仅为徽章。</p>
      <div class="actions">
        <button class="primary" @click="openCreate">新建实例</button>
        <button type="button" @click="downloadInstancesExport().catch((e) => toast(String(e)))">导出实例</button>
        <button type="button" @click="importInput?.click()">导入 JSON</button>
        <input ref="importInput" type="file" accept="application/json,.json" hidden @change="onImportFile" />
        <button type="button" @click="downloadConfigExport().catch((e) => toast(String(e)))">导出配置</button>
      </div>
    </div>

    <div class="filters">
      <input v-model="filterQ" class="search" placeholder="搜索名称 / ID / 主机…" />
      <select v-model="filterStatus">
        <option value="">全部状态</option>
        <option value="RUNNING">RUNNING</option>
        <option value="STOPPED">STOPPED</option>
        <option value="DISABLED">DISABLED</option>
        <option value="UNBOUND">UNBOUND</option>
        <option value="UNSUPPORTED">UNSUPPORTED</option>
      </select>
      <select v-model="filterDbType">
        <option value="">全部类型</option>
        <option v-for="t in dbTypeOptions" :key="t" :value="t">{{ t }}</option>
      </select>
      <select v-model="sortBy">
        <option value="name">按名称</option>
        <option value="port">按端口</option>
        <option value="status">按状态</option>
      </select>
      <label class="check inline">
        <input
          type="checkbox"
          :checked="filtered.length > 0 && selectedIds.size === filtered.length"
          @change="toggleSelectAll(($event.target as HTMLInputElement).checked)"
        />
        全选当前
      </label>
      <button type="button" :disabled="!selectedIds.size" @click="onBulk('start')">批量启动</button>
      <button type="button" :disabled="!selectedIds.size" @click="onBulk('stop')">批量停止</button>
      <span class="muted tiny">已选 {{ selectedIds.size }}</span>
    </div>

    <p v-if="error" class="err">{{ error }}</p>

    <div v-if="!filtered.length" class="empty">
      <p v-if="!instances.length">暂无网关实例。点击「新建实例」或「导入 JSON」开始。</p>
      <p v-else>无匹配结果。请调整搜索或筛选条件。</p>
    </div>

    <div v-else class="card-grid">
      <div v-for="inst in filtered" :key="inst.id" class="card-wrap">
        <label class="sel" @click.stop>
          <input
            type="checkbox"
            :checked="selectedIds.has(inst.id)"
            @change="toggleSelect(inst.id, ($event.target as HTMLInputElement).checked)"
          />
        </label>
        <InstanceCard
          :instance="inst"
          @open="selected = inst"
          @start="onStart(inst.id)"
          @stop="onStop(inst.id)"
        />
        <div class="card-extra">
          <button type="button" @click="onClone(inst.id)">克隆</button>
        </div>
      </div>
    </div>

    <InstanceDrawer
      :instance="selected"
      @close="selected = null"
      @delete="onDelete"
      @save="onSaveEdit"
      @clone="selected && onClone(selected.id)"
    />

    <div v-if="showCreate" class="overlay" @click.self="showCreate = false">
      <form class="dialog" @submit.prevent="submitCreate">
        <header>
          <h2>新建网关实例</h2>
          <button type="button" @click="showCreate = false">关闭</button>
        </header>
        <p class="muted">提交后写入控制面 H2，并绑定 ProtocolAdapter；enabled 时自动启动监听。</p>
        <p v-if="secretEnc?.requireSecretEncryption && !secretEnc?.masterKeyConfigured" class="err">
          生产加固已开启（require-secret-encryption），但控制面主密钥未配置：填写目标密码将失败（503）。
          请先配置 <code>GATEWAY_CONSOLE_SECRET_KEY_BASE64</code>，或关闭强制加密（仅实验室）。
        </p>
        <p v-else-if="secretEnc && !secretEnc.masterKeyConfigured" class="muted tiny">
          实验室模式：密码将明文写入控制面 H2（WARN）。生产请配置 secret-key-base64 并设 require-secret-encryption=true。
        </p>
        <p v-if="portConflictHint" class="err">{{ portConflictHint }}</p>

        <div class="grid2">
          <div class="field">
            <label>实例 ID（可选）</label>
            <input v-model="form.id" placeholder="自动生成 rt-…" />
          </div>
          <div class="field">
            <label>名称</label>
            <input v-model="form.name" required placeholder="展示名" />
          </div>
          <div class="field">
            <label>数据库类型</label>
            <select v-model="form.dbType" required @change="onDbTypeChange">
              <option v-for="t in creatableTypes" :key="t.id" :value="t.id">
                {{ t.displayName }} ({{ t.id }})
                {{ t.proxyModeLabel ? '· ' + t.proxyModeLabel : '' }}
              </option>
            </select>
            <p v-if="selectedCreateType?.proxyModeLabel" class="muted tiny mode-hint">
              <span
                class="badge"
                :class="proxyModeBadgeClass(selectedCreateType.proxyMode)"
              >{{ selectedCreateType.proxyModeLabel }}</span>
              {{ proxyModeHint(selectedCreateType.proxyMode) }}
            </p>
          </div>
          <div class="field">
            <label>监听 Host</label>
            <input v-model="form.listenHost" />
          </div>
          <div class="field">
            <label>监听 Port</label>
            <input v-model.number="form.listenPort" type="number" required min="1" max="65535" />
          </div>
          <div class="field">
            <label>目标 Host</label>
            <input v-model="form.targetHost" required />
          </div>
          <div class="field">
            <label>目标 Port</label>
            <input v-model.number="form.targetPort" type="number" required />
          </div>
          <div class="field">
            <label>目标库名</label>
            <input v-model="form.targetDatabase" />
          </div>
          <div class="field">
            <label>目标用户</label>
            <input v-model="form.targetUsername" />
          </div>
          <div class="field">
            <label>目标密码</label>
            <input v-model="form.targetPassword" type="password" autocomplete="new-password" />
          </div>
        </div>
        <label class="check">
          <input v-model="form.enabled" type="checkbox" />
          启用并自动启动
        </label>
        <footer>
          <button type="button" @click="showCreate = false">取消</button>
          <button class="primary" type="submit" :disabled="submitting || !!portConflictHint">
            {{ submitting ? '提交中…' : '创建并绑定' }}
          </button>
        </footer>
      </form>
    </div>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; gap: 1rem; align-items: flex-start; margin-bottom: 0.75rem; flex-wrap: wrap; }
.actions { display: flex; flex-wrap: wrap; gap: 0.5rem; }
.lead { color: var(--text-muted); margin: 0; max-width: 48rem; }
.filters {
  display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center;
  margin-bottom: 1rem; padding: 0.65rem 0.75rem;
  background: var(--bg-elevated); border: 1px solid var(--border); border-radius: var(--radius);
}
.search { min-width: 12rem; flex: 1; }
.err { color: var(--danger); }
.empty {
  padding: 2.5rem 1rem; text-align: center; color: var(--text-muted);
  border: 1px dashed var(--border); border-radius: var(--radius);
}
.card-wrap { position: relative; display: flex; flex-direction: column; gap: 0.35rem; }
.sel { position: absolute; top: 0.65rem; left: 0.65rem; z-index: 2; }
.card-extra { display: flex; gap: 0.35rem; padding: 0 0.25rem; }
.overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.5);
  display: grid; place-items: center; z-index: 45; padding: 1rem;
}
.dialog {
  width: min(640px, 100%);
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 1.25rem;
  box-shadow: var(--shadow);
}
.dialog header { display: flex; justify-content: space-between; align-items: center; }
.dialog h2 { margin: 0; }
.muted { color: var(--text-muted); font-size: 0.85rem; }
.tiny { font-size: 0.75rem; }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0.5rem 1rem; margin-top: 0.75rem; }
.check { display: flex; align-items: center; gap: 0.5rem; margin: 0.75rem 0 1rem; color: var(--text-muted); }
.check.inline { margin: 0; }
.check input { width: auto; }
footer { display: flex; justify-content: flex-end; gap: 0.5rem; }
.mode-hint { margin: 0.35rem 0 0; display: flex; align-items: center; gap: 0.4rem; flex-wrap: wrap; }
@media (max-width: 640px) { .grid2 { grid-template-columns: 1fr; } }
</style>
