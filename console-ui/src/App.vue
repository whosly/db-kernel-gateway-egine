<script setup lang="ts">
import { ref, provide, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import ConsoleLayout from './layouts/ConsoleLayout.vue'
import { setUnauthorizedHandler } from './api/client'
import { getAuthMode, getAuthMe, logout as apiLogout } from './api/consoleApi'

const router = useRouter()
const toast = ref<string | null>(null)
let toastTimer: ReturnType<typeof setTimeout> | null = null
const authUser = ref<string | null>(null)
const authMode = ref('open')

function showToast(message: string) {
  toast.value = message
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toast.value = null
  }, 3200)
}

provide('toast', showToast)
provide('authUser', authUser)
provide('authMode', authMode)

async function refreshAuth() {
  try {
    const mode = await getAuthMode()
    authMode.value = mode.mode || 'open'
    const me = await getAuthMe()
    authUser.value = me.authenticated ? me.username || 'user' : null
    if ((mode.formLogin || mode.mode === 'form') && !me.authenticated) {
      if (router.currentRoute.value.name !== 'login') {
        router.push({ name: 'login' })
      }
    }
  } catch {
    /* open/token may still work */
  }
}

async function doLogout() {
  try {
    await apiLogout()
  } catch {
    /* ignore */
  }
  authUser.value = null
  if (authMode.value === 'form' || authMode.value === 'oidc') {
    router.push({ name: 'login' })
  }
}

provide('logout', doLogout)

onMounted(() => {
  setUnauthorizedHandler(() => {
    if (router.currentRoute.value.name !== 'login') {
      router.push({ name: 'login' })
    }
  })
  refreshAuth()
})
</script>

<template>
  <RouterView v-if="$route.name === 'login'" />
  <ConsoleLayout v-else />
  <div v-if="toast" class="toast">{{ toast }}</div>
</template>
