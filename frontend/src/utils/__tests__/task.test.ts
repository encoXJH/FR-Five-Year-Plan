import { describe, expect, it } from 'vitest'
import { canWriteTask, formatDeadline, progressPercentage } from '../task'

const NOW = new Date('2026-10-22T12:00:00+08:00')

describe('canWriteTask', () => {
  it('历史任务禁止写入，普通任务允许按既有规则继续校验', () => {
    expect(canWriteTask({ isHistorical: true })).toBe(false)
    expect(canWriteTask({ isHistorical: false })).toBe(true)
    expect(canWriteTask(null)).toBe(false)
  })
})

describe('formatDeadline', () => {
  it('空值显示 —', () => {
    expect(formatDeadline(null, NOW).text).toBe('—')
    expect(formatDeadline(undefined, NOW).urgent).toBe(false)
  })

  it('剩余 ≤ 7 天附剩余天数并标急', () => {
    const result = formatDeadline('2026-10-27T00:00:00+08:00', NOW)
    expect(result.text).toBe('2026-10-27（剩 5 天）')
    expect(result.urgent).toBe(true)
    expect(result.overdue).toBe(false)
  })

  it('剩余 > 7 天只显示日期', () => {
    const result = formatDeadline('2026-12-31T00:00:00+08:00', NOW)
    expect(result.text).toBe('2026-12-31')
    expect(result.urgent).toBe(false)
  })

  it('已过截止日期显示已逾期', () => {
    const result = formatDeadline('2026-10-20T00:00:00+08:00', NOW)
    expect(result.text).toBe('2026-10-20（已逾期）')
    expect(result.urgent).toBe(true)
    expect(result.overdue).toBe(true)
  })
})

describe('progressPercentage', () => {
  it('小数转百分比并封顶 100', () => {
    expect(progressPercentage(0.625)).toBe(62.5)
    expect(progressPercentage(1.069)).toBe(100)
  })

  it('空值与负数归零', () => {
    expect(progressPercentage(null)).toBe(0)
    expect(progressPercentage(undefined)).toBe(0)
    expect(progressPercentage(-0.2)).toBe(0)
  })
})
