import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { tokenStorage } from '@/api/tokenStorage'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    public?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '仪表盘' } },
      { path: 'config', name: 'config', component: () => import('@/views/ServerConfigView.vue'), meta: { title: '服务器配置' } },
      { path: 'bootstrap', name: 'bootstrap', component: () => import('@/views/BootstrapView.vue'), meta: { title: '引导二维码' } },
      { path: 'audit', name: 'audit', component: () => import('@/views/AuditView.vue'), meta: { title: '审计日志' } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

export const router = createRouter({ history: createWebHistory(import.meta.env.BASE_URL), routes })

router.beforeEach((to) => {
  const authed = !!tokenStorage.get() && tokenStorage.isAdmin()
  if (!to.meta.public && !authed) {
    return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  if (to.name === 'login' && authed) return { name: 'dashboard' }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · Couple 管理后台` : 'Couple 管理后台'
})
