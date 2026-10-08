<script setup lang="ts">
defineProps<{
  items: { label: string; value: string | number; hint?: string }[]
}>()
</script>

<template>
  <div class="kpi-grid">
    <div v-for="item in items" :key="item.label" class="kpi">
      <div class="label">{{ item.label }}</div>
      <div class="value">{{ item.value }}</div>
      <div v-if="item.hint" class="hint">{{ item.hint }}</div>
    </div>
  </div>
</template>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 0.85rem;
  margin-bottom: 1.25rem;
}
.kpi {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 0.9rem 1rem;
}
.label { color: var(--text-muted); font-size: 0.8rem; }
.value { font-size: 1.6rem; font-weight: 650; margin-top: 0.25rem; }
.hint { color: var(--text-muted); font-size: 0.75rem; margin-top: 0.2rem; }
</style>
