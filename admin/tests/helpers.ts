import { AxiosError, type AxiosAdapter, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import type { KeyValueStore } from '@/api/tokenStorage'
import type { TokenPair, User } from '@/types/api'

export function memStore(): KeyValueStore & { map: Map<string, string> } {
  const map = new Map<string, string>()
  return {
    map,
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => void map.set(k, v),
    removeItem: (k) => void map.delete(k),
  }
}

function b64url(s: string) {
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export function fakeJwt(claims: Record<string, unknown>): string {
  return `${b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))}.${b64url(JSON.stringify(claims))}.sig`
}

export const user: User = {
  id: '01JTESTUSER0000000000000000',
  username: 'admin',
  displayName: '管理员',
  avatarUrl: null,
  coupleId: null,
  createdAt: 0,
}

export function pair(n: number, expiresInMs = 15 * 60_000, role = 'admin'): TokenPair {
  return {
    accessToken: fakeJwt({ sub: user.id, cid: null, did: 'dev', role, n }),
    refreshToken: `rt-${n}`,
    accessExpiresAt: Date.now() + expiresInMs,
    user,
  }
}

export type Handler = (config: InternalAxiosRequestConfig) => { status: number; data?: unknown } | Promise<{ status: number; data?: unknown }>

/** 根据 handler 返回的状态码构造 axios 响应 / 错误 */
export function makeAdapter(handler: Handler): AxiosAdapter {
  return async (config) => {
    const { status, data } = await handler(config)
    const response: AxiosResponse = { status, statusText: String(status), data, headers: {}, config }
    if (status >= 200 && status < 300) return response
    throw new AxiosError(`HTTP ${status}`, AxiosError.ERR_BAD_RESPONSE, config, null, response)
  }
}

export const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms))
