<script setup lang="ts">
import { ref, provide, onMounted, nextTick } from 'vue'
import { ensureThemeBootstrapped } from './composables/useTheme'
import type { ConfirmFn, ConfirmOptions } from './composables/useConfirm'
import ConfirmDialog from './components/ConfirmDialog.vue'
import { useRouter } from 'vue-router'
import ConsoleLayout from './layouts/ConsoleLayout.vue'
import { setUnauthorizedHandler } from './api/client'
import { getAuthMode, getAuthMe, logout as apiLogout } from './api/consoleApi'

export type ToastType = 'ok' | 'err' | 'info'
export type ToastFn = (message: string, type?: ToastType) => void

const router = useRouter()
const toast = ref<{ message: string; type: ToastType } | null>(null)
let toastTimer: ReturnType<typeof setTimeout> | null = null
const authUser = ref<string | null>(null)
const authMode = ref('open')
const authRoles = ref<string[]>([])
const permissions = ref<string[]>([])

function inferToastType(message: string): ToastType {
  if (/失败|错误|拒绝|不可|冲突|占用|需要 |Abort|Error|error|503|404|401|禁止/i.test(message)) {
    return 'err'
  }
  if (/已|完成|成功|可达|保存|创建|启动|停止|克隆|导入|导出|清空|更新|删除|触发|通过/.test(message)) {
    return 'ok'
  }
  return 'info'
}

function showToast(message: string, type?: ToastType) {
  const resolved = type ?? inferToastType(message)
  toast.value = { message, type: resolved }
  if (toastTimer) clearTimeout(toastTimer)
  const ms = resolved === 'err' ? 5200 : 3200
  toastTimer = setTimeout(() => {
    toast.value = null
  }, ms)
}

function dismissToast() {
  toast.value = null
  if (toastTimer) clearTimeout(toastTimer)
}

provide('toast', showToast as ToastFn)

const confirmOpen = ref(false)
const confirmOptions = ref<ConfirmOptions | null>(null)
const confirmDialogRef = ref<InstanceType<typeof ConfirmDialog> | null>(null)
let confirmResolve: ((v: boolean) => void) | null = null

const askConfirm: ConfirmFn = (opts) => {
  const normalized: ConfirmOptions =
    typeof opts === 'string' ? { message: opts, danger: true } : opts
  confirmOptions.value = normalized
  confirmOpen.value = true
  nextTick(() => confirmDialogRef.value?.focusConfirm())
  return new Promise<boolean>((resolve) => {
    confirmResolve = resolve
  })
}

function settleConfirm(ok: boolean) {
  confirmOpen.value = false
  confirmOptions.value = null
  const r = confirmResolve
  confirmResolve = null
  r?.(ok)
}

provide('confirm', askConfirm)
provide('authUser', authUser)
provide('authMode', authMode)
provide('authRoles', authRoles)
provide('permissions', permissions)

async function refreshAuth() {
  try {
    const mode = await getAuthMode()
    authMode.value = mode.mode || 'open'
    const me = await getAuthMe()
    authUser.value = me.authenticated ? me.username || 'user' : null
    authRoles.value = me.roles || []
    permissions.value = me.permissions || []
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
  authRoles.value = []
  permissions.value = []
  if (authMode.value === 'form' || authMode.value === 'oidc') {
    router.push({ name: 'login' })
  }
}

provide('logout', doLogout)
provide('refreshAuth', refreshAuth)

onMounted(() => {
  ensureThemeBootstrapped()
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
  <div
    v-if="toast"
    class="toast"
    :class="'toast-' + toast.type"
    role="status"
    aria-live="polite"
  >
    <span class="toast-dot" aria-hidden="true" />
    <span class="toast-body">{{ toast.message }}</span>
    <button type="button" class="toast-x ghost" aria-label="关闭提示" @click="dismissToast">×</button>
  </div>
  <ConfirmDialog
    ref="confirmDialogRef"
    :open="confirmOpen"
    :options="confirmOptions"
    @confirm="settleConfirm(true)"
    @cancel="settleConfirm(false)"
  />
</template>
