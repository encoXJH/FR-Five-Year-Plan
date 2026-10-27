import { describe, expect, it } from 'vitest'
import {
  adjustmentTypeOptions,
  canApplyForTask,
  canWithdraw,
  checkTargetYear,
  diffCell,
  groupSnapshot,
  groupTotal,
  isPendingStatus,
  isTransferType,
  nodeOfStatus,
  summarizeItems,
} from '../adjustment'
import type { AdjustmentSnapshotRow } from '@/types/adjustment'

describe('adjustmentTypeOptions', () => {
  it('INCREMENT 口径可选延期 / 转移 / 延期+转移', () => {
    const options = adjustmentTypeOptions('INCREMENT', false)
    const byType = Object.fromEntries(options.map((o) => [o.type, o]))
    expect(byType.POSTPONE.enabled).toBe(true)
    expect(byType.TRANSFER.enabled).toBe(true)
    expect(byType.POSTPONE_TRANSFER.enabled).toBe(true)
    expect(byType.TARGET_CHANGE.enabled).toBe(false)
  })

  it('BOOLEAN 只能延期；STOCK / RATIO 等不能延期转移', () => {
    const boolOptions = Object.fromEntries(
      adjustmentTypeOptions('BOOLEAN', false).map((o) => [o.type, o]),
    )
    expect(boolOptions.POSTPONE.enabled).toBe(true)
    expect(boolOptions.TRANSFER.enabled).toBe(false)
    expect(boolOptions.POSTPONE_TRANSFER.enabled).toBe(false)

    const stockOptions = Object.fromEntries(
      adjustmentTypeOptions('STOCK', false).map((o) => [o.type, o]),
    )
    expect(stockOptions.POSTPONE.enabled).toBe(false)
    expect(stockOptions.TRANSFER.enabled).toBe(false)
    expect(stockOptions.POSTPONE_TRANSFER.enabled).toBe(false)
  })

  it('TARGET_CHANGE 仅管理员可用；未选任务时任务级类型置灰', () => {
    const adminOptions = Object.fromEntries(
      adjustmentTypeOptions('RATIO', true).map((o) => [o.type, o]),
    )
    expect(adminOptions.TARGET_CHANGE.enabled).toBe(true)

    const noTask = Object.fromEntries(adjustmentTypeOptions(null, false).map((o) => [o.type, o]))
    expect(noTask.POSTPONE.enabled).toBe(false)
    expect(noTask.TARGET_CHANGE.enabled).toBe(false)
  })
})

describe('isTransferType / canApplyForTask', () => {
  it('转移类识别', () => {
    expect(isTransferType('TRANSFER')).toBe(true)
    expect(isTransferType('POSTPONE_TRANSFER')).toBe(true)
    expect(isTransferType('POSTPONE')).toBe(false)
    expect(isTransferType('TARGET_CHANGE')).toBe(false)
  })

  it('工作台调整入口：仅 INCREMENT / BOOLEAN 且任务未终结', () => {
    expect(canApplyForTask('IN_PROGRESS', 'INCREMENT', false)).toBe(true)
    expect(canApplyForTask('DISTRIBUTED', 'BOOLEAN', false)).toBe(true)
    expect(canApplyForTask('IN_PROGRESS', 'RATIO', false)).toBe(false)
    expect(canApplyForTask('TRANSFERRED_OUT', 'INCREMENT', false)).toBe(false)
    expect(canApplyForTask('CLOSED', 'INCREMENT', false)).toBe(false)
    expect(canApplyForTask('IN_PROGRESS', null, false)).toBe(false)
    expect(canApplyForTask('IN_PROGRESS', 'INCREMENT', true)).toBe(false)
  })
})

describe('checkTargetYear', () => {
  it('延期必须晚于原年度', () => {
    expect(checkTargetYear('POSTPONE', 2026, 2027, 2030)).toBeNull()
    expect(checkTargetYear('POSTPONE', 2026, 2026, 2030)).toContain('晚于原年度')
    expect(checkTargetYear('POSTPONE_TRANSFER', 2026, 2026, 2030)).toContain('晚于原年度')
  })

  it('转移可同年但不能更早', () => {
    expect(checkTargetYear('TRANSFER', 2026, 2026, 2030)).toBeNull()
    expect(checkTargetYear('TRANSFER', 2026, 2025, 2030)).toContain('不能早于原年度')
  })

  it('不得超出规划末年（70003）', () => {
    expect(checkTargetYear('POSTPONE', 2026, 2031, 2030)).toContain('70003')
  })
})

