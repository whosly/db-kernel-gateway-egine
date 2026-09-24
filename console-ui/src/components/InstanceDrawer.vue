<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import type { GatewayInstance } from '../api/types'
import { getInstanceMetrics } from '../api/consoleApi'

const props = defineProps<{ instance: GatewayInstance | null }>()
defineEmits<{ close: []; delete: [] }>()

const metrics = ref<Record<string, number>>({})

async function loadMetrics() {
  if (!props.instance) return
  try {
    const body = await getInstanceMetrics(props.instance.id)
    metrics.value = body.metrics || {}
  } catch {
    metrics.value = props.instance.metrics || {}
  }
}

onMounted(loadMetrics)
watch(() => props.instance?.id, loadMetrics)
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
        <p class="muted tiny">仅管控台创建的实例可删；将停止 listener 并移除 H2 行。</p>
      </footer>
    </aside>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.45);
  display: flex; justify-content: flex-end; z-index: 40;
}
.drawer {
  width: min(420px, 100%);
  background: var(--bg-elevated);
  border-left: 1px solid var(--border);
  padding: 1.25rem;
  overflow: auto;
  box-shadow: var(--shadow);
}
header { display: flex; justify-content: space-between; align-items: flex-start; }
h2 { margin: 0; }
.sub, .muted { color: var(--text-muted); font-size: 0.85rem; }
section { margin-top: 1.25rem; }
h4 { margin: 0 0 0.5rem; font-size: 0.9rem; color: var(--text-muted); }
dl { margin: 0; }
dl > div { display: flex; justify-content: space-between; gap: 1rem; padding: 0.35rem 0; border-bottom: 1px solid var(--border); }
dt { color: var(--text-muted); }
dd { margin: 0; }
footer { margin-top: 1.5rem; }
.tiny { font-size: 0.75rem; }
</style>
