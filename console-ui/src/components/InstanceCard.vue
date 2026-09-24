<script setup lang="ts">
import type { GatewayInstance } from '../api/types'

defineProps<{ instance: GatewayInstance }>()
defineEmits<{
  open: []
  start: []
  stop: []
}>()
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
footer { display: flex; gap: 0.5rem; }
</style>
