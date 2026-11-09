import type { AdjustmentItem, AdjustmentSnapshotRow } from '@/types/adjustment'
import type { AdjustmentStatus, AdjustmentType, StatCaliber, TaskStatus } from '@/types/enums'

/** 调整类型 → 适用统计口径（API 第 9 章，违反返回 70006；TARGET_CHANGE 全部口径但仅管理员） */
export const ADJUSTMENT_TYPE_CALIBERS: Record<AdjustmentType, StatCaliber[]> = {
  POSTPONE: ['INCREMENT', 'BOOLEAN'],
  TRANSFER: ['INCREMENT'],
  POSTPONE_TRANSFER: ['INCREMENT'],
  TARGET_CHANGE: ['INCREMENT', 'STOCK', 'GROWTH', 'RATIO', 'DIRECT_PERCENT', 'BOOLEAN'],
}

export interface AdjustmentTypeOption {
  type: AdjustmentType
  enabled: boolean
  /** 置灰原因（前端引导，服务端 70006 兜底） */
  reason: string | null
}

const TYPE_ORDER: AdjustmentType[] = ['POSTPONE', 'TRANSFER', 'POSTPONE_TRANSFER', 'TARGET_CHANGE']

/** 按口径与角色过滤可选调整类型（未选任务时口径传 null，仅目标调整可按管理员放开） */
export function adjustmentTypeOptions(
  caliber: StatCaliber | null,
  isAdmin: boolean,
): AdjustmentTypeOption[] {
  return TYPE_ORDER.map((type) => {
    if (type === 'TARGET_CHANGE') {
      return isAdmin
        ? { type, enabled: true, reason: null }
        : { type, enabled: false, reason: '仅平台管理员可发起目标调整' }
    }
    if (caliber === null) {
      return { type, enabled: false, reason: '请先选择学院任务' }
    }
    const enabled = ADJUSTMENT_TYPE_CALIBERS[type].includes(caliber)
    return {
      type,
      enabled,
      reason: enabled ? null : '该统计口径不支持此调整类型（70006）',
    }
  })
}

/** 勾选任务后即时展示可调整类型，服务端仍按 70006 复核。 */
export function adjustmentTypeHint(caliber: StatCaliber | null, isAdmin: boolean): string {
  if (caliber === null) return '请先勾选学院任务，系统将根据统计口径提示可调整类型'
  if (caliber === 'INCREMENT') {
    return `可发起：延期、转移、延期 + 转移${isAdmin ? '、目标调整' : ''}`
  }
  if (caliber === 'BOOLEAN') {
    return `仅可延期（修改计划完成年份）${isAdmin ? '；管理员也可发起目标调整' : ''}`
  }
  return `不支持延期或转移；${isAdmin ? '可发起目标调整' : '如需变更请联系管理员发起目标调整'}（70006）`
}

/** 是否涉及接收单元（转移类） */
export function isTransferType(type: AdjustmentType): boolean {
  return type === 'TRANSFER' || type === 'POSTPONE_TRANSFER'
}

/** B 任务是否可从工作台发起调整入口（任务级延期 / 转移仅 INCREMENT / BOOLEAN，且任务未终结） */
export function canApplyForTask(
  status: TaskStatus,
  caliber: StatCaliber | null | undefined,
  isHistorical: boolean,
): boolean {
  if (isHistorical || status === 'TRANSFERRED_OUT' || status === 'CLOSED') return false
  return caliber === 'INCREMENT' || caliber === 'BOOLEAN'
}

/**
 * 目标年份前端校验（服务端 70003 兜底）：
 * 延期类必须晚于原年度；转移可同年但不能更早；均不得超出规划末年
 */
export function checkTargetYear(
  type: AdjustmentType,
  fromYear: number,
  toYear: number,
  endYear: number,
): string | null {
  if (toYear > endYear) return `目标年份不能超出规划末年 ${endYear}（70003）`
  if (type === 'POSTPONE' || type === 'POSTPONE_TRANSFER') {
    if (toYear <= fromYear) return '延期的目标年份必须晚于原年度'
  } else if (type === 'TRANSFER' && toYear < fromYear) {
    return '目标年份不能早于原年度'
  }
  return null
}

/** 调整明细摘要，如「2026 年 1 项 → 2027 年」 */
export function summarizeItems(
  items: AdjustmentItem[] | null | undefined,
  unitLabel?: string | null,
): string {
  if (!items || items.length === 0) return '—'
  return items
    .map((item) => {
      // TARGET_CHANGE：year + newTarget（列表项以 toYear 承载被调整年度）
      if (item.newTarget != null) {
        const year = item.year ?? item.toYear
        return `${year ?? '—'} 年目标 → ${item.newTarget}`
      }
      const amount = typeof item.amount === 'number' ? ` ${item.amount}${unitLabel ?? ''}` : ''
      if (typeof item.toYear === 'number' && item.toYear !== item.fromYear) {
        return `${item.fromYear} 年${amount} → ${item.toYear} 年`
      }
      return `${item.fromYear} 年${amount}`
    })
    .join('；')
}

/** 快照按任务 / 指标分组（保持出现顺序），每组含调整前 / 后两套年度目标 */
export interface SnapshotGroup {
  /** 任务编号（移动类）或指标编号（目标调整） */
  taskCode: string
  /** 接收方原本没有该 A+ 的 B：before 无该编码行，after 才有（生效时自动新建） */
  isNew: boolean
  /** TARGET_CHANGE 使用指标编号而非任务编号 */
  isIndicator: boolean
  before: Record<number, number | null>
  after: Record<number, number | null>
  /** TARGET_CHANGE 的五年总量条目（快照行 year=null） */
  totalBefore?: number | null
  totalAfter?: number | null
}

