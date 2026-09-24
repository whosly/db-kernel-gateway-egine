<script setup lang="ts">
import type { CatalogEntry } from '../api/types'
defineProps<{ rows: CatalogEntry[] }>()
</script>

<template>
  <div class="table-wrap">
    <table>
      <thead>
        <tr>
          <th>类型 ID</th>
          <th>名称</th>
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
th, td { text-align: left; padding: 0.7rem 0.85rem; border-bottom: 1px solid var(--border); }
th { color: var(--text-muted); font-weight: 600; background: var(--bg-elevated); }
.notes { color: var(--text-muted); max-width: 280px; }
</style>
