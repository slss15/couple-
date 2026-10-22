// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { webcrypto } from 'node:crypto'
import QRCode from 'qrcode'
import { parseBootstrapUri, verifyBootstrapSignature } from '@/utils/bootstrap'

const b64url = (b: Uint8Array) => Buffer.from(b).toString('base64url')

describe('引导二维码签名校验（与后端格式一致）', () => {
  it('用 32 字节裸公钥校验 d 原始字节上的 Ed25519 签名', async () => {
    const kp = (await webcrypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify'])) as CryptoKeyPair
    const rawPub = new Uint8Array(await webcrypto.subtle.exportKey('raw', kp.publicKey))
    const bytes = new TextEncoder().encode(
      JSON.stringify({ v: 1, baseUrl: 'https://c.example.com', mqttUrl: 'wss://c.example.com/mqtt', configVersion: 2, issuedAt: 1 }),
    )
    const sig = new Uint8Array(await webcrypto.subtle.sign({ name: 'Ed25519' }, kp.privateKey, bytes))
    const uri = `couple://bootstrap?d=${b64url(bytes)}&s=${b64url(sig)}`
    const { d, s, payload } = parseBootstrapUri(uri)
    expect(payload.configVersion).toBe(2)
    const pubB64 = Buffer.from(rawPub).toString('base64') // 后端用标准 base64
    expect(await verifyBootstrapSignature(d, s, pubB64)).toBe('ok')

    const tampered = b64url(new TextEncoder().encode('{"v":1,"baseUrl":"https://evil"}'))
    expect(await verifyBootstrapSignature(tampered, s, pubB64)).toBe('bad')

    // 二维码可以正常编码该长度的 URI
    const png = await QRCode.toDataURL(uri, { errorCorrectionLevel: 'M', width: 256 })
    expect(png.startsWith('data:image/png;base64,')).toBe(true)
  })
})
