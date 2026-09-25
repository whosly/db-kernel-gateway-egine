<script setup lang="ts">
import { inject, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getAuthMode, login } from '../api/consoleApi'

const router = useRouter()
const refreshAuth = inject<() => Promise<void>>('refreshAuth', async () => {})
const username = ref('admin')
const password = ref('admin')
const error = ref<string | null>(null)
const loading = ref(false)
const mode = ref('form')
const hint = ref('')
const oidc = ref(false)
const ssoLoginUrl = ref('/oauth2/authorization/console')

onMounted(async () => {
  try {
    const m = await getAuthMode()
    mode.value = m.mode || 'open'
    oidc.value = !!m.oidc
    if (m.ssoLoginUrl) ssoLoginUrl.value = m.ssoLoginUrl
    if (m.open) hint.value = '当前为 open 模式，无需登录。'
    else if (m.token) hint.value = '当前为 token 模式，请在 localStorage.consoleApiToken 设置 Bearer Token。'
    else if (m.oidc) {
      hint.value = m.oidcConfigured === false
        ? 'OIDC 模式已启用但尚未配置 issuer-uri/client-id，请联系运维。'
        : '当前为 OIDC SSO 模式，请使用下方按钮跳转 IdP 登录。'
    } else hint.value = '请使用表单账号登录（默认 lab：admin/admin · operator/operator · viewer/viewer）。'
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
    await refreshAuth()
    router.replace('/')
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function ssoLogin() {
  window.location.href = ssoLoginUrl.value
}
</script>

<template>
  <div class="login">
    <div class="card">
      <h1>管控台登录</h1>
      <p class="muted">{{ hint }}</p>

      <template v-if="oidc">
        <button type="button" class="primary sso" @click="ssoLogin">使用 SSO 登录</button>
        <p class="muted tiny">将跳转 {{ ssoLoginUrl }} · 成功后回到 /console/</p>
      </template>

      <form v-else @submit.prevent="submit" class="form">
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
      </form>

      <p class="muted tiny">mode={{ mode }} · 角色 CONSOLE_ADMIN / CONSOLE_VIEWER</p>
    </div>
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
.form { display: flex; flex-direction: column; gap: 0.75rem; }
label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.85rem; color: var(--text-muted); }
input { padding: 0.5rem 0.65rem; border-radius: 8px; border: 1px solid var(--border); background: var(--bg-card); color: var(--text); }
.primary {
  padding: 0.55rem 0.85rem; border-radius: 8px; border: none;
  background: #2563eb; color: #fff; font-weight: 600; cursor: pointer;
}
.primary:disabled { opacity: 0.6; cursor: not-allowed; }
.sso { width: 100%; }
.err { color: #ef4444; }
.muted { color: var(--text-muted); }
.tiny { font-size: 0.75rem; }
</style>
