<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import KpiGrid from '../components/KpiGrid.vue'
import StatusDonut from '../components/StatusDonut.vue'
import InstanceCard from '../components/InstanceCard.vue'
import { getOverview, startInstance, stopInstance } from '../api/consoleApi'
import type { OverviewResponse } from '../api/types'
import { usePolling } from '../composables/usePolling'
import { useRouter } from 'vue-router'

const toast = inject<(m: string) => void>('toast', () => {})
const router = useRouter()
const data = ref<OverviewResponse | null>(null)
const error = ref<string | null>(null)

async function load() {
  try {
    data.value = await getOverview()
    error.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

usePolling(load, 6000)

const kpis = computed(() => {
  const d = data.value
  if (!d) return []
  const health = d.health || {}
  return [
    { label: '实例总数', value: d.instances?.length ?? 0 },
    { label: '运行中', value: Number(health.runningCount ?? 0) },
    { label: '已绑定', value: Number(health.boundCount ?? 0) },
    { label: '接受连接', value: d.metrics?.connectionsAccepted ?? 0, hint: '全实例求和' },
    { label: '策略拒绝', value: d.metrics?.policyDenials ?? 0 },
  ]
})

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
</script>

<template>
  <div>
    <p v-if="error" class="err">加载失败：{{ error }}</p>
    <KpiGrid :items="kpis" />
    <div class="row">
      <div class="panel">
        <h3>状态分布</h3>
        <StatusDonut :by-status="data?.byStatus || {}" />
      </div>
      <div class="panel grow">
        <h3>指标说明</h3>
        <p class="muted">
          总览 <code>metrics</code> = 全部绑定实例计数求和（metricsScope={{ data?.metricsScope || '—' }}）。
          <code>legacyMetrics</code> 为遗留 /gateway adapter，仅对照用。
        </p>
        <pre class="code">{{ JSON.stringify(data?.legacyMetrics || {}, null, 2) }}</pre>
      </div>
    </div>
    <h3>实例</h3>
    <div class="card-grid">
      <InstanceCard
        v-for="inst in data?.instances || []"
        :key="inst.id"
        :instance="inst"
        @open="router.push('/instances')"
        @start="onStart(inst.id)"
        @stop="onStop(inst.id)"
      />
    </div>
  </div>
</template>

<style scoped>
.row { display: flex; gap: 1rem; flex-wrap: wrap; margin-bottom: 1.25rem; }
.panel {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 1rem;
  min-width: 240px;
}
.grow { flex: 1; }
h3 { margin: 0 0 0.75rem; font-size: 1rem; }
.muted { color: var(--text-muted); font-size: 0.9rem; }
.code {
  background: var(--bg);
  border-radius: 8px;
  padding: 0.75rem;
  font-size: 0.8rem;
  overflow: auto;
}
.err { color: var(--danger); }
</style>
