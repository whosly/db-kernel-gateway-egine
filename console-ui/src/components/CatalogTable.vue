<script setup lang="ts">
import type { CatalogEntry } from '../api/types'
import { proxyModeBadgeClass, proxyModeHint } from '../api/proxyMode'
defineProps<{ rows: CatalogEntry[] }>()
</script>

<template>
  <div class="wrap">
    <table v-if="rows.length" class="data-table">
      <thead>
        <tr>
          <th>类型 ID</th>
          <th>名称</th>
          <th>代理模式</th>
          <th>成熟度</th>
          <th>可创建</th>
          <th>默认端口</th>
          <th>备注</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.id">
          <td><span class="badge">{{ row.id }}</span></td>
          <td>{{ row.displayName }}</td>
          <td>
            <span
              v-if="row.proxyModeLabel"
              class="badge"
              :class="proxyModeBadgeClass(row.proxyMode)"
              :title="proxyModeHint(row.proxyMode)"
            >{{ row.proxyModeLabel }}</span>
            <span v-else class="muted">—</span>
            <div v-if="row.proxyMode" class="hint">{{ proxyModeHint(row.proxyMode) }}</div>
          </td>
          <td>{{ row.maturity }}</td>
          <td>{{ row.creatable ? '是' : '否' }}</td>
          <td class="mono">{{ row.defaultProxyPort }} → {{ row.defaultTargetPort }}</td>
          <td class="notes">{{ row.notes || '—' }}</td>
        </tr>
      </tbody>
    </table>
    <div v-else class="empty-state">
      <strong>目录为空</strong>
      <p>尚未注册可管理的数据库类型适配器。</p>
    </div>
  </div>
</template>

<style scoped>
.wrap {
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--bg-card);
  box-shadow: var(--shadow-sm);
}
.data-table { margin: 0; width: 100%; }
.data-table th { position: sticky; top: 0; z-index: 1; }
.data-table tbody tr:hover { background: var(--bg-hover); }
.notes { color: var(--text-muted); max-width: 280px; font-size: 0.8rem; }
.hint { color: var(--text-muted); font-size: 0.72rem; margin-top: 0.25rem; }
.muted { color: var(--text-muted); }
.mono { font-family: var(--font-mono); font-size: 0.8rem; }
</style>
