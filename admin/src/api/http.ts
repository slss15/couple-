import axios, {
  type AxiosAdapter,
  type AxiosInstance,
  type InternalAxiosRequestConfig,
} from 'axios'
import type { TokenPair } from '@/types/api'
import { TokenStorage, tokenStorage } from './tokenStorage'
import { toApiError } from './errors'

/** 不需要（也不能）带 access token、且 401 时不触发刷新的路径 */
const AUTH_FREE = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/logout', '/health', '/bootstrap/pubkey', '/setup', '/setup/status']

/**
 * 后端根地址（不含 /api/v1）。优先级：调用方显式传入 > 容器启动时注入的
 * window.__COUPLE_CONFIG__.apiBase > 构建期 VITE_API_BASE > 同源。
 *
 * 每次调用都重新读 window，而不是在模块初始化时缓存一次：/config.js 是 nginx 后置注入的
 * （后端 AdminStatic.kt 也会输出同样的对象），部署时只改 COUPLE_API_BASE / ADMIN_API_BASE
 * 并重启容器即可换域名；缓存会把“脚本谁先加载”变成只在线上复现的隐性 bug。
 */
export function apiRoot(base?: string): string {
  const runtime = typeof window === 'undefined' ? undefined : window.__COUPLE_CONFIG__?.apiBase
  const resolved = base ?? runtime ?? import.meta.env.VITE_API_BASE ?? ''
  return `${resolved.trim().replace(/\/+$/, '')}/api/v1`
}

export interface HttpOptions {
  baseURL?: string
  storage?: TokenStorage
  /** 刷新失败（令牌被吊销/过期）时回调，通常跳转登录页 */
  onAuthFailure?: () => void
  /** 测试用：替换 axios 适配器 */
  adapter?: AxiosAdapter
  /** access token 提前多少毫秒视为将过期并主动刷新 */
  refreshSkewMs?: number
}

type RetriableConfig = InternalAxiosRequestConfig & { _retried?: boolean }

export interface Http extends AxiosInstance {
  /** 单飞刷新：并发调用共享同一个 Promise */
  refreshTokens(): Promise<string>
}

function isAuthFree(url?: string): boolean {
  if (!url) return false
  return AUTH_FREE.some((p) => url === p || url.startsWith(p + '?'))
}

export function createHttp(opts: HttpOptions = {}): Http {
  const storage = opts.storage ?? tokenStorage
  const skew = opts.refreshSkewMs ?? 30_000
  // 显式传入 baseURL（测试、嵌入场景）时固定使用；否则每次请求前重新解析运行时配置
  const fixedBase = opts.baseURL
  const baseURL = fixedBase ?? apiRoot()

  const instance = axios.create({ baseURL, timeout: 15_000, adapter: opts.adapter }) as Http
  // 刷新请求使用独立实例，避免经过本实例的拦截器造成递归
  const raw = axios.create({ baseURL, timeout: 15_000, adapter: opts.adapter })

  let inflight: Promise<string> | null = null

  async function doRefresh(): Promise<string> {
    const before = storage.refreshToken
    if (!before) throw toApiError(new Error('NO_REFRESH_TOKEN'))

    const run = async (): Promise<string> => {
      // 进入锁后再读一次：如果其它标签页已经完成轮换，直接用新的 access token
      const current = storage.get()
      if (current && current.refreshToken !== before && !storage.isAccessExpiring(skew)) {
        return current.accessToken
      }
      const rt = current?.refreshToken ?? before
      // raw 实例不经过拦截器，这里手动跟上运行时配置，避免刷新令牌打到旧域名
      if (fixedBase === undefined) raw.defaults.baseURL = apiRoot()
      const { data } = await raw.post<TokenPair>('/auth/refresh', {
        refreshToken: rt,
        deviceId: storage.deviceId(),
      })
      storage.set(data)
      return data.accessToken
    }

    // 跨标签页互斥（Web Locks API）；不支持时退化为本页单飞
    const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined
    if (locks?.request) {
      return locks.request('couple-admin-refresh', run) as unknown as Promise<string>
    }
    return run()
  }

  instance.refreshTokens = () => {
    if (!inflight) {
      inflight = doRefresh()
        .catch((e) => {
          storage.clear()
          opts.onAuthFailure?.()
          throw toApiError(e)
        })
        .finally(() => {
          inflight = null
        })
    }
    return inflight
  }

  instance.interceptors.request.use(async (config) => {
    // 每次请求前重新解析 baseURL：/config.js 与模块的加载顺序、多份 config.js 覆盖、
    // 部署后换域名等场景都不必依赖“初始化那一刻读到的值是对的”
    if (fixedBase === undefined) config.baseURL = apiRoot()
    if (isAuthFree(config.url)) return config
    const t = storage.get()
    if (!t) return config
    let token = t.accessToken
    if (storage.isAccessExpiring(skew)) {
      token = await instance.refreshTokens()
    }
    config.headers.set('Authorization', `Bearer ${token}`)
    return config
  })

  instance.interceptors.response.use(
    (r) => r,
    async (error) => {
      const config = error?.config as RetriableConfig | undefined
      const status = error?.response?.status
      if (status === 401 && config && !config._retried && !isAuthFree(config.url) && storage.refreshToken) {
        config._retried = true
        const token = await instance.refreshTokens()
        config.headers.set('Authorization', `Bearer ${token}`)
        return instance.request(config)
      }
      throw toApiError(error)
    },
  )

  return instance
}
