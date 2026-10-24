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

/** 后端根据请求头（X-Forwarded-Proto / X-Forwarded-Host / Host）推导出的建议地址 */
export interface SuggestedUrls {
  publicBaseUrl: string
  mqttUrl: string
}

/** GET /setup/status */
export interface SetupStatus {
  needed: boolean
  suggested: SuggestedUrls | null
}

/** GET/PUT /admin/config 的响应：后端额外返回 configVersion / updatedAt / suggested（契约之外的补充字段） */
export interface AdminConfigView extends Omit<AdminConfig, 'baseUrl' | 'mqttUrl'> {
  baseUrl: string | null
  mqttUrl: string | null
  configVersion?: number
  updatedAt?: number | null
  suggested?: SuggestedUrls | null
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

// ---- 自动更新（contract “Auto-update (P0.5)”） ----

export type ReleaseStatus = 'MIRRORED' | 'PUBLISHED' | 'WITHDRAWN'

/** GET /admin/releases 的一项 */
export interface AppReleaseView {
  id: string
  versionCode: number
  versionName: string
  sha256: string
  size: number
  signingCertSha256: string
  minSupportedVersionCode: number
  notes: string | null
  publishedAt: number
  status: ReleaseStatus
  rolloutPercent: number
  createdAt: number
  /** 服务器磁盘上的 APK 是否还在 */
  fileExists: boolean
}

/** PATCH /admin/releases/{versionCode}，字段均可选 */
export interface ReleasePatch {
  status?: ReleaseStatus
  rolloutPercent?: number
  minSupportedVersionCode?: number
  notes?: string
}

/** POST/GET /admin/releases/sync */
export interface SyncState {
  running: boolean
  lastRunAt: number | null
  lastFinishedAt: number | null
  lastResult: 'ok' | 'error' | null
  lastError: string | null
  mirrored: number[]
  rejected: string[]
}

/** GET /admin/releases/settings；githubToken 为脱敏值或 null */
export interface ReleaseSettingsView {
  repo: string
  githubToken: string | null
  autoPublish: boolean
  pollMinutes: number
  expectedSigningCertSha256: string | null
  updatedAt: number | null
}

/** PUT /admin/releases/settings：githubToken 缺省 = 保持，"" = 清除 */
export interface ReleaseSettingsUpdate {
  repo?: string
  githubToken?: string
  autoPublish?: boolean
  pollMinutes?: number
  expectedSigningCertSha256?: string
}

/** server-updater 上报的状态（原样转发，字段可能缺失） */
export interface UpdaterReport {
  channel?: string
  enabled?: boolean
  current?: Record<string, string | null>
  lastCheck?: number | null
  lastResult?: string | null
  lastError?: string | null
  [k: string]: unknown
}

/** GET /admin/server/update-status */
export interface ServerUpdateStatus {
  backend: { version: string; buildVersion: string; revision: string | null }
  updater: UpdaterReport | null
  updaterReportedAt: number | null
}
