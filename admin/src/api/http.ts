import axios, {
  type AxiosAdapter,
  type AxiosInstance,
  type InternalAxiosRequestConfig,
} from 'axios'
import type { TokenPair } from '@/types/api'
import { TokenStorage, tokenStorage } from './tokenStorage'
import { toApiError } from './errors'

/** 不需要（也不能）带 access token、且 401 时不触发刷新的路径 */
const AUTH_FREE = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/logout', '/health', '/bootstrap/pubkey']

export function apiRoot(base = import.meta.env.VITE_API_BASE ?? ''): string {
  return `${base.replace(/\/+$/, '')}/api/v1`
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
  const baseURL = opts.baseURL ?? apiRoot()

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
