import { describe, expect, it } from 'vitest'
import { canReviewNode, canReviewSubmission, initialReviewValue } from '../review'
import type { ReviewNodeTarget } from '../review'
import { approvedReduction, isApprovedValueValid } from '../submission'

function target(partial: Partial<ReviewNodeTarget> = {}): ReviewNodeTarget {
  return {
    status: 'PENDING_REVIEW',
    currentReviewNode: 'UNIT_REVIEW',
    value: { amount: 3 },
    approvedValue: null,
    ...partial,
  }
}

describe('两级审核节点操作守卫', () => {
  it('学院负责人只处理院审，校级负责人只处理校审', () => {
    expect(canReviewNode(['UNIT_OWNER'], 'UNIT_REVIEW')).toBe(true)
    expect(canReviewNode(['UNIT_OWNER'], 'LEADER_REVIEW')).toBe(false)
    expect(canReviewNode(['LEADER'], 'UNIT_REVIEW')).toBe(false)
    expect(canReviewNode(['LEADER'], 'LEADER_REVIEW')).toBe(true)
  })

  it('管理员可代审两级，多角色按并集，纯 STAFF 与 SCHOOL_OWNER 无审核资格', () => {
    expect(canReviewNode(['ADMIN'], 'UNIT_REVIEW')).toBe(true)
    expect(canReviewNode(['ADMIN'], 'LEADER_REVIEW')).toBe(true)
    expect(canReviewNode(['STAFF', 'UNIT_OWNER'], 'UNIT_REVIEW')).toBe(true)
    expect(canReviewNode(['STAFF', 'LEADER'], 'LEADER_REVIEW')).toBe(true)
    expect(canReviewNode(['STAFF'], 'UNIT_REVIEW')).toBe(false)
    expect(canReviewNode(['STAFF'], 'LEADER_REVIEW')).toBe(false)
    expect(canReviewNode(['SCHOOL_OWNER'], 'UNIT_REVIEW')).toBe(false)
    expect(canReviewNode(['SCHOOL_OWNER'], 'LEADER_REVIEW')).toBe(false)
  })

  it('非待审或缺少节点时 fail-closed', () => {
    expect(canReviewSubmission(['ADMIN'], target({ status: 'APPROVED' }))).toBe(false)
    expect(canReviewSubmission(['ADMIN'], target({ currentReviewNode: null }))).toBe(false)
  })
})

describe('校审继承院审认定值', () => {
  it('校审从院审暂存值初始化并返回副本', () => {
    const item = target({
      currentReviewNode: 'LEADER_REVIEW',
      value: { amount: 3 },
      approvedValue: { amount: 2 },
    })
    const initial = initialReviewValue(item)
    expect(initial).toEqual({ amount: 2 })
    expect(initial).not.toBe(item.approvedValue)
  })

  it('院审不从暂存值初始化，无暂存值时校审回退填报值', () => {
    expect(initialReviewValue(target({ approvedValue: { amount: 1 } }))).toEqual({ amount: 3 })
    expect(initialReviewValue(target({ currentReviewNode: 'LEADER_REVIEW' }))).toEqual({ amount: 3 })
  })

  it('校审核减量以院审认定值为基准，同时仍校验不能超过填报值', () => {
    const item = target({
      currentReviewNode: 'LEADER_REVIEW',
      value: { amount: 3 },
      approvedValue: { amount: 2 },
    })
    const baseline = initialReviewValue(item)
    expect(approvedReduction('INCREMENT', baseline, { amount: 1 })).toBe(1)
    expect(isApprovedValueValid('INCREMENT', item.value, { amount: 1 })).toBe(true)
    expect(isApprovedValueValid('INCREMENT', item.value, { amount: 4 })).toBe(false)
  })
})
