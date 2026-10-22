/**
 * 与 ../contract.md 对齐的类型定义。时间均为 epoch 毫秒，ID 为 ULID 字符串。
 * 契约未细化的结构（turn / storage 的字段）集中在这里，后端确定后只需改这一处。
 */

export interface ApiErrorBody {
  code: string
  message: string
}

export interface HealthInfo {
  status: string
  version: string
  protocolVersion: number
}

export interface User {
  id: string
  username: string
  displayName: string
  avatarUrl: string | null
  coupleId: string | null
  createdAt: number
}

export interface TokenPair {
  accessToken: string
  refreshToken: string
  accessExpiresAt: number
  user: User
}

export interface Couple {
  id: string
  members: [User, User]
  pairedAt: number
  anniversary: number | null
}

export interface BootstrapPubkey {
  alg: 'Ed25519'
  publicKey: string
}

/** 二维码内 d 参数解码后的 JSON */
export interface BootstrapPayload {
  v: number
  baseUrl: string
  mqttUrl: string
  configVersion: number
  issuedAt: number
}

export interface BootstrapResponse {
  uri: string
  /** 契约写作 json；兼容后端返回对象或 JSON 字符串 */
  json: BootstrapPayload | string
}

export type TurnProvider = 'cloudflare' | 'coturn'

/**
 * 密钥字段（apiToken / sharedSecret / secretAccessKey）只写：
 * - GET 返回脱敏字符串（如 "****abcd"）或 null（未设置）
 * - PUT 时字段缺省 = 保持原值；传空字符串 "" = 清除；传其他值 = 覆盖
 */
export interface TurnConfig {
  provider: TurnProvider
  cloudflare?: {
    keyId: string
    apiToken?: string | null
  }
  coturn?: {
    urls: string[]
    sharedSecret?: string | null
    /** 凭证有效期（秒） */
    ttlSeconds?: number
  }
}

export type StorageProvider = 'local' | 'minio' | 'r2'

export interface StorageConfig {
  provider: StorageProvider
  endpoint?: string
  bucket?: string
  region?: string
  publicBaseUrl?: string
  accessKeyId?: string
  secretAccessKey?: string | null
}

export interface AdminConfig {
  baseUrl: string
  mqttUrl: string
  turn: TurnConfig
  storage: StorageConfig
}

/** GET/PUT /admin/config 的响应：后端额外返回 configVersion / updatedAt（契约之外的补充字段） */
export interface AdminConfigView extends Omit<AdminConfig, 'baseUrl' | 'mqttUrl'> {
  baseUrl: string | null
  mqttUrl: string | null
  configVersion?: number
  updatedAt?: number | null
}

export interface AuditEntry {
  id: string
  actorId: string | null
  action: string
  target: string | null
  meta: unknown
  ts: number
}

export interface JwtClaims {
  sub: string
  cid: string | null
  did: string
  role: 'user' | 'admin'
  exp?: number
  iat?: number
}
