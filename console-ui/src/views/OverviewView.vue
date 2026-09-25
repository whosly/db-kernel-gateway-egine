<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import KpiGrid from '../components/KpiGrid.vue'
import StatusDonut from '../components/StatusDonut.vue'
import InstanceCard from '../components/InstanceCard.vue'
import Sparkline from '../components/Sparkline.vue'
import { getMetricsHistory, getOverview, listActiveAlerts, startInstance, stopInstance } from '../api/consoleApi'
import type { ActiveAlert, MetricsHistoryPoint, OverviewResponse } from '../api/types'
import { usePolling } from '../composables/usePolling'
import { useRouter } from 'vue-router'

const toast = inject<(m: string) => void>('toast', () => {})
const router = useRouter()
const data = ref<OverviewResponse | null>(null)
const history = ref<MetricsHistoryPoint[]>([])
const historyNote = ref<string | null>(null)
const activeAlerts = ref<ActiveAlert[]>([])
const error = ref<string | null>(null)

async function load() {
  try {
    data.value = await getOverview()
    const hist = await getMetricsHistory(undefined, 60)
    history.value = hist.points || []
    historyNote.value = hist.note || null
    try {
      const alerts = await listActiveAlerts(true)
      activeAlerts.value = alerts.items || []
    } catch {
      activeAlerts.value = []
    }
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
  const sessionSum = (d.instances || [])
    .filter((i) => i.status === 'RUNNING')
    .reduce((n, i) => n + (Number(i.activeSessions) || 0), 0)
  return [
    { label: '实例总数', value: d.instances?.length ?? 0 },
    { label: '运行中', value: Number(health.runningCount ?? 0) },
    { label: '活跃会话', value: sessionSum, hint: 'RUNNING 实例合计' },
    { label: '已绑定', value: Number(health.boundCount ?? 0) },
    { label: '接受连接', value: d.metrics?.connectionsAccepted ?? 0, hint: '全实例求和' },
    { label: '策略拒绝', value: d.metrics?.policyDenials ?? 0 },
  ]
})

function series(key: string) {
  return history.value.map((p) => Number(p[key] ?? 0))
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
</script>

<template>
  <div>
    <p v-if="error" class="err">加载失败：{{ error }}</p>
    <div
      v-if="activeAlerts.length"
      class="alert-banner"
      role="status"
      @click="router.push('/alerts')"
    >
      <strong>告警触发 {{ activeAlerts.length }}</strong>
      <span class="muted tiny">
        {{ activeAlerts.slice(0, 3).map((a) => a.name || a.metricKey).join(' · ') }}
        <template v-if="activeAlerts.length > 3"> …</template>
        — 点击查看
      </span>
    </div>
    <KpiGrid :items="kpis" />
    <div class="row">
      <div class="panel">
        <h3>状态分布</h3>
        <StatusDonut :by-status="data?.byStatus || {}" />
      </div>
      <div class="panel grow">
        <h3>指标趋势（进程内）</h3>
        <p class="muted tiny">
          内存环采样 · {{ historyNote || '重启丢失；不替代 Prometheus' }}
        </p>
        <div class="sparks">
          <div>
            <div class="spark-label">接受连接</div>
            <Sparkline :values="series('connectionsAccepted')" color="#22c55e" />
          </div>
          <div>
            <div class="spark-label">策略拒绝</div>
            <Sparkline :values="series('policyDenials')" color="#ef4444" />
          </div>
          <div>
            <div class="spark-label">活跃连接</div>
            <Sparkline :values="series('activeConnections')" color="#38bdf8" />
          </div>
        </div>
        <p class="muted" style="margin-top: 0.75rem">
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
.tiny { font-size: 0.75rem; }
.code {
  background: var(--bg);
  border-radius: 8px;
  padding: 0.75rem;
  font-size: 0.8rem;
  overflow: auto;
}
.err { color: var(--danger); }
.sparks { display: grid; gap: 0.65rem; }
.spark-label { font-size: 0.8rem; color: var(--text-muted); margin-bottom: 0.2rem; }
.alert-banner {
  display: flex; gap: 0.75rem; align-items: baseline; flex-wrap: wrap;
  background: rgba(239, 68, 68, 0.12);
  border: 1px solid #ef4444;
  border-radius: 10px;
  padding: 0.65rem 0.9rem;
  margin-bottom: 0.85rem;
  cursor: pointer;
}
.alert-banner:hover { background: rgba(239, 68, 68, 0.18); }
</style>
