<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ byStatus: Record<string, number> }>()

const colors: Record<string, string> = {
  RUNNING: '#22c55e',
  STOPPED: '#eab308',
  DISABLED: '#94a3b8',
  UNSUPPORTED: '#ef4444',
  UNBOUND: '#38bdf8',
}

const slices = computed(() => {
  const entries = Object.entries(props.byStatus || {}).filter(([, n]) => n > 0)
  const total = entries.reduce((s, [, n]) => s + n, 0) || 1
  let angle = 0
  return entries.map(([status, count]) => {
    const sweep = (count / total) * 360
    const start = angle
    angle += sweep
    return { status, count, start, sweep, color: colors[status] || '#64748b' }
  })
})

function arc(start: number, sweep: number) {
  if (sweep >= 359.9) {
    return 'M 50 50 m 0 -40 a 40 40 0 1 1 0 80 a 40 40 0 1 1 0 -80'
  }
  const r = 40
  const toRad = (d: number) => ((d - 90) * Math.PI) / 180
  const x1 = 50 + r * Math.cos(toRad(start))
  const y1 = 50 + r * Math.sin(toRad(start))
  const x2 = 50 + r * Math.cos(toRad(start + sweep))
  const y2 = 50 + r * Math.sin(toRad(start + sweep))
  const large = sweep > 180 ? 1 : 0
  return `M 50 50 L ${x1} ${y1} A ${r} ${r} 0 ${large} 1 ${x2} ${y2} Z`
}
</script>

<template>
  <div class="donut-wrap">
    <svg viewBox="0 0 100 100" class="donut">
      <circle cx="50" cy="50" r="40" fill="transparent" stroke="#2d3a4d" stroke-width="12" />
      <path
        v-for="s in slices"
        :key="s.status"
        :d="arc(s.start, s.sweep)"
        :fill="s.color"
        opacity="0.9"
      />
      <circle cx="50" cy="50" r="24" fill="var(--bg-card)" />
    </svg>
    <ul class="legend">
      <li v-for="s in slices" :key="s.status">
        <span class="dot" :style="{ background: s.color }" />
        {{ s.status }} · {{ s.count }}
      </li>
    </ul>
  </div>
</template>

<style scoped>
.donut-wrap { display: flex; gap: 1rem; align-items: center; }
.donut { width: 140px; height: 140px; }
.legend { list-style: none; margin: 0; padding: 0; color: var(--text-muted); font-size: 0.85rem; }
.legend li { display: flex; align-items: center; gap: 0.4rem; margin: 0.25rem 0; }
.dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
</style>
