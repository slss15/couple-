import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRoot } from '@/api/http'

beforeEach(() => {
  // 避免本机 .env.local 里的构建期变量影响断言
  vi.stubEnv('VITE_API_BASE', '')
})

afterEach(() => {
  vi.unstubAllEnvs()
  delete window.__COUPLE_CONFIG__
})

describe('apiRoot：运行时注入的地址优先', () => {
  it('读取容器启动时写入的 window.__COUPLE_CONFIG__.apiBase', () => {
    window.__COUPLE_CONFIG__ = { apiBase: 'https://api.example.com/', version: '1.2.3' }
    expect(apiRoot()).toBe('https://api.example.com/api/v1')
  })

  it('运行时未注入时回退到同源（dev 代理 / nginx 反代）', () => {
    expect(apiRoot()).toBe('/api/v1')
  })

  it('每次调用都重新读取，部署后换域名不必重新打包', () => {
    expect(apiRoot()).toBe('/api/v1')
    window.__COUPLE_CONFIG__ = { apiBase: 'https://new.example.com' }
    expect(apiRoot()).toBe('https://new.example.com/api/v1')
  })

  it('运行时配置优先于构建期 VITE_API_BASE', () => {
    vi.stubEnv('VITE_API_BASE', 'https://build.example.com')
    window.__COUPLE_CONFIG__ = { apiBase: 'https://runtime.example.com' }
    expect(apiRoot()).toBe('https://runtime.example.com/api/v1')
  })

  it('显式传入的 base 优先于运行时配置', () => {
    window.__COUPLE_CONFIG__ = { apiBase: 'https://api.example.com' }
    expect(apiRoot('http://127.0.0.1:8080')).toBe('http://127.0.0.1:8080/api/v1')
  })
})
