import { computed, onMounted, onUnmounted, ref, type Ref } from 'vue'

export type ThemePreference = 'light' | 'dark' | 'system'
export type ResolvedTheme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'console-theme'

const preference: Ref<ThemePreference> = ref('light')
const resolved: Ref<ResolvedTheme> = ref('light')
let media: MediaQueryList | null = null
let bootstrapped = false

function readStored(): ThemePreference {
  try {
    const raw = localStorage.getItem(THEME_STORAGE_KEY)
    if (raw === 'light' || raw === 'dark' || raw === 'system') return raw
  } catch {
    /* ignore */
  }
  return 'light'
}

function systemIsDark(): boolean {
  return typeof window !== 'undefined'
    && window.matchMedia
    && window.matchMedia('(prefers-color-scheme: dark)').matches
}

export function resolveTheme(pref: ThemePreference): ResolvedTheme {
  if (pref === 'system') return systemIsDark() ? 'dark' : 'light'
  return pref
}

export function applyTheme(pref: ThemePreference) {
  const next = resolveTheme(pref)
  preference.value = pref
  resolved.value = next
  const root = document.documentElement
  root.setAttribute('data-theme', next)
  root.setAttribute('data-theme-pref', pref)
  try {
    localStorage.setItem(THEME_STORAGE_KEY, pref)
  } catch {
    /* ignore */
  }
}

function onSystemChange() {
  if (preference.value === 'system') {
    applyTheme('system')
  }
}

/** Call once from App / layout so media listener is shared. */
export function ensureThemeBootstrapped() {
  if (bootstrapped) return
  bootstrapped = true
  applyTheme(readStored())
  if (typeof window !== 'undefined' && window.matchMedia) {
    media = window.matchMedia('(prefers-color-scheme: dark)')
    media.addEventListener?.('change', onSystemChange)
  }
}

export function useTheme() {
  ensureThemeBootstrapped()

  onMounted(() => {
    ensureThemeBootstrapped()
  })

  onUnmounted(() => {
    /* keep global listener; do not remove so other mounts still work */
  })

  const label = computed(() => {
    if (preference.value === 'light') return '浅色'
    if (preference.value === 'dark') return '深色'
    return '跟随系统'
  })

  function setTheme(pref: ThemePreference) {
    applyTheme(pref)
  }

  function cycleTheme() {
    const order: ThemePreference[] = ['light', 'dark', 'system']
    const i = order.indexOf(preference.value)
    setTheme(order[(i + 1) % order.length])
  }

  return {
    preference,
    resolved,
    label,
    setTheme,
    cycleTheme,
  }
}
