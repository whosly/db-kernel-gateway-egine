<script setup lang="ts">
import { ref } from 'vue'
import { usePermissions, Perm } from '../composables/usePermissions'
import type { GatewayInstance, HealthCheckResult } from '../api/types'
import { proxyModeBadgeClass } from '../api/proxyMode'
import { healthCheck } from '../api/consoleApi'

const props = defineProps<{ instance: GatewayInstance }>()
defineEmits<{
  open: []
  start: []
  stop: []
}>()

const health = ref<HealthCheckResult | null>(null)
const busy = ref(false)
const { has } = usePermissions()

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
  <article
    class="card interactive-card"
    role="button"
    tabindex="0"
    @click="$emit('open')"
    @keydown.enter="$emit('open')"
  >
    <header>
      <div class="titles">
        <h3>{{ instance.name }}</h3>
        <div class="id mono">{{ instance.id }}</div>
      </div>
      <span class="badge" :class="'status-' + instance.status">
        <span class="status-dot" />
        {{ instance.status }}
      </span>
    </header>
    <div class="meta">
      <span class="badge">{{ instance.dbType }}</span>
      <span
        v-if="instance.proxyModeLabel"
        class="badge"
        :class="proxyModeBadgeClass(instance.proxyMode)"
        :title="instance.proxyMode || ''"
      >{{ instance.proxyModeLabel }}</span>
      <span class="badge">{{ instance.source === 'console' ? '管控台' : 'YAML' }}</span>
      <span class="port mono">{{ instance.listenHost }}:{{ instance.listenPort }}</span>
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
    <p class="msg">{{ instance.message || '—' }}</p>
    <footer @click.stop>
      <button
        class="primary"
        :disabled="!has(Perm.INSTANCES_START_STOP) || !instance.startable || instance.status === 'RUNNING'"
        :title="!has(Perm.INSTANCES_START_STOP) ? '需要 instances:start_stop' : ''"
        @click="$emit('start')"
      >启动</button>
      <button
        :disabled="!has(Perm.INSTANCES_START_STOP) || !instance.bound || instance.status !== 'RUNNING'"
        :title="!has(Perm.INSTANCES_START_STOP) ? '需要 instances:start_stop' : ''"
        @click="$emit('stop')"
      >停止</button>
      <button
        :disabled="busy || !has(Perm.INSTANCES_START_STOP)"
        :title="!has(Perm.INSTANCES_START_STOP) ? '需要 instances:start_stop' : ''"
        @click="probe"
      >{{ busy ? '探测…' : '探测后端' }}</button>
    </footer>
  </article>
</template>

<style scoped>
.card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 1rem 1.05rem 0.95rem;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 0.55rem;
  box-shadow: var(--shadow-sm);
}
header {
  display: flex;
  justify-content: space-between;
  gap: 0.65rem;
  align-items: flex-start;
}
h3 {
  margin: 0;
  font-size: 1rem;
  font-weight: 650;
  letter-spacing: -0.01em;
}
.id {
  color: var(--text-faint);
  font-size: 0.72rem;
  margin-top: 0.2rem;
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
  align-items: center;
}
.port {
  color: var(--text-muted);
  font-size: 0.78rem;
}
.msg {
  color: var(--text-muted);
  font-size: 0.8rem;
  min-height: 2.2em;
  margin: 0;
  line-height: 1.4;
}
footer {
  display: flex;
  gap: 0.4rem;
  flex-wrap: wrap;
  margin-top: auto;
  padding-top: 0.25rem;
}
.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  display: inline-block;
}
</style>
