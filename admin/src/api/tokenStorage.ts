import type { JwtClaims, TokenPair, User } from '@/types/api'
import { base64UrlDecodeUtf8 } from '@/utils/base64url'

/**
 * 令牌持久化（localStorage）。纯模块、不依赖 Pinia，方便拦截器与单测直接使用。
 * 注意：refresh token 每次使用都会轮换，旧的一旦被重放，后端会吊销整个令牌族，
 * 所以这里必须永远只保存“最新的一枚”。
 */

const KEY_TOKENS = 'couple.admin.tokens'
const KEY_DEVICE = 'couple.admin.deviceId'

export interface StoredTokens {
  accessToken: string
  refreshToken: string
  accessExpiresAt: number
  user: User
}

export interface KeyValueStore {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

function memoryStore(): KeyValueStore {
  const m = new Map<string, string>()
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, v),
    removeItem: (k) => void m.delete(k),
  }
}

function defaultStore(): KeyValueStore {
  try {
    if (typeof localStorage !== 'undefined') return localStorage
  } catch {
    /* 隐私模式等情况下访问 localStorage 会抛错 */
  }
  return memoryStore()
}

/** Crockford Base32 ULID，用作 deviceId */
export function ulid(now = Date.now()): string {
  const alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
  let time = ''
  let t = now
  for (let i = 0; i < 10; i++) {
    time = alphabet.charAt(t % 32) + time
    t = Math.floor(t / 32)
  }
  const rand = new Uint8Array(16)
  crypto.getRandomValues(rand)
  let r = ''
  for (let i = 0; i < 16; i++) r += alphabet.charAt((rand[i] ?? 0) % 32)
  return time + r
}

export function decodeJwt(token: string): JwtClaims | null {
  const parts = token.split('.')
  if (parts.length !== 3) return null
  try {
    return JSON.parse(base64UrlDecodeUtf8(parts[1] ?? '')) as JwtClaims
  } catch {
    return null
  }
}

export class TokenStorage {
  private cache: StoredTokens | null | undefined
  constructor(private readonly store: KeyValueStore = defaultStore()) {}

  get(): StoredTokens | null {
    // 每次都从存储读，以便感知其它标签页完成的轮换
    const raw = this.store.getItem(KEY_TOKENS)
    if (!raw) return (this.cache = null)
    try {
      this.cache = JSON.parse(raw) as StoredTokens
      return this.cache
    } catch {
      this.store.removeItem(KEY_TOKENS)
      return (this.cache = null)
    }
  }

  set(pair: TokenPair | StoredTokens): void {
    const v: StoredTokens = {
      accessToken: pair.accessToken,
      refreshToken: pair.refreshToken,
      accessExpiresAt: pair.accessExpiresAt,
      user: pair.user,
    }
    this.cache = v
    this.store.setItem(KEY_TOKENS, JSON.stringify(v))
  }

  clear(): void {
    this.cache = null
    this.store.removeItem(KEY_TOKENS)
  }

  get accessToken(): string | null {
    return this.get()?.accessToken ?? null
  }

  get refreshToken(): string | null {
    return this.get()?.refreshToken ?? null
  }

  /** access token 是否已过期或将在 skewMs 内过期 */
  isAccessExpiring(skewMs = 30_000, now = Date.now()): boolean {
    const t = this.get()
    if (!t) return true
    return t.accessExpiresAt - skewMs <= now
  }

  claims(): JwtClaims | null {
    const at = this.accessToken
    return at ? decodeJwt(at) : null
  }

  isAdmin(): boolean {
    return this.claims()?.role === 'admin'
  }

  /** 浏览器级稳定的设备 ID（登录与刷新都需要携带） */
  deviceId(): string {
    let id = this.store.getItem(KEY_DEVICE)
    if (!id) {
      id = ulid()
      this.store.setItem(KEY_DEVICE, id)
    }
    return id
  }
}

export const tokenStorage = new TokenStorage()
