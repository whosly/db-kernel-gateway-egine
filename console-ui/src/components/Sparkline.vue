<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  values: number[]
  color?: string
  width?: number
  height?: number
}>()

const w = computed(() => props.width ?? 160)
const h = computed(() => props.height ?? 40)
const stroke = computed(() => props.color ?? '#38bdf8')

const path = computed(() => {
  const vals = props.values || []
  if (vals.length < 2) return ''
  const min = Math.min(...vals)
  const max = Math.max(...vals)
  const span = max - min || 1
  const step = w.value / (vals.length - 1)
  return vals
    .map((v, i) => {
      const x = i * step
      const y = h.value - ((v - min) / span) * (h.value - 4) - 2
      return `${i === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`
    })
    .join(' ')
})

const latest = computed(() => {
  const vals = props.values || []
  return vals.length ? vals[vals.length - 1] : 0
})
</script>

<template>
  <div class="spark">
    <svg :viewBox="`0 0 ${w} ${h}`" :width="w" :height="h" class="chart">
      <path v-if="path" :d="path" fill="none" :stroke="stroke" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
      <text v-else x="4" y="24" class="empty" fill="currentColor">暂无数据</text>
    </svg>
    <span class="val">{{ latest }}</span>
  </div>
</template>

<style scoped>
.spark { display: flex; align-items: center; gap: 0.5rem; }
.chart { display: block; background: var(--bg); border-radius: 6px; }
.val { font-variant-numeric: tabular-nums; font-size: 0.85rem; color: var(--text-muted); min-width: 2.5rem; }
.empty { font-size: 10px; opacity: 0.6; }
</style>
