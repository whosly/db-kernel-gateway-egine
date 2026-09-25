import { createRouter, createWebHistory } from 'vue-router'
import OverviewView from '../views/OverviewView.vue'
import InstancesView from '../views/InstancesView.vue'
import CatalogView from '../views/CatalogView.vue'
import OpsView from '../views/OpsView.vue'
import SqlWorkspaceView from '../views/SqlWorkspaceView.vue'
import LoginView from '../views/LoginView.vue'

export const router = createRouter({
  history: createWebHistory('/console/'),
  routes: [
    { path: '/', name: 'overview', component: OverviewView, meta: { title: '总览' } },
    { path: '/instances', name: 'instances', component: InstancesView, meta: { title: '网关实例' } },
    { path: '/catalog', name: 'catalog', component: CatalogView, meta: { title: '类型目录' } },
    { path: '/ops', name: 'ops', component: OpsView, meta: { title: '运维 / 安全' } },
    { path: '/sql', name: 'sql', component: SqlWorkspaceView, meta: { title: 'SQL 工作台' } },
    { path: '/login', name: 'login', component: LoginView, meta: { title: '登录', public: true } },
  ],
})
