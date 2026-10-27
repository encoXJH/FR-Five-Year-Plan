/** 审计日志变更前后 JSON 差异计算（P13；纯函数，便于单测） */

export type DiffChangeType = 'added' | 'removed' | 'changed'

export interface DiffEntry {
  /** 字段路径（嵌套用点号，如 yearTargets.2026） */
  field: string
  before: unknown
  after: unknown
  changeType: DiffChangeType
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * 递归展开为「叶子路径 → 值」。空对象 / 空数组本身视为叶子值。
 * 数组不展开下标，整体作为叶子值比较（JSON 字符串比较）。
 */
export function flattenObject(obj: Record<string, unknown>, prefix = ''): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const key of Object.keys(obj)) {
    const path = prefix ? `${prefix}.${key}` : key
    const value = obj[key]
    if (isPlainObject(value) && Object.keys(value).length > 0) {
      Object.assign(out, flattenObject(value, path))
    } else {
      out[path] = value
    }
  }
  return out
}

function isEqual(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true
  // 数组 / 对象引用不同则按 JSON 字符串比较
  if (typeof a === 'object' && a !== null && typeof b === 'object' && b !== null) {
    return JSON.stringify(a) === JSON.stringify(b)
  }
  return false
}

/**
 * 计算 before → after 的字段级差异列表。
 * added：after 新增；removed：after 删除；changed：两边都有但值不同。
 * 入参为 null 时按空对象处理。
 */
export function diffObjects(
  before: Record<string, unknown> | null,
  after: Record<string, unknown> | null,
): DiffEntry[] {
  const flatBefore = flattenObject(before ?? {})
  const flatAfter = flattenObject(after ?? {})
  const entries: DiffEntry[] = []
  for (const field of Object.keys(flatBefore)) {
    if (!(field in flatAfter)) {
      entries.push({ field, before: flatBefore[field], after: undefined, changeType: 'removed' })
    } else if (!isEqual(flatBefore[field], flatAfter[field])) {
      entries.push({ field, before: flatBefore[field], after: flatAfter[field], changeType: 'changed' })
    }
  }
  for (const field of Object.keys(flatAfter)) {
    if (!(field in flatBefore)) {
      entries.push({ field, before: undefined, after: flatAfter[field], changeType: 'added' })
    }
  }
  return entries
}

/** 差异行内单值展示：undefined → 「（空）」，对象 / 数组 → 紧凑 JSON，其余 → 字符串 */
export function formatDiffValue(value: unknown): string {
  if (value === undefined) return '（空）'
  if (value === null) return 'null'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

/** JSON 美化展示；null / undefined → 空串（调用方显示占位符） */
export function prettyJson(value: unknown): string {
  if (value === null || value === undefined) return ''
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/** 审计日志 before / after 是 JSON 字符串：解析为对象，解析失败返回 null */
export function parseSnapshot(raw: string | null | undefined): Record<string, unknown> | null {
  if (!raw) return null
  try {
    const parsed: unknown = JSON.parse(raw)
    return isPlainObject(parsed) ? parsed : { value: parsed }
  } catch {
    return null
  }
}

/** JSON 字符串美化展示；非法 JSON 原样返回，空 → 空串 */
export function prettyJsonString(raw: string | null | undefined): string {
  if (!raw) return ''
  const parsed = parseSnapshot(raw)
  return parsed === null ? raw : prettyJson(parsed)
}
