export function base64UrlToBytes(input: string): Uint8Array {
  let s = input.replace(/-/g, '+').replace(/_/g, '/')
  const pad = s.length % 4
  if (pad === 2) s += '=='
  else if (pad === 3) s += '='
  else if (pad === 1) throw new Error('非法的 base64url 字符串')
  const bin = atob(s)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

export function base64ToBytes(input: string): Uint8Array {
  return base64UrlToBytes(input.replace(/=+$/, ''))
}

export function base64UrlDecodeUtf8(input: string): string {
  return new TextDecoder().decode(base64UrlToBytes(input))
}
