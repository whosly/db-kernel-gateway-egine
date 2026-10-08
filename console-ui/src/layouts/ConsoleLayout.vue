<script setup lang="ts">
import { computed, inject, ref, type Ref } from 'vue'
import { useRoute, RouterLink, RouterView } from 'vue-router'

const route = useRoute()
const title = computed(() => (route.meta.title as string) || '管控台')
const authUser = inject<Ref<string | null>>('authUser', ref(null))
const authMode = inject<Ref<string>>('authMode', ref('open'))
const logout = inject<() => void>('logout', () => {})

const nav = [
  { to: '/', label: '总览' },
  { to: '/instances', label: '网关实例' },
  { to: '/catalog', label: '类型目录' },
  { to: '/sql', label: 'SQL 工作台' },
  { to: '/alerts', label: '告警' },
  { to: '/ops', label: '运维 / 安全' },
]
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">GW</div>
        <div>
          <div class="brand-title">数据库管控台</div>
          <div class="brand-sub">实例中心 · 协议无关</div>
        </div>
      </div>
      <nav>
        <RouterLink
          v-for="item in nav"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          active-class="active"
        >
          {{ item.label }}
        </RouterLink>
      </nav>
    </aside>
    <main class="main">
      <header class="topbar">
        <h1>{{ title }}</h1>
        <span class="hint">刷新约 6s · 隐藏页暂停</span>
        <span v-if="authUser" class="auth">{{ authUser }} · {{ authMode }}
          <button type="button" class="link" @click="logout">退出</button>
        </span>
      </header>
      <section class="content">
        <RouterView />
      </section>
    </main>
  </div>
</template>

<style scoped>
.shell { display: flex; min-height: 100vh; }
.sidebar {
  width: var(--sidebar-w);
  background: var(--bg-elevated);
  border-right: 1px solid var(--border);
  padding: 1.25rem 1rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}
.brand { display: flex; gap: 0.75rem; align-items: center; }
.brand-mark {
  width: 40px; height: 40px; border-radius: 10px;
  background: linear-gradient(135deg, #3b82f6, #06b6d4);
  display: grid; place-items: center; font-weight: 700;
}
.brand-title { font-weight: 650; }
.brand-sub { font-size: 0.75rem; color: var(--text-muted); }
.nav-item {
  display: block;
  padding: 0.65rem 0.85rem;
  border-radius: 8px;
  color: var(--text-muted);
  margin-bottom: 0.35rem;
}
.nav-item.active, .nav-item:hover {
  background: var(--accent-soft);
  color: var(--text);
}
.main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.topbar {
  display: flex; align-items: baseline; justify-content: space-between;
  padding: 1.25rem 1.5rem 0.5rem;
}
.topbar h1 { margin: 0; font-size: 1.35rem; }
.hint { color: var(--text-muted); font-size: 0.8rem; }
.content { padding: 1rem 1.5rem 2rem; }
.auth { margin-left: auto; font-size: 0.8rem; color: var(--text-muted); }
.link { background:none;border:0;color:var(--accent,#3b82f6);cursor:pointer; }
</style>
