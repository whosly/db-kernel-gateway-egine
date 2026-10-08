import { onMounted, onUnmounted, ref } from 'vue'

/**
 * Poll `fn` every `intervalMs` (default 6s). Pauses while document.hidden.
 */
export function usePolling(fn: () => void | Promise<void>, intervalMs = 6000) {
  const active = ref(false)
  let timer: ReturnType<typeof setInterval> | null = null

  async function tick() {
    if (document.hidden) return
    try {
      await fn()
    } catch (e) {
      console.warn('poll failed', e)
    }
  }

  function start() {
    if (timer) return
    active.value = true
    void tick()
    timer = setInterval(() => void tick(), intervalMs)
  }

  function stop() {
    active.value = false
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  function onVisibility() {
    if (!document.hidden && active.value) void tick()
  }

  onMounted(() => {
    document.addEventListener('visibilitychange', onVisibility)
    start()
  })

  onUnmounted(() => {
    document.removeEventListener('visibilitychange', onVisibility)
    stop()
  })

  return { active, start, stop, refresh: tick }
}
