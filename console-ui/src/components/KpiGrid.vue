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
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 0.75rem;
  margin-bottom: 1.15rem;
}
.kpi {
  position: relative;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 0.85rem 0.95rem 0.9rem;
  overflow: hidden;
  box-shadow: var(--shadow-sm);
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}
.kpi::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: linear-gradient(180deg, var(--accent), transparent);
  opacity: 0.85;
}
.kpi:hover {
  border-color: color-mix(in srgb, var(--accent) 35%, var(--border));
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--accent) 18%, transparent);
}
.label {
  color: var(--text-muted);
  font-size: var(--fs-xs);
  font-weight: 600;
  letter-spacing: 0.02em;
}
.value {
  font-size: 1.55rem;
  font-weight: 700;
  margin-top: 0.3rem;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
}
.hint {
  color: var(--text-faint);
  font-size: 0.7rem;
  margin-top: 0.25rem;
}
</style>
