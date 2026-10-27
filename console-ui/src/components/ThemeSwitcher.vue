<script setup lang="ts">
import { useTheme, type ThemePreference } from '../composables/useTheme'

const { preference, setTheme } = useTheme()

const options: { value: ThemePreference; label: string; title: string }[] = [
  { value: 'light', label: '浅色', title: '浅色主题' },
  { value: 'dark', label: '深色', title: '深色主题' },
  { value: 'system', label: '系统', title: '跟随系统外观' },
]
</script>

<template>
  <div class="theme-switch" role="group" aria-label="主题">
    <button
      v-for="opt in options"
      :key="opt.value"
      type="button"
      class="theme-btn"
      :class="{ active: preference === opt.value }"
      :title="opt.title"
      :aria-pressed="preference === opt.value"
      @click="setTheme(opt.value)"
    >
      <span class="icon" aria-hidden="true">
        <svg v-if="opt.value === 'light'" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="4" />
          <path stroke-linecap="round" d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
        </svg>
        <svg v-else-if="opt.value === 'dark'" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M21 14.5A8.5 8.5 0 1 1 9.5 3 7 7 0 0 0 21 14.5z" />
        </svg>
        <svg v-else viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="3" y="4" width="18" height="16" rx="2" />
          <path d="M12 4v16" />
        </svg>
      </span>
      <span class="lbl">{{ opt.label }}</span>
    </button>
  </div>
</template>

<style scoped>
.theme-switch {
  display: inline-flex;
  align-items: center;
  padding: 2px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--border);
  background: var(--bg-card);
  gap: 1px;
}
.theme-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.28rem;
  padding: 0.22rem 0.55rem;
  border: 0;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--text-muted);
  font-size: 0.7rem;
  font-weight: 600;
  line-height: 1.2;
  box-shadow: none;
}
.theme-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text-secondary);
  border-color: transparent;
}
.theme-btn.active {
  background: var(--accent-soft);
  color: var(--accent);
}
.theme-btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--accent-ring);
}
.icon {
  display: grid;
  place-items: center;
}
@media (max-width: 720px) {
  .lbl { display: none; }
  .theme-btn { padding: 0.28rem 0.42rem; }
}
</style>
