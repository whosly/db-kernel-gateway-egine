<script setup lang="ts">
import { computed, inject, onMounted, ref, watch, type Ref } from 'vue'
import { useRoute, RouterLink, RouterView } from 'vue-router'
import { getOverview } from '../api/consoleApi'
import ThemeSwitcher from '../components/ThemeSwitcher.vue'

const route = useRoute()
const title = computed(() => (route.meta.title as string) || '管控台')
const authUser = inject<Ref<string | null>>('authUser', ref(null))
const authMode = inject<Ref<string>>('authMode', ref('open'))
const authRoles = inject<Ref<string[]>>('authRoles', ref([]))
const logout = inject<() => void>('logout', () => {})

const healthChip = ref<{ running: number; total: number; bound: number } | null>(null)
const healthErr = ref(false)
const sidebarOpen = ref(false)

function closeSidebar() {
  sidebarOpen.value = false
}

async function refreshHealthChip() {
  try {
    const d = await getOverview()
    const health = d.health || {}
    healthChip.value = {
      running: Number(health.runningCount ?? 0),
      total: d.instances?.length ?? 0,
      bound: Number(health.boundCount ?? 0),
    }
    healthErr.value = false
  } catch {
    healthErr.value = true
  }
}

onMounted(() => {
  refreshHealthChip()
  setInterval(refreshHealthChip, 12000)
})

watch(() => route.path, () => {
  sidebarOpen.value = false
})

const nav = [
  { to: '/', label: '总览', exact: true },
  { to: '/instances', label: '网关实例' },
  { to: '/catalog', label: '类型目录' },
  { to: '/sql', label: 'SQL 工作台' },
  { to: '/alerts', label: '告警' },
  { to: '/ops', label: '运维 / 安全' },
]

function isActive(to: string, exact?: boolean) {
  const path = route.path
  if (exact) return path === '/' || path === ''
  return path === to || path.startsWith(to + '/')
}

const iconPaths: Record<string, string> = {
  '/': 'M4 10.5 12 4l8 6.5V20h-5v-6H9v6H4V10.5z',
  '/instances': 'M3 5h18v5H3V5zm0 7h18v5H3v-5zm0 7h12v2H3v-2z',
  '/catalog': 'M4 4h16v3H4V4zm0 5h16v3H4V9zm0 5h16v3H4v-3zm0 5h10v2H4v-2z',
  '/sql': 'M4 3h16v18H4V3zm4 6 3 3-3 3h2l3-3-3-3H8zm7 6h3v2h-3v-2z',
  '/alerts': 'M12 2a7 7 0 0 1 7 7v3.2l2 3.3H3l2-3.3V9a7 7 0 0 1 7-7zm-2.5 16.5h5a2.5 2.5 0 1 1-5 0z',
  '/ops': 'M12 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6zm0-6.5 1.5 3 3.3.5-2.4 2.3.6 3.3L12 10.2 9 11.6l.6-3.3-2.4-2.3 3.3-.5L12 2.5z',
}

const roleLabel = computed(() => {
  const roles = authRoles.value || []
  if (roles.some((r) => r.includes('ADMIN'))) return 'ADMIN'
  if (roles.some((r) => r.includes('OPERATOR'))) return 'OPERATOR'
  if (roles.some((r) => r.includes('VIEWER'))) return 'VIEWER'
  return authMode.value === 'open' ? 'OPEN' : '—'
})
</script>

