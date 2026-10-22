import { describe, expect, it } from 'vitest'
import { TokenStorage, decodeJwt, ulid } from '@/api/tokenStorage'
import { fakeJwt, memStore, pair } from './helpers'

describe('TokenStorage', () => {
  it('保存、读取、清除令牌', () => {
    const s = new TokenStorage(memStore())
    expect(s.get()).toBeNull()
    const p = pair(1)
    s.set(p)
    expect(s.get()).toEqual({ accessToken: p.accessToken, refreshToken: 'rt-1', accessExpiresAt: p.accessExpiresAt, user: p.user })
    expect(s.refreshToken).toBe('rt-1')
    s.clear()
    expect(s.get()).toBeNull()
    expect(s.accessToken).toBeNull()
  })

  it('总是从底层存储读取，能感知其它标签页的轮换', () => {
    const store = memStore()
    const a = new TokenStorage(store)
    const b = new TokenStorage(store)
    a.set(pair(1))
    b.set(pair(2))
    expect(a.refreshToken).toBe('rt-2')
  })

  it('损坏的 JSON 会被丢弃', () => {
    const store = memStore()
    store.setItem('couple.admin.tokens', '{not json')
    const s = new TokenStorage(store)
    expect(s.get()).toBeNull()
    expect(store.map.has('couple.admin.tokens')).toBe(false)
  })

  it('按 skew 判断 access token 即将过期', () => {
    const s = new TokenStorage(memStore())
    expect(s.isAccessExpiring()).toBe(true) // 无令牌
    s.set(pair(1, 60_000))
    expect(s.isAccessExpiring(30_000)).toBe(false)
    expect(s.isAccessExpiring(90_000)).toBe(true)
    s.set(pair(2, -1))
    expect(s.isAccessExpiring(0)).toBe(true)
  })

  it('从 JWT 解析角色', () => {
    const s = new TokenStorage(memStore())
    s.set(pair(1, 60_000, 'admin'))
    expect(s.isAdmin()).toBe(true)
    s.set(pair(2, 60_000, 'user'))
    expect(s.isAdmin()).toBe(false)
    expect(decodeJwt('garbage')).toBeNull()
    expect(decodeJwt(fakeJwt({ sub: 'x', role: 'admin', did: 'd', cid: null }))?.sub).toBe('x')
  })

  it('deviceId 为 ULID 且稳定', () => {
    const store = memStore()
    const id = new TokenStorage(store).deviceId()
    expect(id).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/)
    expect(new TokenStorage(store).deviceId()).toBe(id)
    expect(ulid(0).slice(0, 10)).toBe('0000000000')
  })
})