export function groupSnapshot(
  before: AdjustmentSnapshotRow[] | null | undefined,
  after: AdjustmentSnapshotRow[] | null | undefined,
): SnapshotGroup[] {
  const groups = new Map<string, SnapshotGroup>()
  const keyOf = (row: AdjustmentSnapshotRow): string => row.taskCode ?? row.indicatorCode ?? ''
  const ensure = (row: AdjustmentSnapshotRow): SnapshotGroup => {
    const key = keyOf(row)
    let group = groups.get(key)
    if (!group) {
      group = { taskCode: key, isNew: false, isIndicator: false, before: {}, after: {} }
      groups.set(key, group)
    }
    group.isIndicator = group.isIndicator || row.indicatorCode != null
    return group
  }
  for (const row of before ?? []) {
    const group = ensure(row)
    if (row.year == null) group.totalBefore = row.target
    else group.before[row.year] = row.target
  }
  for (const row of after ?? []) {
    const group = ensure(row)
    if (row.year == null) group.totalAfter = row.target
    else group.after[row.year] = row.target
  }
  for (const group of groups.values()) {
    group.isNew = Object.keys(group.before).length === 0 && group.totalBefore === undefined
  }
  return [...groups.values()]
}

export interface CellDiff {
  changed: boolean
  increased: boolean
  decreased: boolean
}

/** 单个年度单元格的前后差异（UI 规范 10.2：减少 ↓ / 增加 ↑） */
export function diffCell(
  before: number | boolean | null | undefined,
  after: number | boolean | null | undefined,
): CellDiff {
  const b = before ?? null
  const a = after ?? null
  if (b === a) return { changed: false, increased: false, decreased: false }
  if (typeof b === 'number' && typeof a === 'number') {
    return { changed: true, increased: a > b, decreased: a < b }
  }
  return { changed: true, increased: false, decreased: false }
}

/** 某组年度目标的五年合计（仅数值参与；无数值返回 null） */
export function groupTotal(values: Record<number, number | boolean | null>): number | null {
  let sum = 0
  let has = false
  for (const value of Object.values(values)) {
    if (typeof value === 'number') {
      sum += value
      has = true
    }
  }
  return has ? sum : null
}

export interface ConservationLine {
  taskCode: string
  before: number | null
  after: number | null
  balanced: boolean
  combined: boolean
}

function indicatorCodeOfTask(taskCode: string): string {
  const separator = taskCode.lastIndexOf('-')
  return separator > 0 ? taskCode.slice(0, separator) : taskCode
}

function snapshotTotal(group: SnapshotGroup, phase: 'before' | 'after'): number | null {
  if (phase === 'before') return group.totalBefore ?? groupTotal(group.before)
  return group.totalAfter ?? groupTotal(group.after)
}

function combinedTotal(groups: SnapshotGroup[], phase: 'before' | 'after'): number | null {
  const totals = groups.map((group) => snapshotTotal(group, phase))
  return totals.some((total) => total !== null)
    ? totals.reduce<number>((sum, total) => sum + (total ?? 0), 0)
    : null
}

function conservationLine(
  taskCode: string,
  before: number | null,
  after: number | null,
  combined: boolean,
): ConservationLine {
  return {
    taskCode,
    before,
    after,
    balanced: before !== null && after !== null && Math.abs(before - after) < 1e-9,
    combined,
  }
}

/** 延期按单任务守恒；转移按同一指标的转出与接收任务合计守恒。 */
export function buildConservationLines(
  groups: SnapshotGroup[],
  type?: AdjustmentType,
): ConservationLine[] {
  if (type === 'TARGET_CHANGE' || (type === undefined && groups.length > 0 && groups.every((group) => group.isIndicator))) {
    return []
  }
  const inferredTransfer = type === undefined && (
    groups.some((group) => group.isNew)
    || new Set(groups.map((group) => indicatorCodeOfTask(group.taskCode))).size < groups.length
  )
  if ((type !== undefined && isTransferType(type)) || inferredTransfer) {
    const byIndicator = new Map<string, SnapshotGroup[]>()
    for (const group of groups) {
      const key = indicatorCodeOfTask(group.taskCode)
      byIndicator.set(key, [...(byIndicator.get(key) ?? []), group])
    }
    return [...byIndicator.entries()].map(([indicatorCode, related]) =>
      conservationLine(indicatorCode, combinedTotal(related, 'before'), combinedTotal(related, 'after'), true),
    )
  }
  return groups.map((group) => conservationLine(
    group.taskCode,
    snapshotTotal(group, 'before'),
    snapshotTotal(group, 'after'),
    false,
  ))
}

/** 审批中（未生效、未终结）的状态 */
export function isPendingStatus(status: AdjustmentStatus): boolean {
  return (
    status === 'PENDING_OWNER_REVIEW' ||
    status === 'PENDING_RECEIVER_CONFIRM' ||
    status === 'PENDING_LEADER_APPROVAL'
  )
}

/** 撤回入口：仅申请人本人且申请未生效（API 15.6） */
export function canWithdraw(
  status: AdjustmentStatus,
  applicantId: string | null | undefined,
  currentUserId: string | null | undefined,
): boolean {
  return isPendingStatus(status) && !!applicantId && !!currentUserId && applicantId === currentUserId
}

/** 审批中状态 → 当前环节（currentNode 缺省时按状态推断） */
export function nodeOfStatus(status: AdjustmentStatus): import('@/types/enums').ApprovalNode | null {
  switch (status) {
    case 'PENDING_OWNER_REVIEW':
      return 'OWNER_REVIEW'
    case 'PENDING_RECEIVER_CONFIRM':
      return 'RECEIVER_CONFIRM'
    case 'PENDING_LEADER_APPROVAL':
      return 'LEADER_APPROVAL'
    default:
      return null
  }
}
