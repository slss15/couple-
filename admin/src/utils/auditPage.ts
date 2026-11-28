import type { AuditEntry } from '@/types/api'

/**
 * 审计日志的游标分页（前端侧实现）。
 *
 * 后端契约：GET /admin/audit?limit&before，SQL 是 `ts < before`（严格小于），排序 (ts DESC, id DESC)，
 * 并且**没有** beforeId 参数。因此不能只拿“上一页最后一条的 ts”当 before：与它同一毫秒、
 * 但 id 更小（排序更靠后）的记录不满足 ts < before，会在下一次请求里被整组排除——
 * 页边界正好切在同 ts 组里时，剩下的记录永远看不到。
 *
 * 现有接口能力下用 before = ts + 1ms 把整组同 ts 记录重新取回来，再用 skip 丢掉已经展示过的部分
 * （同 ts 内按 id DESC，ULID 字符串序与后端一致）。代价是每次多取 skip 条，换来“不重不漏”。
 * 等后端支持 beforeId 后，把这里换成直接透传 (ts, id) 即可，游标结构不用再改。
 */
export interface AuditCursor {
  /** 上一页最后一条的 ts（epoch 毫秒） */
  ts: number
  /** 上一页最后一条的 id：请求参数里暂未使用，保留它就是为了将来能直接透传 beforeId */
  id: string
  /** 该 ts 上已经展示过的条数，下一次请求跳过这部分 */
  skip: number
}

export interface AuditPageResult {
  rows: AuditEntry[]
  hasNext: boolean
  next: AuditCursor | null
}

/**
 * 后端 limit 上限：AdminRoutes.kt 里 `limit.coerceIn(1, 200)`。
 * skip 只有在“同一毫秒写了 200 条以上审计”时才会顶到上限；审计是每次请求写一条，实际不会发生。
 */
const MAX_LIMIT = 200

/** 组装请求参数：before 覆盖整组同 ts，limit 多取 1 条用于判断下一页，再补上会被重复返回的旧记录 */
export function auditRequest(cursor: AuditCursor | null, pageSize: number): { limit: number; before?: number } {
  const skip = cursor?.skip ?? 0
  const params: { limit: number; before?: number } = { limit: Math.min(pageSize + 1 + skip, MAX_LIMIT) }
  if (cursor) params.before = cursor.ts + 1
  return params
}

/** 从一页响应里切出真正的新记录；响应按 (ts DESC, id DESC)，前 skip 条正好是已经展示过的 */
export function readAuditPage(data: AuditEntry[], cursor: AuditCursor | null, pageSize: number): AuditPageResult {
  const skip = cursor?.skip ?? 0
  const fresh = data.slice(skip)
  const hasNext = fresh.length > pageSize
  const rows = fresh.slice(0, pageSize)
  const last = rows[rows.length - 1]
  return { rows, hasNext, next: hasNext && last ? nextAuditCursor(rows, last, cursor) : null }
}

/** 下一页游标：skip = 该 ts 上已展示过的条数（本页同 ts 的 + 之前各页在该 ts 上跳过的） */
export function nextAuditCursor(rows: AuditEntry[], last: AuditEntry, prev: AuditCursor | null): AuditCursor {
  const sameTs = rows.filter((row) => row.ts === last.ts).length
  const carried = prev && prev.ts === last.ts ? prev.skip : 0
  return { ts: last.ts, id: last.id, skip: sameTs + carried }
}
