import { describe, expect, it } from 'vitest'
import {
  formatSize,
  isForced,
  isRepo,
  isValidPollMinutes,
  normalizeCertSha256,
  releaseSettingsForm,
  releaseSettingsPayload,
  shortDigest,
  updaterResultTag,
} from '@/utils/releases'
import type { ReleaseSettingsView } from '@/types/api'

const HEX = 'ab'.repeat(32)

const view: ReleaseSettingsView = {
  repo: 'slss15/couple-',
  githubToken: '****1234',
  autoPublish: false,
  pollMinutes: 30,
  expectedSigningCertSha256: HEX,
  canaryDeviceIds: ['dev-a', 'dev-b'],
  updatedAt: 1,
}

describe('版本发布设置表单 → 请求体', () => {
  it('未修改的令牌不发送，不会回传脱敏值；canary 设备原样提交', () => {
    const p = releaseSettingsPayload(releaseSettingsForm(view))
    expect('githubToken' in p).toBe(false)
    expect(JSON.stringify(p)).not.toContain('****')
    expect(p).toEqual({
      repo: 'slss15/couple-',
      autoPublish: false,
      pollMinutes: 30,
      expectedSigningCertSha256: HEX,
      canaryDeviceIds: ['dev-a', 'dev-b'],
    })
  })

  it('新令牌覆盖、勾选清除发送空串；证书指纹规范化，留空清除固定', () => {
    const f = releaseSettingsForm(view)
    f.token.value = '  ghp_new  '
    f.certPin = HEX.toUpperCase().match(/.{2}/g)!.join(':')
    expect(releaseSettingsPayload(f).githubToken).toBe('ghp_new')
    expect(releaseSettingsPayload(f).expectedSigningCertSha256).toBe(HEX)
    f.token.clear = true
    f.certPin = ''
    expect(releaseSettingsPayload(f).githubToken).toBe('')
    expect(releaseSettingsPayload(f).expectedSigningCertSha256).toBe('')
  })

  it('canary 设备 ID 逐项 trim、过滤空串', () => {
    const f = releaseSettingsForm(view)
    f.canaryIds = [' dev-a ', '', 'dev-c']
    expect(releaseSettingsPayload(f).canaryDeviceIds).toEqual(['dev-a', 'dev-c'])
  })

  it('空视图给出默认值', () => {
    const f = releaseSettingsForm(null)
    expect(f.repo).toBe('slss15/couple-')
    expect(f.pollMinutes).toBe(30)
    expect(f.token.masked).toBeNull()
    expect(f.canaryIds).toEqual([])
  })
})

describe('校验与格式化', () => {
  it('仓库、轮询间隔、证书指纹', () => {
    expect(isRepo('a/b')).toBe(true)
    expect(isRepo('slss15/couple-')).toBe(true)
    expect(isRepo('nope')).toBe(false)
    expect(isRepo('a/b/c')).toBe(false)
    expect(isValidPollMinutes(0)).toBe(true)
    expect(isValidPollMinutes(4)).toBe(false)
    expect(isValidPollMinutes(30)).toBe(true)
    expect(isValidPollMinutes(1441)).toBe(false)
    expect(normalizeCertSha256(` ${HEX.toUpperCase()} `)).toBe(HEX)
    expect(normalizeCertSha256('xyz')).toBeNull()
  })

  it('大小与摘要', () => {
    expect(formatSize(512)).toBe('512 B')
    expect(formatSize(1536)).toBe('1.5 KB')
    expect(formatSize(25 * 1024 * 1024)).toBe('25.0 MB')
    expect(formatSize(null)).toBe('—')
    expect(shortDigest('sha256:0123456789abcdef', 6)).toBe('012345…')
    expect(shortDigest('abc')).toBe('abc')
    expect(shortDigest(null)).toBe('—')
  })

  it('强制更新规则与 updater 结果标签', () => {
    expect(isForced(100, 101)).toBe(true)
    expect(isForced(101, 101)).toBe(false)
    expect(updaterResultTag(null)).toBe('info')
    expect(updaterResultTag({ lastResult: 'deployed backend', lastError: null })).toBe('success')
    expect(updaterResultTag({ lastResult: 'rolled back', lastError: null })).toBe('danger')
    expect(updaterResultTag({ lastResult: 'ok', lastError: 'boom' })).toBe('danger')
    expect(updaterResultTag({ lastResult: 'skipped (outside window)' })).toBe('warning')
  })
})
