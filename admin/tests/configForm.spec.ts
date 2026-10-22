import { describe, expect, it } from 'vitest'
import { fromConfig, toPayload } from '@/utils/configForm'
import { parseBootstrapUri } from '@/utils/bootstrap'
import type { AdminConfig } from '@/types/api'

const server: AdminConfig = {
  baseUrl: 'https://c.example.com',
  mqttUrl: 'wss://c.example.com/mqtt',
  turn: { provider: 'cloudflare', cloudflare: { keyId: 'k1', apiToken: '****abcd' } },
  storage: { provider: 'r2', bucket: 'b', secretAccessKey: '****wxyz' },
}

describe('配置表单 → 请求体', () => {
  it('未修改的密钥不会出现在请求体（不会把脱敏值回传）', () => {
    const p = toPayload(fromConfig(server))
    expect(p.turn.cloudflare).toEqual({ keyId: 'k1' })
    expect('secretAccessKey' in p.storage).toBe(false)
    expect(JSON.stringify(p)).not.toContain('****')
  })

  it('新输入覆盖，勾选清除发送空字符串', () => {
    const f = fromConfig(server)
    f.cfApiToken.value = ' new-token '
    f.storageSecret.clear = true
    f.baseUrl = 'https://c.example.com///'
    const p = toPayload(f)
    expect(p.turn.cloudflare?.apiToken).toBe('new-token')
    expect(p.storage.secretAccessKey).toBe('')
    expect(p.baseUrl).toBe('https://c.example.com')
  })

  it('coturn URL 按行/逗号拆分', () => {
    const f = fromConfig(server)
    f.turnProvider = 'coturn'
    f.coturnUrls = 'turn:a:3478\n turns:a:5349 ,\n'
    expect(toPayload(f).turn.coturn?.urls).toEqual(['turn:a:3478', 'turns:a:5349'])
  })
})

describe('引导 URI 解析', () => {
  it('解码 d 参数', () => {
    const json = { v: 1, baseUrl: 'https://c.example.com', mqttUrl: 'wss://c.example.com/mqtt', configVersion: 3, issuedAt: 1 }
    const d = btoa(JSON.stringify(json)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
    const r = parseBootstrapUri(`couple://bootstrap?d=${d}&s=AAAA`)
    expect(r.payload).toEqual(json)
    expect(() => parseBootstrapUri('https://x')).toThrow()
  })
})
