<script setup lang="ts">
import type { CatalogEntry } from '../api/types'
import { proxyModeBadgeClass, proxyModeHint } from '../api/proxyMode'
defineProps<{ rows: CatalogEntry[] }>()
</script>

<template>
  <div class="table-wrap">
    <table>
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
          <td>{{ row.defaultProxyPort }} → {{ row.defaultTargetPort }}</td>
          <td class="notes">{{ row.notes || '—' }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.table-wrap {
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-card);
}
table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
th, td { text-align: left; padding: 0.7rem 0.85rem; border-bottom: 1px solid var(--border); vertical-align: top; }
th { color: var(--text-muted); font-weight: 600; background: var(--bg-elevated); }
.notes { color: var(--text-muted); max-width: 280px; }
.hint { color: var(--text-muted); font-size: 0.75rem; margin-top: 0.25rem; }
.muted { color: var(--text-muted); }
</style>
