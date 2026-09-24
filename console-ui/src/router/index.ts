import { createRouter, createWebHistory } from 'vue-router'
import OverviewView from '../views/OverviewView.vue'
import InstancesView from '../views/InstancesView.vue'
import CatalogView from '../views/CatalogView.vue'
import OpsView from '../views/OpsView.vue'

export const router = createRouter({
  history: createWebHistory('/console/'),
  routes: [
    { path: '/', name: 'overview', component: OverviewView, meta: { title: '总览' } },
    { path: '/instances', name: 'instances', component: InstancesView, meta: { title: '网关实例' } },
    { path: '/catalog', name: 'catalog', component: CatalogView, meta: { title: '类型目录' } },
    { path: '/ops', name: 'ops', component: OpsView, meta: { title: '运维' } },
  ],
})
