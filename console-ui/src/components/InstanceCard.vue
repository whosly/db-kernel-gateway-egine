<script setup lang="ts">
import { ref } from 'vue'
import type { GatewayInstance, HealthCheckResult } from '../api/types'
import { healthCheck } from '../api/consoleApi'

const props = defineProps<{ instance: GatewayInstance }>()
defineEmits<{
  open: []
  start: []
  stop: []
}>()

const health = ref<HealthCheckResult | null>(null)
const busy = ref(false)

async function probe() {
  busy.value = true
  try {
    health.value = await healthCheck(props.instance.id)
  } catch (e) {
    health.value = {
      ok: false,
      latencyMs: 0,
      targetHost: props.instance.targetHost,
      targetPort: props.instance.targetPort,
      message: e instanceof Error ? e.message : String(e),
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <article class="card" @click="$emit('open')">
    <header>
      <div>
        <h3>{{ instance.name }}</h3>
        <div class="id">{{ instance.id }}</div>
      </div>
      <span class="badge" :class="'status-' + instance.status">{{ instance.status }}</span>
    </header>
    <div class="meta">
      <span class="badge">{{ instance.dbType }}</span>
      <span class="badge">{{ instance.source === 'console' ? '管控台' : 'YAML' }}</span>
      <span class="port">{{ instance.listenHost }}:{{ instance.listenPort }}</span>
      <span
        v-if="instance.status === 'RUNNING'"
        class="badge"
      >会话 {{ instance.activeSessions ?? 0 }}</span>
      <span
        v-if="health"
        class="badge"
        :class="health.ok ? 'ok' : 'bad'"
      >{{ health.ok ? '后端可达' : '后端不可达' }}</span>
    </div>
    <p class="msg">{{ instance.message }}</p>
    <footer @click.stop>
      <button
        class="primary"
        :disabled="!instance.startable || instance.status === 'RUNNING'"
        @click="$emit('start')"
      >启动</button>
      <button
        :disabled="!instance.bound || instance.status !== 'RUNNING'"
        @click="$emit('stop')"
      >停止</button>
      <button :disabled="busy" @click="probe">{{ busy ? '探测…' : '探测后端' }}</button>
    </footer>
  </article>
</template>

<style scoped>
.card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 1rem;
  cursor: pointer;
  transition: border-color 0.15s;
}
.card:hover { border-color: var(--accent); }
header { display: flex; justify-content: space-between; gap: 0.5rem; }
h3 { margin: 0; font-size: 1.05rem; }
.id { color: var(--text-muted); font-size: 0.75rem; margin-top: 0.15rem; }
.meta { display: flex; flex-wrap: wrap; gap: 0.4rem; margin: 0.75rem 0; align-items: center; }
.port { color: var(--text-muted); font-size: 0.85rem; }
.msg { color: var(--text-muted); font-size: 0.85rem; min-height: 2.4em; }
footer { display: flex; gap: 0.5rem; flex-wrap: wrap; }
.badge.ok { background: #1b5e20; color: #c8e6c9; }
.badge.bad { background: #b71c1c; color: #ffcdd2; }
</style>
