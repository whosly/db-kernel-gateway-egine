<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getAuthMode, login } from '../api/consoleApi'

const router = useRouter()
const username = ref('admin')
const password = ref('admin')
const error = ref<string | null>(null)
const loading = ref(false)
const mode = ref('form')
const hint = ref('')

onMounted(async () => {
  try {
    const m = await getAuthMode()
    mode.value = m.mode || 'open'
    if (m.open) hint.value = '当前为 open 模式，无需登录。'
    else if (m.token) hint.value = '当前为 token 模式，请在 localStorage.consoleApiToken 设置 Bearer Token。'
    else if (m.oidc) hint.value = '当前为 OIDC 模式，请使用 IdP 登录入口。'
    else hint.value = '请使用表单账号登录（默认 lab：admin/admin）。'
    if (m.open) {
      router.replace('/')
    }
  } catch {
    hint.value = '无法读取鉴权模式，仍可尝试表单登录。'
  }
})

async function submit() {
  error.value = null
  loading.value = true
  try {
    const res = await login(username.value, password.value)
    if ((res as { ok?: boolean }).ok === false) {
      error.value = (res as { message?: string }).message || '登录失败'
      return
    }
    router.replace('/')
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <form class="card" @submit.prevent="submit">
      <h1>管控台登录</h1>
      <p class="muted">{{ hint }}</p>
      <label>
        用户名
        <input v-model="username" autocomplete="username" required />
      </label>
      <label>
        密码
        <input v-model="password" type="password" autocomplete="current-password" required />
      </label>
      <p v-if="error" class="err">{{ error }}</p>
      <button class="primary" :disabled="loading">{{ loading ? '登录中…' : '登录' }}</button>
      <p class="muted tiny">mode={{ mode }} · 角色 CONSOLE_ADMIN / CONSOLE_VIEWER</p>
    </form>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh; display: grid; place-items: center;
  background: var(--bg, #0b1220);
}
.card {
  width: min(24rem, 92vw); display: flex; flex-direction: column; gap: 0.75rem;
  padding: 1.5rem; border-radius: 12px; border: 1px solid var(--border, #1f2937);
  background: var(--bg-elevated, #111827);
}
label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.85rem; color: var(--text-muted); }
input { padding: 0.5rem 0.65rem; border-radius: 8px; border: 1px solid var(--border); background: var(--bg-card); color: var(--text); }
.err { color: #ef4444; }
.muted { color: var(--text-muted); }
.tiny { font-size: 0.75rem; }
</style>
