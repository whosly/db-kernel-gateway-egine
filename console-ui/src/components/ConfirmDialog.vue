<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import type { ConfirmOptions } from '../composables/useConfirm'

const props = defineProps<{
  open: boolean
  options: ConfirmOptions | null
}>()
const emit = defineEmits<{ confirm: []; cancel: [] }>()

const confirmBtn = ref<HTMLButtonElement | null>(null)

function onKey(e: KeyboardEvent) {
  if (!props.open) return
  if (e.key === 'Escape') {
    e.preventDefault()
    emit('cancel')
  } else if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey) {
    // Enter confirms when focus is in the dialog (default button)
    const t = e.target as HTMLElement | null
    if (t?.tagName === 'TEXTAREA') return
    e.preventDefault()
    emit('confirm')
  }
}

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))

function focusConfirm() {
  requestAnimationFrame(() => confirmBtn.value?.focus())
}

defineExpose({ focusConfirm })
</script>

<template>
  <div
    v-if="open && options"
    class="overlay"
    role="alertdialog"
    aria-modal="true"
    :aria-labelledby="'confirm-title'"
    :aria-describedby="'confirm-msg'"
    @click.self="emit('cancel')"
  >
    <div class="dialog">
      <h2 id="confirm-title">{{ options.title || '请确认' }}</h2>
      <p id="confirm-msg" class="msg">{{ options.message }}</p>
      <footer>
        <button type="button" class="ghost" @click="emit('cancel')">
          {{ options.cancelLabel || '取消' }}
        </button>
        <button
          ref="confirmBtn"
          type="button"
          :class="options.danger ? 'danger' : 'primary'"
          @click="emit('confirm')"
        >
          {{ options.confirmLabel || (options.danger ? '确认删除' : '确认') }}
        </button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  background: var(--bg-overlay);
  display: grid;
  place-items: center;
  z-index: 90;
  padding: 1rem;
  backdrop-filter: blur(3px);
  animation: fade-in var(--dur) var(--ease);
}
.dialog {
  width: min(420px, 100%);
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-xl);
  padding: 1.2rem 1.25rem 1.05rem;
  box-shadow: var(--shadow-lg);
}
h2 {
  margin: 0 0 0.55rem;
  font-size: 1.05rem;
  letter-spacing: -0.02em;
}
.msg {
  margin: 0 0 1.15rem;
  color: var(--text-secondary);
  font-size: var(--fs-sm);
  line-height: 1.55;
  white-space: pre-wrap;
}
footer {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
}
@keyframes fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}
</style>
