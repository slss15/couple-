import { describe, expect, it, vi } from 'vitest'
import { createHttp } from '@/api/http'
import { ApiError } from '@/api/errors'
import { TokenStorage } from '@/api/tokenStorage'
import { makeAdapter, memStore, pair, tick, type Handler } from './helpers'

function setup(handler: Handler, initial = pair(1)) {
  const storage = new TokenStorage(memStore())
  storage.set(initial)
  const onAuthFailure = vi.fn()
  const http = createHttp({ baseURL: 'http://x/api/v1', storage, onAuthFailure, adapter: makeAdapter(handler) })
  return { http, storage, onAuthFailure }
}

/** 模拟后端：refresh token 轮换，重放旧 token 返回 401 */
function backend(opts: { refreshDelay?: number } = {}) {
  let current = 1
  let refreshCalls = 0
  const seenAuth: string[] = []
  const handler: Handler = async (config) => {
    if (config.url === '/auth/refresh') {
      refreshCalls++
      await tick(opts.refreshDelay ?? 10)
      const body = JSON.parse(config.data as string) as { refreshToken: string; deviceId: string }
      if (body.refreshToken !== `rt-${current}` || !body.deviceId) return { status: 401, data: { code: 'REFRESH_REUSED', message: 'reused' } }
      current++
      return { status: 200, data: pair(current) }
    }
    const auth = String(config.headers.get('Authorization') ?? '')
    seenAuth.push(auth)
    const n = Number(JSON.parse(atob(auth.split('.')[1]!.replace(/-/g, '+').replace(/_/g, '/'))).n)
    if (n !== current) return { status: 401, data: { code: 'TOKEN_EXPIRED', message: 'expired' } }
    return { status: 200, data: { ok: true, url: config.url } }
  }
  return { handler, stats: () => ({ refreshCalls, current, seenAuth }) }
}

describe('axios 拦截器：令牌刷新', () => {
  it('并发 401 只触发一次刷新（single-flight），全部请求用新 token 重试成功', async () => {
    const be = backend()
    const { http, storage } = setup(be.handler)
    // 服务端已轮换到 2，客户端手里还是 1 的 access token（但未到本地过期时间）
    await http.refreshTokens()
    storage.set({ ...storage.get()!, accessToken: pair(1).accessToken })
    const before = be.stats().refreshCalls

    const results = await Promise.all(Array.from({ length: 5 }, (_, i) => http.get(`/admin/x${i}`)))
    expect(results.every((r) => r.status === 200)).toBe(true)
    expect(be.stats().refreshCalls - before).toBe(1)
    expect(storage.refreshToken).toBe('rt-3')
  })

  it('access 即将过期时主动刷新，并发请求共享同一次刷新', async () => {
    const be = backend({ refreshDelay: 20 })
    const { http, storage } = setup(be.handler, pair(1, 5_000)) // 5 秒后过期 < 30 秒 skew
    const results = await Promise.all([http.get('/a'), http.get('/b'), http.get('/c')])
    expect(results.map((r) => r.status)).toEqual([200, 200, 200])
    expect(be.stats().refreshCalls).toBe(1)
    expect(storage.refreshToken).toBe('rt-2')
    expect(be.stats().seenAuth.length).toBe(3) // 没有一次带着旧 token 打到后端
  })

  it('刷新失败：清空令牌并且只回调一次 onAuthFailure', async () => {
    const handler: Handler = (config) =>
      config.url === '/auth/refresh'
        ? { status: 401, data: { code: 'REFRESH_REUSED', message: 'reused' } }
        : { status: 401, data: { code: 'TOKEN_EXPIRED', message: 'expired' } }
    const { http, storage, onAuthFailure } = setup(handler)
    const settled = await Promise.allSettled([http.get('/a'), http.get('/b')])
    expect(settled.every((s) => s.status === 'rejected')).toBe(true)
    const reason = (settled[0] as PromiseRejectedResult).reason
    expect(reason).toBeInstanceOf(ApiError)
    expect(storage.get()).toBeNull()
    expect(onAuthFailure).toHaveBeenCalledTimes(1)
  })

  it('同一请求只重试一次，避免死循环', async () => {
    let calls = 0
    let refreshes = 0
    const handler: Handler = (config) => {
      if (config.url === '/auth/refresh') {
        refreshes++
        return { status: 200, data: pair(10 + refreshes) }
      }
      calls++
      return { status: 401, data: { code: 'FORBIDDEN', message: 'nope' } }
    }
    const { http } = setup(handler)
    await expect(http.get('/a')).rejects.toMatchObject({ status: 401 })
    expect(calls).toBe(2)
    expect(refreshes).toBe(1)
  })

  it('免鉴权接口不带 Authorization，401 时也不刷新', async () => {
    const seen: (string | undefined)[] = []
    let refreshes = 0
    const handler: Handler = (config) => {
      if (config.url === '/auth/refresh') refreshes++
      seen.push(config.headers.get('Authorization') as string | undefined)
      return config.url === '/auth/login'
        ? { status: 401, data: { code: 'INVALID_CREDENTIALS', message: 'bad' } }
        : { status: 200, data: { status: 'ok', version: '0.1.0', protocolVersion: 1 } }
    }
    const { http } = setup(handler)
    await http.get('/health')
    await expect(http.post('/auth/login', {})).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' })
    expect(seen.every((h) => !h)).toBe(true)
    expect(refreshes).toBe(0)
  })

  it('错误体被规范化为 ApiError', async () => {
    const { http } = setup(() => ({ status: 404, data: { code: 'NOT_PAIRED', message: 'not paired' } }))
    const err = await http.get('/couple').catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.code).toBe('NOT_PAIRED')
    expect(err.display).toBe('尚未配对')
  })
})
