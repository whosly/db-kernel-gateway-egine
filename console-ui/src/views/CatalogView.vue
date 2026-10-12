<script setup lang="ts">
import { ref } from 'vue'
import CatalogTable from '../components/CatalogTable.vue'
import { listSupportedDatabases } from '../api/consoleApi'
import type { CatalogEntry } from '../api/types'
import { usePolling } from '../composables/usePolling'

const rows = ref<CatalogEntry[]>([])
const error = ref<string | null>(null)

async function load() {
  try {
    const body = await listSupportedDatabases()
    rows.value = body.items || []
    error.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}
usePolling(load, 6000)
</script>

<template>
  <div>
    <p class="lead">类型目录（可插拔适配器注册表）。与实例注册表分离；dbType 仅为实例属性。MySQL/PG 标<strong>网关代理</strong>，SQL Server 标<strong>透明代理</strong>。</p>
    <p v-if="error" class="err">{{ error }}</p>
    <CatalogTable :rows="rows" />
  </div>
</template>

<style scoped>
.lead { color: var(--text-muted); margin-top: 0; line-height: 1.55; max-width: 52rem; }
.err { color: var(--danger); }
</style>
