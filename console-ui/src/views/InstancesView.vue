<script setup lang="ts">
import { computed, inject, reactive, ref } from 'vue'
import InstanceCard from '../components/InstanceCard.vue'
import InstanceDrawer from '../components/InstanceDrawer.vue'
import {
  createInstance,
  deleteInstance,
  listInstances,
  listSupportedDatabases,
  startInstance,
  stopInstance,
} from '../api/consoleApi'
import type { CatalogEntry, CreateInstancePayload, GatewayInstance } from '../api/types'
import { usePolling } from '../composables/usePolling'

const toast = inject<(m: string) => void>('toast', () => {})
const instances = ref<GatewayInstance[]>([])
const catalog = ref<CatalogEntry[]>([])
const error = ref<string | null>(null)
const selected = ref<GatewayInstance | null>(null)
const showCreate = ref(false)
const submitting = ref(false)

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

async function load() {
  try {
    const [inst, cat] = await Promise.all([listInstances(), listSupportedDatabases()])
    instances.value = inst.instances || []
    catalog.value = cat.databases || []
    if (selected.value) {
      selected.value = instances.value.find((i) => i.id === selected.value!.id) || null
    }
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
    toast(r.message || '已启动')
    await load()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}
async function onStop(id: string) {
  try {
    const r = await stopInstance(id)
    toast(r.message || '已停止')
    await load()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}
async function onDelete() {
  if (!selected.value) return
  try {
    const r = await deleteInstance(selected.value.id)
    toast(r.message || '已删除')
    selected.value = null
    await load()
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e))
  }
}
</script>

<template>
  <div>
    <div class="toolbar">
      <p class="lead">网关实例为一等实体；类型仅为徽章。可新建管控台实例（H2 持久化 + 运行时绑定）。</p>
      <button class="primary" @click="openCreate">新建实例</button>
    </div>
    <p v-if="error" class="err">{{ error }}</p>

    <div class="card-grid">
      <InstanceCard
        v-for="inst in instances"
        :key="inst.id"
        :instance="inst"
        @open="selected = inst"
        @start="onStart(inst.id)"
        @stop="onStop(inst.id)"
      />
    </div>

    <InstanceDrawer
      :instance="selected"
      @close="selected = null"
      @delete="onDelete"
    />

    <div v-if="showCreate" class="overlay" @click.self="showCreate = false">
      <form class="dialog" @submit.prevent="submitCreate">
        <header>
          <h2>新建网关实例</h2>
          <button type="button" @click="showCreate = false">关闭</button>
        </header>
        <p class="muted">提交后写入控制面 H2，并绑定 ProtocolAdapter；enabled 时自动启动监听。</p>

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
              </option>
            </select>
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
          <button class="primary" type="submit" :disabled="submitting">
            {{ submitting ? '提交中…' : '创建并绑定' }}
          </button>
        </footer>
      </form>
    </div>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; gap: 1rem; align-items: flex-start; margin-bottom: 1rem; }
.lead { color: var(--text-muted); margin: 0; max-width: 48rem; }
.err { color: var(--danger); }
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
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0.5rem 1rem; margin-top: 0.75rem; }
.check { display: flex; align-items: center; gap: 0.5rem; margin: 0.75rem 0 1rem; color: var(--text-muted); }
.check input { width: auto; }
footer { display: flex; justify-content: flex-end; gap: 0.5rem; }
@media (max-width: 640px) { .grid2 { grid-template-columns: 1fr; } }
</style>