<template>
  <div class="shell" :class="{ 'nav-open': sidebarOpen }">
    <div v-if="sidebarOpen" class="nav-scrim" aria-hidden="true" @click="closeSidebar" />
    <aside class="sidebar" :class="{ open: sidebarOpen }" id="console-sidebar">
      <div class="brand">
        <div class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M4 7h16M4 12h16M4 17h10" stroke-linecap="round" />
            <circle cx="18" cy="17" r="2.5" />
          </svg>
        </div>
        <div class="brand-text">
          <div class="brand-title">数据库管控台</div>
          <div class="brand-sub">实例中心 · 协议无关</div>
        </div>
      </div>

      <nav class="nav" aria-label="主导航">
        <RouterLink
          v-for="item in nav"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          active-class=""
          exact-active-class=""
          :class="{ active: isActive(item.to, item.exact) }"
        >
          <span class="nav-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true">
              <path :d="iconPaths[item.to]" />
            </svg>
          </span>
          <span class="nav-label">{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="sidebar-foot">
        <div class="foot-chip">
          <span class="dot" :class="healthErr ? 'bad' : 'ok'" />
          <span v-if="healthChip">
            {{ healthChip.running }}/{{ healthChip.total }} 运行
          </span>
          <span v-else-if="healthErr">健康不可用</span>
          <span v-else>连接中…</span>
        </div>
        <div class="foot-meta">v1 API · 轮询约 6s</div>
      </div>
    </aside>

    <main class="main">
      <header class="topbar">
        <div class="topbar-left">
          <button
            type="button"
            class="menu-btn ghost"
            aria-label="打开导航"
            :aria-expanded="sidebarOpen"
            aria-controls="console-sidebar"
            @click="sidebarOpen = !sidebarOpen"
          >
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path d="M4 7h16M4 12h16M4 17h16" stroke-linecap="round" />
            </svg>
          </button>
          <h1>{{ title }}</h1>
          <span class="crumb">控制面</span>
        </div>
        <div class="topbar-right">
          <ThemeSwitcher />
          <span
            v-if="healthChip"
            class="chip"
            :title="`绑定 ${healthChip.bound}`"
          >
            <span class="dot ok" />
            运行 {{ healthChip.running }} · 实例 {{ healthChip.total }}
          </span>
          <span class="chip mode" :title="'鉴权模式 ' + authMode">
            {{ authMode }}
          </span>
          <span v-if="authUser || authMode === 'open'" class="chip user">
            <span class="avatar">{{ (authUser || 'L').slice(0, 1).toUpperCase() }}</span>
            <span class="user-meta">
              <span class="user-name">{{ authUser || '实验室' }}</span>
              <span class="user-role">{{ roleLabel }}</span>
            </span>
            <button
              v-if="authUser && (authMode === 'form' || authMode === 'oidc')"
              type="button"
              class="ghost link-btn"
              @click="logout"
            >退出</button>
          </span>
        </div>
      </header>
      <section class="content">
        <RouterView />
      </section>
    </main>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  min-height: 100vh;
}
.sidebar {
  width: var(--sidebar-w);
  flex-shrink: 0;
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border);
  padding: 1.1rem 0.85rem 1rem;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
  position: sticky;
  top: 0;
  height: 100vh;
  z-index: 20;
}
.brand {
  display: flex;
  gap: 0.7rem;
  align-items: center;
  padding: 0.15rem 0.4rem;
}
.brand-mark {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: linear-gradient(145deg, #4f8cff 0%, #2dd4bf 100%);
  display: grid;
  place-items: center;
  color: #fff;
  box-shadow: 0 4px 14px rgba(79, 140, 255, 0.35);
  flex-shrink: 0;
}
.brand-title {
  font-weight: 700;
  font-size: 0.95rem;
  letter-spacing: -0.02em;
}
.brand-sub {
  font-size: 0.7rem;
  color: var(--text-muted);
  margin-top: 0.1rem;
}
.nav {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  flex: 1;
  overflow: auto;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 0.65rem;
  padding: 0.58rem 0.75rem;
  border-radius: var(--radius);
  color: var(--text-muted);
  font-size: 0.875rem;
  font-weight: 500;
  border: 1px solid transparent;
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease), border-color var(--dur) var(--ease);
}
.nav-item:hover {
  background: var(--bg-hover);
  color: var(--text-secondary);
}
.nav-item.active {
  background: var(--accent-soft);
  color: var(--text);
  border-color: color-mix(in srgb, var(--accent) 28%, transparent);
  box-shadow: inset 2px 0 0 var(--accent);
}
.nav-icon {
  display: grid;
  place-items: center;
  opacity: 0.9;
  flex-shrink: 0;
}
.nav-item.active .nav-icon { color: var(--accent); opacity: 1; }
.sidebar-foot {
  padding: 0.65rem 0.55rem 0.25rem;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}
.foot-chip {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
}
.foot-meta {
  font-size: 0.68rem;
  color: var(--text-faint);
  padding-left: 1.05rem;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-faint);
  flex-shrink: 0;
}
.dot.ok { background: var(--ok); box-shadow: 0 0 0 3px var(--ok-soft); }
.dot.bad { background: var(--danger); box-shadow: 0 0 0 3px var(--danger-soft); }

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.topbar {
  height: var(--topbar-h);
  min-height: var(--topbar-h);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0 1.35rem;
  border-bottom: 1px solid var(--border);
  background: var(--bg-topbar);
  backdrop-filter: blur(10px);
  position: sticky;
  top: 0;
  z-index: 15;
}
.topbar-left {
  display: flex;
  align-items: baseline;
  gap: 0.65rem;
  min-width: 0;
}
.topbar h1 {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 700;
  letter-spacing: -0.02em;
}
.crumb {
  font-size: 0.72rem;
  color: var(--text-faint);
  padding: 0.1rem 0.45rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
}
.topbar-right {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.28rem 0.65rem;
  border-radius: var(--radius-pill);
  border: 1px solid var(--border);
  background: var(--bg-card);
  font-size: 0.72rem;
  color: var(--text-muted);
  white-space: nowrap;
}
.chip.mode { text-transform: uppercase; letter-spacing: 0.04em; }
.chip.user {
  padding-right: 0.35rem;
  gap: 0.5rem;
}
.avatar {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--accent), #2dd4bf);
  color: #fff;
  font-size: 0.7rem;
  font-weight: 700;
  display: grid;
  place-items: center;
}
.user-meta {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}
.user-name { color: var(--text-secondary); font-weight: 600; font-size: 0.75rem; }
.user-role { color: var(--text-faint); font-size: 0.65rem; }
.link-btn {
  padding: 0.2rem 0.5rem !important;
  font-size: 0.72rem !important;
  color: var(--accent) !important;
}
.content {
  padding: 1.15rem 1.35rem 2rem;
  max-width: var(--content-max);
  width: 100%;
  margin: 0 auto;
}

.menu-btn {
  display: none;
  padding: 0.35rem !important;
  color: var(--text-secondary) !important;
  border-radius: var(--radius-sm);
}
.nav-scrim { display: none; }

@media (max-width: 900px) {
  .menu-btn { display: grid; place-items: center; }
  .nav-scrim {
    display: block;
    position: fixed;
    inset: 0;
    background: var(--bg-overlay);
    z-index: 35;
    backdrop-filter: blur(2px);
  }
  .sidebar {
    position: fixed;
    left: 0;
    top: 0;
    height: 100vh;
    width: min(var(--sidebar-w), 86vw);
    z-index: 40;
    transform: translateX(-105%);
    transition: transform var(--dur-slow) var(--ease);
    box-shadow: var(--shadow-lg);
    border-right: 1px solid var(--border);
  }
  .sidebar.open { transform: translateX(0); }
  .sidebar-foot { display: flex; }
}
</style>
