import type { ReleaseSettingsUpdate, ReleaseSettingsView, ReleaseStatus, UpdaterReport } from '@/types/api'
import type { SecretField } from './configForm'

export const STATUS_LABEL: Record<ReleaseStatus, string> = {
  MIRRORED: '已镜像（未发布）',
  PUBLISHED: '已发布',
  WITHDRAWN: '已撤回',
}

export const STATUS_TAG: Record<ReleaseStatus, 'info' | 'success' | 'danger'> = {
  MIRRORED: 'info',
  PUBLISHED: 'success',
  WITHDRAWN: 'danger',
}

/** 字节数 → 人类可读（1024 进制） */
export function formatSize(bytes: number | null | undefined): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let v = bytes / 1024
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(v >= 100 ? 0 : 1)} ${units[i]}`
}

/** 长哈希/镜像 digest 缩写显示：sha256:abcdef… → abcdef123456 */
export function shortDigest(s: string | null | undefined, n = 12): string {
  if (!s) return '—'
  const v = s.includes(':') && !/^([0-9a-f]{2}:)+/i.test(s) ? s.slice(s.indexOf(':') + 1) : s
  return v.length > n ? `${v.slice(0, n)}…` : v
}

/** 证书指纹规范化：去冒号/空格转小写；不是 64 位十六进制返回 null */
export function normalizeCertSha256(s: string): string | null {
  const v = s.trim().toLowerCase().replace(/[:\s]/g, '')
  return /^[0-9a-f]{64}$/.test(v) ? v : null
}

export function isRepo(s: string): boolean {
  return /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(s.trim())
}

export function isValidPollMinutes(n: number): boolean {
  return Number.isInteger(n) && (n === 0 || (n >= 5 && n <= 1440))
}

/** 设备是否强制更新：当前版本 < 最新已发布版本的 minSupportedVersionCode（与后端规则一致，仅用于提示） */
export function isForced(current: number, minSupported: number): boolean {
  return current < minSupported
}

export interface ReleaseSettingsForm {
  repo: string
  token: SecretField
  autoPublish: boolean
  pollMinutes: number
  certPin: string
}

export function releaseSettingsForm(v: ReleaseSettingsView | null): ReleaseSettingsForm {
  return {
    repo: v?.repo ?? 'qimingnan17/couple-',
    token: { masked: v?.githubToken || null, value: '', clear: false },
    autoPublish: v?.autoPublish ?? false,
    pollMinutes: v?.pollMinutes ?? 30,
    certPin: v?.expectedSigningCertSha256 ?? '',
  }
}

/**
 * 表单 → PUT 请求体。令牌只写：未输入不发送（不回传脱敏值），勾选清除发送 ""。
 * 证书固定：留空发送 ""（清除，下一次镜像会重新固定），否则发送规范化后的值。
 */
export function releaseSettingsPayload(f: ReleaseSettingsForm): ReleaseSettingsUpdate {
  const out: ReleaseSettingsUpdate = {
    repo: f.repo.trim(),
    autoPublish: f.autoPublish,
    pollMinutes: f.pollMinutes,
    expectedSigningCertSha256: f.certPin.trim() ? (normalizeCertSha256(f.certPin) ?? f.certPin.trim()) : '',
  }
  if (f.token.clear) out.githubToken = ''
  else if (f.token.value.trim()) out.githubToken = f.token.value.trim()
  return out
}

/** 仪表盘：updater 最近结果的展示类型 */
export function updaterResultTag(r: UpdaterReport | null): 'success' | 'danger' | 'warning' | 'info' {
  if (!r) return 'info'
  if (r.lastError) return 'danger'
  const res = (r.lastResult ?? '').toLowerCase()
  if (!res) return 'info'
  if (/(fail|error|rollback|rolled)/.test(res)) return 'danger'
  if (/(skip|disabled|window)/.test(res)) return 'warning'
  return 'success'
}
