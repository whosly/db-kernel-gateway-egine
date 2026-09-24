<script setup lang="ts">
import { ref } from 'vue'
import { getConfigSummary, getHealth } from '../api/consoleApi'
import { usePolling } from '../composables/usePolling'

const summary = ref<Record<string, unknown> | null>(null)
const health = ref<Record<string, unknown> | null>(null)
const error = ref<string | null>(null)

async function load() {
  try {
    summary.value = await getConfigSummary()
    health.value = await getHealth()
    error.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}
usePolling(load, 6000)
</script>

<template>
  <div>
    <div class="panel">
      <h3>运维说明</h3>
      <ul>
        <li>管控台 API：<code>/console/api/*</code>（实例中心，协议无关）</li>
        <li>遗留管理面：<code>/gateway/*</code> · Actuator · CLI（legacy adapter）</li>
        <li>管控台创建的实例持久化在嵌入式 H2（控制面库，非业务库）</li>
        <li>列脱敏 / 列加密：实例抽屉「脱敏规则」Tab；H2 持久化 + 热挂 MaskingEngine；encrypt 需 <code>gateway.masking.key-base64</code></li>
        <li>密钥永不回传；H2 中密码为实验室明文存储，生产需加密/密钥托管</li>
      </ul>
    </div>
    <p v-if="error" class="err">{{ error }}</p>
    <div class="grid">
      <div class="panel">
        <h3>健康</h3>
        <pre>{{ JSON.stringify(health, null, 2) }}</pre>
      </div>
      <div class="panel">
        <h3>配置摘要（无密钥）</h3>
        <pre>{{ JSON.stringify(summary, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.panel {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 1rem;
  margin-bottom: 1rem;
}
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 1rem; }
pre { overflow: auto; font-size: 0.8rem; background: var(--bg); padding: 0.75rem; border-radius: 8px; }
ul { color: var(--text-muted); line-height: 1.6; }
.err { color: var(--danger); }
h3 { margin-top: 0; }
</style>