describe('summarizeItems', () => {
  it('跨年与同年明细摘要', () => {
    expect(summarizeItems([{ fromYear: 2026, toYear: 2027, amount: 1 }], '项')).toBe(
      '2026 年 1项 → 2027 年',
    )
    expect(summarizeItems([{ fromYear: 2026, toYear: 2026, amount: 2 }])).toBe('2026 年 2')
    expect(summarizeItems([{ fromYear: 2026, toYear: 2028 }])).toBe('2026 年 → 2028 年')
  })

  it('多条用分号连接；空为 —', () => {
    expect(
      summarizeItems([
        { fromYear: 2026, toYear: 2027, amount: 1 },
        { fromYear: 2027, toYear: 2028, amount: 2 },
      ]),
    ).toBe('2026 年 1 → 2027 年；2027 年 2 → 2028 年')
    expect(summarizeItems([])).toBe('—')
    expect(summarizeItems(null)).toBe('—')
  })
})

describe('groupSnapshot / groupTotal / diffCell', () => {
  const before: AdjustmentSnapshotRow[] = [
    { taskCode: 'A01-2-C03', year: 2026, target: 1 },
    { taskCode: 'A01-2-C03', year: 2027, target: 2 },
  ]
  const after: AdjustmentSnapshotRow[] = [
    { taskCode: 'A01-2-C03', year: 2026, target: 0 },
    { taskCode: 'A01-2-C03', year: 2027, target: 2 },
    { taskCode: 'A01-2-C05', year: 2027, target: 1 },
  ]

  it('按任务分组并保持顺序，接收方新建由 before 缺行推断', () => {
    const groups = groupSnapshot(before, after)
    expect(groups.map((g) => g.taskCode)).toEqual(['A01-2-C03', 'A01-2-C05'])
    expect(groups[0].before).toEqual({ 2026: 1, 2027: 2 })
    expect(groups[0].after).toEqual({ 2026: 0, 2027: 2 })
    expect(groups[0].isNew).toBe(false)
    expect(groups[1].isNew).toBe(true)
    expect(groups[1].after).toEqual({ 2027: 1 })
  })

  it('TARGET_CHANGE 总量条目（year=null）进 totalBefore/totalAfter', () => {
    const groups = groupSnapshot(
      [{ indicatorCode: 'A01', year: null, target: 4 }],
      [
        { indicatorCode: 'A01', year: null, target: 6 },
        { indicatorCode: 'A01', year: 2027, target: 2 },
      ],
    )
    expect(groups[0].totalBefore).toBe(4)
    expect(groups[0].totalAfter).toBe(6)
    expect(groups[0].after).toEqual({ 2027: 2 })
    expect(groups[0].isNew).toBe(false)
  })

  it('五年合计只加数值目标', () => {
    expect(groupTotal({ 2026: 1, 2027: 2, 2028: null })).toBe(3)
    expect(groupTotal({ 2026: true })).toBeNull()
    expect(groupTotal({})).toBeNull()
  })

  it('单元格差异：增 / 减 / 不变 / null 等价', () => {
    expect(diffCell(1, 2)).toEqual({ changed: true, increased: true, decreased: false })
    expect(diffCell(2, 1)).toEqual({ changed: true, increased: false, decreased: true })
    expect(diffCell(1, 1)).toEqual({ changed: false, increased: false, decreased: false })
    expect(diffCell(null, undefined)).toEqual({ changed: false, increased: false, decreased: false })
    expect(diffCell(null, 1).changed).toBe(true)
    expect(diffCell(true, null).changed).toBe(true)
  })
})

describe('isPendingStatus / canWithdraw / nodeOfStatus', () => {
  it('审批中状态识别', () => {
    expect(isPendingStatus('PENDING_OWNER_REVIEW')).toBe(true)
    expect(isPendingStatus('PENDING_RECEIVER_CONFIRM')).toBe(true)
    expect(isPendingStatus('PENDING_LEADER_APPROVAL')).toBe(true)
    expect(isPendingStatus('APPROVED')).toBe(false)
    expect(isPendingStatus('REJECTED')).toBe(false)
    expect(isPendingStatus('WITHDRAWN')).toBe(false)
  })

  it('仅申请人本人且未生效可撤回', () => {
    expect(canWithdraw('PENDING_OWNER_REVIEW', 'u1', 'u1')).toBe(true)
    expect(canWithdraw('PENDING_OWNER_REVIEW', 'u1', 'u2')).toBe(false)
    expect(canWithdraw('APPROVED', 'u1', 'u1')).toBe(false)
    expect(canWithdraw('PENDING_OWNER_REVIEW', null, 'u1')).toBe(false)
  })

  it('状态 → 审批环节推断', () => {
    expect(nodeOfStatus('PENDING_OWNER_REVIEW')).toBe('OWNER_REVIEW')
    expect(nodeOfStatus('PENDING_RECEIVER_CONFIRM')).toBe('RECEIVER_CONFIRM')
    expect(nodeOfStatus('PENDING_LEADER_APPROVAL')).toBe('LEADER_APPROVAL')
    expect(nodeOfStatus('APPROVED')).toBeNull()
  })
})
