import type { RouteLocationNormalized, RouteLocationRaw } from 'vue-router'

/**
 * 首次初始化守卫：应用加载后查询一次 /setup/status（结果缓存）。
 * - needed=true：除 /setup 外的所有路由都跳转到 /setup（保留 ?token=）
 * - needed=false：访问 /setup 时跳到登录页
 * 查询失败（网络错误、旧版后端没有该接口）时视为无需初始化，不阻塞正常使用。
 */
export function createSetupGuard(fetchNeeded: () => Promise<boolean>) {
  let cached: Promise<boolean> | null = null

  function needed(): Promise<boolean> {
    if (!cached) cached = fetchNeeded().catch(() => false)
    return cached
  }

  async function guard(to: RouteLocationNormalized): Promise<true | RouteLocationRaw> {
    const need = await needed()
    if (need && to.name !== 'setup') {
      const token = typeof to.query.token === 'string' ? to.query.token : undefined
      return { name: 'setup', query: token ? { token } : {} }
    }
    if (!need && to.name === 'setup') return { name: 'login' }
    return true
  }

  return {
    guard,
    /** 初始化成功后调用，之后不再跳转到 /setup */
    markDone() {
      cached = Promise.resolve(false)
    },
    /** 测试用 */
    reset() {
      cached = null
    },
  }
}
