<script setup lang="ts">
import { ref } from 'vue'
import {
  deleteMaskingKey,
  downloadConfigExport,
  downloadInstancesExport,
  getConfigSummary,
  getHealth,
  getMaskingKeyStatus,
  listAudit,
  putMaskingKey,
} from '../api/consoleApi'
import type { AuditEntry, MaskingKeyStatus } from '../api/types'
import { usePolling } from '../composables/usePolling'

const summary = ref<Record<string, unknown> | null>(null)
const health = ref<Record<string, unknown> | null>(null)
const error = ref<string | null>(null)
const keyStatus = ref<MaskingKeyStatus | null>(null)
const keyId = ref('default')
const keyBase64 = ref('')
const keyMsg = ref<string | null>(null)
const keyBusy = ref(false)
const audit = ref<AuditEntry[]>([])

async function load() {
  try {
    summary.value = await getConfigSummary()
    health.value = await getHealth()
    keyStatus.value = await getMaskingKeyStatus()
    const auditBody = await listAudit(30)
    audit.value = auditBody.entries || []
    error.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}
usePolling(load, 6000)

async function saveKey() {
  keyBusy.value = true
  keyMsg.value = null
  try {
    keyStatus.value = await putMaskingKey({
      keyId: keyId.value.trim() || 'default',
      keyBase64: keyBase64.value.trim(),
    })
    keyBase64.value = ''
    keyMsg.value = keyStatus.value.message || '已保存'
  } catch (e) {
    keyMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    keyBusy.value = false
  }
}

async function clearKey() {
  if (!confirm('清除管控台脱敏密钥并回退到 yaml 配置？')) return
  keyBusy.value = true
  keyMsg.value = null
  try {
    keyStatus.value = await deleteMaskingKey()
    keyMsg.value = keyStatus.value.message || '已清除'
  } catch (e) {
    keyMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    keyBusy.value = false
  }
}

function sourceLabel(s?: string) {
  if (s === 'console') return '管控台'
  if (s === 'config') return '配置文件'
  return '未配置'
}
</script>

<template>
  <div>
    <div class="panel">
      <h3>配置导出</h3>
      <p class="muted">导出 JSON（无密码 / 无脱敏密钥材料；仅 passwordConfigured 等标志）。</p>
      <div class="row">
        <button type="button" @click="downloadInstancesExport().catch((e) => (error = String(e)))">导出实例</button>
        <button type="button" class="primary" @click="downloadConfigExport().catch((e) => (error = String(e)))">导出配置</button>
      </div>
    </div>

    <div class="panel">
      <h3>运维说明</h3>
      <ul>
        <li>管控台 API：<code>/console/api/*</code>（实例中心，协议无关）</li>
        <li>遗留管理面：<code>/gateway/*</code> · Actuator · CLI（legacy adapter）</li>
        <li>管控台创建的实例持久化在嵌入式 H2（控制面库，非业务库）</li>
        <li>
          列脱敏 / 列加密：实例抽屉「脱敏规则」；encrypt 可用 yaml
          <code>gateway.masking.key-base64</code> 或下方「安全」配置
        </li>
        <li>
          控制面密码加密：配置 <code>gateway.console.secret-key-base64</code>（32 字节 AES Base64）；缺省为实验室明文 + WARN
        </li>
        <li>
          可选 API Token：<code>gateway.console.api-token</code>；本地可
          <code>localStorage.setItem('consoleApiToken','…')</code> 或
          <code>VITE_CONSOLE_API_TOKEN</code>
        </li>
      </ul>
    </div>

    <div class="panel">
      <h3>安全 · 脱敏密钥</h3>
      <p class="muted">
        状态：
        <strong>{{ keyStatus?.configured ? '已配置' : '未配置' }}</strong>
        · 来源 {{ sourceLabel(keyStatus?.source) }}
        <template v-if="keyStatus?.keyId"> · keyId={{ keyStatus.keyId }}</template>
        · 控制面主密钥
        {{ keyStatus?.consoleMasterKeyConfigured ? '已配置' : '未配置（实验室）' }}
      </p>
      <p class="muted tiny">
        密钥永不回显。保存需已配置 <code>gateway.console.secret-key-base64</code>。清除后回退 yaml
        <code>gateway.masking.key-base64</code>。
      </p>
      <form class="key-form" @submit.prevent="saveKey">
        <div class="field">
          <label>keyId</label>
          <input v-model="keyId" placeholder="default" />
        </div>
        <div class="field">
          <label>密钥 Base64（AES 128/192/256）</label>
          <input v-model="keyBase64" type="password" autocomplete="new-password" required placeholder="Base64…" />
        </div>
        <div class="actions">
          <button type="submit" class="primary" :disabled="keyBusy">保存密钥</button>
          <button type="button" :disabled="keyBusy" @click="clearKey">清除管控台密钥</button>
        </div>
      </form>
      <p v-if="keyMsg" class="msg">{{ keyMsg }}</p>
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

    <div class="panel">
      <h3>操作审计（最近）</h3>
      <p class="muted tiny">不含密码 / 脱敏密钥。完整鉴权与 SSO 仍为规划项。</p>
      <table v-if="audit.length" class="audit">
        <thead>
          <tr><th>时间</th><th>动作</th><th>实例</th><th>详情</th></tr>
        </thead>
        <tbody>
          <tr v-for="e in audit" :key="e.id">
            <td>{{ e.at }}</td>
            <td>{{ e.action }}</td>
            <td>{{ e.instanceId || '—' }}</td>
            <td class="tiny">{{ e.detailJson }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">暂无审计记录</p>
    </div>
  </div>
</template>

<style scoped>
.panel {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 1rem 1.1rem;
  margin-bottom: 1rem;
}
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
@media (max-width: 900px) { .grid { grid-template-columns: 1fr; } }
pre { font-size: 0.75rem; overflow: auto; max-height: 280px; }
.err { color: var(--danger); }
.muted { color: var(--text-muted); }
.tiny { font-size: 0.75rem; }
.key-form { display: grid; gap: 0.65rem; max-width: 480px; }
.field label { display: block; font-size: 0.8rem; color: var(--text-muted); margin-bottom: 0.25rem; }
.actions { display: flex; gap: 0.5rem; }
.msg { margin-top: 0.5rem; }
.audit { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
.audit th, .audit td { border-bottom: 1px solid var(--border); padding: 0.4rem 0.35rem; text-align: left; vertical-align: top; }
code { font-size: 0.85em; }
.row { display: flex; gap: 0.5rem; flex-wrap: wrap; }
.muted { color: var(--text-muted); font-size: 0.9rem; }
</style>
