import type {
  AdminConfig,
  AdminConfigView,
  AuditEntry,
  BootstrapPubkey,
  BootstrapResponse,
  Couple,
  HealthInfo,
  TokenPair,
} from '@/types/api'
import { createHttp } from './http'
import { tokenStorage } from './tokenStorage'

let authFailureHandler: () => void = () => {}
export function setAuthFailureHandler(fn: () => void) {
  authFailureHandler = fn
}

export const http = createHttp({ onAuthFailure: () => authFailureHandler() })

function deviceName(): string {
  const ua = typeof navigator !== 'undefined' ? navigator.userAgent : ''
  const browser = /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome' : /Firefox\//.test(ua) ? 'Firefox' : /Safari\//.test(ua) ? 'Safari' : '浏览器'
  return `Web 管理后台 (${browser})`
}

export const api = {
  health: () => http.get<HealthInfo>('/health').then((r) => r.data),
  pubkey: () => http.get<BootstrapPubkey>('/bootstrap/pubkey').then((r) => r.data),

  login: (username: string, password: string) =>
    http
      .post<TokenPair>('/auth/login', {
        username,
        password,
        deviceId: tokenStorage.deviceId(),
        deviceName: deviceName(),
      })
      .then((r) => r.data),
  logout: (refreshToken: string) => http.post<void>('/auth/logout', { refreshToken }).then(() => undefined),

  couple: () => http.get<Couple>('/couple').then((r) => r.data),

  getConfig: () => http.get<AdminConfigView>('/admin/config').then((r) => r.data),
  putConfig: (cfg: AdminConfig) => http.put<AdminConfigView>('/admin/config', cfg).then((r) => r.data),
  bootstrap: () => http.post<BootstrapResponse>('/admin/bootstrap').then((r) => r.data),
  audit: (params: { limit: number; before?: number }) =>
    http.get<AuditEntry[]>('/admin/audit', { params }).then((r) => r.data),
}
