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
    <div class="login-card">
      <div class="brand-row">
        <div class="mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M4 7h16M4 12h16M4 17h10" stroke-linecap="round" />
            <circle cx="18" cy="17" r="2.5" />
          </svg>
        </div>
        <div>
          <h1>管控台登录</h1>
          <p class="sub">数据库网关 · 控制面</p>
        </div>
      </div>
      <p class="hint">{{ hint }}</p>

      <template v-if="oidc">
        <button type="button" class="primary sso" @click="ssoLogin">使用 SSO 登录</button>
        <p class="muted tiny">将跳转 {{ ssoLoginUrl }} · 成功后回到 /console/</p>
      </template>

      <form v-else class="form" @submit.prevent="submit">
        <div class="field">
          <label for="login-user">用户名</label>
          <input id="login-user" v-model="username" autocomplete="username" required />
        </div>
        <div class="field">
          <label for="login-pass">密码</label>
          <input id="login-pass" v-model="password" type="password" autocomplete="current-password" required />
        </div>
        <p v-if="error" class="err" role="alert">{{ error }}</p>
        <button class="primary" :disabled="loading">{{ loading ? '登录中…' : '登录' }}</button>
      </form>

      <p class="footer-meta muted tiny">mode={{ mode }} · VIEWER / OPERATOR / ADMIN</p>
    </div>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 1.5rem;
  background:
    radial-gradient(900px 480px at 20% 0%, rgba(79, 140, 255, 0.12), transparent 55%),
    radial-gradient(700px 400px at 90% 20%, rgba(45, 212, 191, 0.08), transparent 50%),
    var(--bg);
}
.login-card {
  width: min(26rem, 94vw);
  display: flex;
  flex-direction: column;
  gap: 0.85rem;
  padding: 1.65rem 1.5rem 1.35rem;
  border-radius: var(--radius-xl);
  border: 1px solid var(--border);
  background: color-mix(in srgb, var(--bg-elevated) 92%, #000);
  box-shadow: var(--shadow-lg);
}
.brand-row {
  display: flex;
  gap: 0.85rem;
  align-items: center;
}
.mark {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: linear-gradient(145deg, #4f8cff, #2dd4bf);
  display: grid;
  place-items: center;
  color: #fff;
  box-shadow: 0 6px 18px rgba(79, 140, 255, 0.35);
}
h1 {
  margin: 0;
  font-size: 1.2rem;
  letter-spacing: -0.02em;
}
.sub {
  margin: 0.15rem 0 0;
  font-size: 0.75rem;
  color: var(--text-muted);
}
.hint {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.85rem;
  line-height: 1.5;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}
.primary {
  margin-top: 0.35rem;
  width: 100%;
  padding: 0.65rem 0.85rem;
}
.sso { width: 100%; }
.err { color: var(--danger); margin: 0.25rem 0; font-size: 0.85rem; }
.muted { color: var(--text-muted); }
.tiny { font-size: 0.75rem; }
.footer-meta { margin: 0.25rem 0 0; }
</style>
