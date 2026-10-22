import type { BootstrapPayload } from '@/types/api'
import { base64ToBytes, base64UrlDecodeUtf8, base64UrlToBytes } from './base64url'

export interface ParsedBootstrapUri {
  d: string
  s: string
  payload: BootstrapPayload
}

/** 解析 couple://bootstrap?d=<base64url(json)>&s=<base64url(sig)> */
export function parseBootstrapUri(uri: string): ParsedBootstrapUri {
  const m = /^couple:\/\/bootstrap\?(.*)$/.exec(uri.trim())
  if (!m) throw new Error('URI 不是 couple://bootstrap 格式')
  const q = new URLSearchParams(m[1])
  const d = q.get('d')
  const s = q.get('s')
  if (!d || !s) throw new Error('URI 缺少 d 或 s 参数')
  const payload = JSON.parse(base64UrlDecodeUtf8(d)) as BootstrapPayload
  return { d, s, payload }
}

export type VerifyResult = 'ok' | 'bad' | 'unsupported'

/**
 * 用服务器公钥校验签名（假设签名对象为 d 解码后的原始 JSON 字节）。
 * 浏览器不支持 Ed25519 WebCrypto 时返回 unsupported。
 */
export async function verifyBootstrapSignature(d: string, s: string, publicKeyB64: string): Promise<VerifyResult> {
  const subtle = globalThis.crypto?.subtle
  if (!subtle) return 'unsupported'
  try {
    const raw = base64ToBytes(publicKeyB64)
    // 兼容 32 字节裸公钥和 44 字节 SPKI(DER)
    const key =
      raw.length === 32
        ? await subtle.importKey('raw', raw as BufferSource, { name: 'Ed25519' }, false, ['verify'])
        : await subtle.importKey('spki', raw as BufferSource, { name: 'Ed25519' }, false, ['verify'])
    const ok = await subtle.verify(
      { name: 'Ed25519' },
      key,
      base64UrlToBytes(s) as BufferSource,
      base64UrlToBytes(d) as BufferSource,
    )
    return ok ? 'ok' : 'bad'
  } catch (e) {
    if (e instanceof DOMException && e.name === 'NotSupportedError') return 'unsupported'
    return 'bad'
  }
}
