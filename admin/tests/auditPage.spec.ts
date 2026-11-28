import { describe, expect, it } from 'vitest'
import type { AuditEntry } from '@/types/api'
import { auditRequest, readAuditPage, type AuditCursor } from '@/utils/auditPage'

/** 与后端一致：ts 严格小于 before，排序 (ts DESC, id DESC)，limit 截断 */
function fakeBackend(entries: AuditEntry[]) {
  const sorted = [...entries].sort(byTsThenIdDesc)
  return (params: { limit: number; before?: number }) => {
    const before = params.before
    const rows = before === undefined ? sorted : sorted.filter((row) => row.ts < before)
    return rows.slice(0, params.limit)
  }
}

function byTsThenIdDesc(a: AuditEntry, b: AuditEntry): number {
  return b.ts - a.ts || (a.id < b.id ? 1 : a.id > b.id ? -1 : 0)
}

/** ULID 风格的定长 id：字符串序与后端 id 序一致 */
function ulid(n: number): string {
  return `01J${String(n).padStart(23, '0')}`
}

function entry(ts: number, n: number): AuditEntry {
  return { id: ulid(n), actorId: null, action: 'admin.test', target: null, meta: null, ts }
}

/** 像页面一样一页页翻到底，返回实际展示过的 id 顺序 */
function collect(fetchPage: (params: { limit: number; before?: number }) => AuditEntry[], pageSize: number): string[] {
  const seen: string[] = []
  let cursor: AuditCursor | null = null
  for (let guard = 0; guard < 100; guard++) {
    const result = readAuditPage(fetchPage(auditRequest(cursor, pageSize)), cursor, pageSize)
    seen.push(...result.rows.map((row) => row.id))
    if (!result.hasNext) return seen
    cursor = result.next
  }
  throw new Error('翻页没有收敛：游标没有前进')
}

describe('审计日志游标分页', () => {
  it('页边界切开同一毫秒的记录时不重不漏', () => {
    const entries = [
      ...Array.from({ length: 5 }, (_, i) => entry(5_000, i)),
      ...Array.from({ length: 3 }, (_, i) => entry(4_000, 10 + i)),
      ...Array.from({ length: 7 }, (_, i) => entry(3_000, 20 + i)),
      ...Array.from({ length: 2 }, (_, i) => entry(2_000, 30 + i)),
      ...Array.from({ length: 13 }, (_, i) => entry(1_000, 40 + i)),
    ]
    const expected = [...entries].sort(byTsThenIdDesc).map((row) => row.id)
    for (const pageSize of [1, 2, 3, 4, 5, 7, 20, 100]) {
      expect(collect(fakeBackend(entries), pageSize)).toEqual(expected)
    }
  })

  it('整页都是同一毫秒时也能翻到底，不会重复翻同一页', () => {
    const entries = Array.from({ length: 25 }, (_, i) => entry(7_000, i))
    const expected = [...entries].sort(byTsThenIdDesc).map((row) => row.id)
    for (const pageSize of [5, 10, 20]) {
      expect(collect(fakeBackend(entries), pageSize)).toEqual(expected)
    }
  })

  it('游标请求用 ts+1ms 覆盖整组同 ts，并带上已展示条数', () => {
    expect(auditRequest(null, 20)).toEqual({ limit: 21 })
    expect(auditRequest({ ts: 100, id: ulid(1), skip: 3 }, 20)).toEqual({ limit: 24, before: 101 })
  })

  it('请求 limit 不超过后端上限 200', () => {
    expect(auditRequest({ ts: 100, id: ulid(1), skip: 150 }, 100).limit).toBe(200)
  })
})
