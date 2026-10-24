import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { tokenStorage } from '@/api/tokenStorage'
import { api } from '@/api'
import { createSetupGuard } from '@/utils/setupGuard'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    public?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  { path: '/setup', name: 'setup', component: () => import('@/views/SetupView.vue'), meta: { public: true, title: '初始化' } },
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '仪表盘' } },
      { path: 'config', name: 'config', component: () => import('@/views/ServerConfigView.vue'), meta: { title: '服务器配置' } },
      { path: 'releases', name: 'releases', component: () => import('@/views/ReleasesView.vue'), meta: { title: '版本发布' } },
      { path: 'bootstrap', name: 'bootstrap', component: () => import('@/views/BootstrapView.vue'), meta: { title: '引导二维码' } },
      { path: 'audit', name: 'audit', component: () => import('@/views/AuditView.vue'), meta: { title: '审计日志' } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

export const router = createRouter({ history: createWebHistory(import.meta.env.BASE_URL), routes })

export const setupGuard = createSetupGuard(() => api.setupStatus().then((s) => s.needed))

router.beforeEach(async (to) => {
  const setup = await setupGuard.guard(to)
  if (setup !== true) return setup
  if (to.name === 'setup') return true
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
