<script setup lang="ts">
import { ref, provide } from 'vue'
import ConsoleLayout from './layouts/ConsoleLayout.vue'

const toast = ref<string | null>(null)
let toastTimer: ReturnType<typeof setTimeout> | null = null

function showToast(message: string) {
  toast.value = message
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toast.value = null
  }, 3200)
}

provide('toast', showToast)
</script>

<template>
  <ConsoleLayout />
  <div v-if="toast" class="toast">{{ toast }}</div>
</template>
