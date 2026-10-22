import type { AdminConfig, AdminConfigView, StorageProvider, TurnProvider } from '@/types/api'

export interface SecretField {
  /** 服务器返回的脱敏值，null = 未设置 */
  masked: string | null
  /** 用户新输入的值；空 = 不修改 */
  value: string
  /** 勾选后提交 ""，清除服务器上的值 */
  clear: boolean
}

export interface ConfigForm {
  baseUrl: string
  mqttUrl: string
  turnProvider: TurnProvider
  cfKeyId: string
  cfApiToken: SecretField
  coturnUrls: string
  coturnSecret: SecretField
  coturnTtl: number
  storageProvider: StorageProvider
  storageEndpoint: string
  storageBucket: string
  storageRegion: string
  storagePublicBaseUrl: string
  storageAccessKeyId: string
  storageSecret: SecretField
}

const secret = (masked: string | null | undefined): SecretField => ({ masked: masked || null, value: '', clear: false })

export function emptyForm(): ConfigForm {
  return fromConfig(null)
}

export function fromConfig(c: AdminConfig | AdminConfigView | null): ConfigForm {
  return {
    baseUrl: c?.baseUrl ?? '',
    mqttUrl: c?.mqttUrl ?? '',
    turnProvider: c?.turn?.provider ?? 'cloudflare',
    cfKeyId: c?.turn?.cloudflare?.keyId ?? '',
    cfApiToken: secret(c?.turn?.cloudflare?.apiToken),
    coturnUrls: (c?.turn?.coturn?.urls ?? []).join('\n'),
    coturnSecret: secret(c?.turn?.coturn?.sharedSecret),
    coturnTtl: c?.turn?.coturn?.ttlSeconds ?? 86400,
    storageProvider: c?.storage?.provider ?? 'local',
    storageEndpoint: c?.storage?.endpoint ?? '',
    storageBucket: c?.storage?.bucket ?? '',
    storageRegion: c?.storage?.region ?? '',
    storagePublicBaseUrl: c?.storage?.publicBaseUrl ?? '',
    storageAccessKeyId: c?.storage?.accessKeyId ?? '',
    storageSecret: secret(c?.storage?.secretAccessKey),
  }
}

/** 密钥只写：未修改则不出现在请求体中；清除则发送 "" */
function secretOut(f: SecretField): string | undefined {
  if (f.clear) return ''
  const v = f.value.trim()
  return v ? v : undefined
}

function withSecret<T extends object, K extends string>(obj: T, key: K, f: SecretField): T & Partial<Record<K, string>> {
  const v = secretOut(f)
  return v === undefined ? obj : { ...obj, [key]: v }
}

export function toPayload(f: ConfigForm): AdminConfig {
  const urls = f.coturnUrls
    .split(/[\n,]/)
    .map((s) => s.trim())
    .filter(Boolean)
  return {
    baseUrl: f.baseUrl.trim().replace(/\/+$/, ''),
    mqttUrl: f.mqttUrl.trim(),
    turn: {
      provider: f.turnProvider,
      cloudflare: withSecret({ keyId: f.cfKeyId.trim() }, 'apiToken', f.cfApiToken),
      coturn: withSecret({ urls, ttlSeconds: f.coturnTtl }, 'sharedSecret', f.coturnSecret),
    },
    storage: withSecret(
      {
        provider: f.storageProvider,
        endpoint: f.storageEndpoint.trim() || undefined,
        bucket: f.storageBucket.trim() || undefined,
        region: f.storageRegion.trim() || undefined,
        publicBaseUrl: f.storagePublicBaseUrl.trim() || undefined,
        accessKeyId: f.storageAccessKeyId.trim() || undefined,
      },
      'secretAccessKey',
      f.storageSecret,
    ),
  }
}

export function isHttpUrl(s: string): boolean {
  try {
    const u = new URL(s)
    return u.protocol === 'https:' || u.protocol === 'http:'
  } catch {
    return false
  }
}

export function isWssUrl(s: string): boolean {
  try {
    return new URL(s).protocol === 'wss:'
  } catch {
    return false
  }
}
