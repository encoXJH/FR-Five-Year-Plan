import { describe, expect, it } from 'vitest'
import {
  EMPTY_DISPLAY,
  formatCount,
  formatDate,
  formatDateTime,
  formatDateTimeFull,
  formatDiff,
  formatGrowth,
  formatLargeNumber,
  formatMoney,
  formatNumber,
  formatPercent,
  formatRatio,
  formatRelativeTime,
  timeProgressOfPlan,
  timeProgressOfYear,
} from '../format'

describe('formatCount', () => {
  it('千分位、0 位小数', () => {
    expect(formatCount(25000)).toBe('25,000')
    expect(formatCount(5)).toBe('5')
    expect(formatCount(0)).toBe('0')
  })
  it('空值显示 —', () => {
    expect(formatCount(null)).toBe(EMPTY_DISPLAY)
    expect(formatCount(undefined)).toBe(EMPTY_DISPLAY)
    expect(formatCount(Number.NaN)).toBe(EMPTY_DISPLAY)
  })
})

describe('formatMoney', () => {
  it('千分位、2 位小数', () => {
    expect(formatMoney(2860)).toBe('2,860.00')
    expect(formatMoney(0)).toBe('0.00')
    expect(formatMoney(1234.5)).toBe('1,234.50')
  })
  it('空值显示 —', () => {
    expect(formatMoney(null)).toBe(EMPTY_DISPLAY)
  })
})

describe('formatRatio', () => {
  it('统一 1 位小数', () => {
    expect(formatRatio(18)).toBe('18.0')
    expect(formatRatio(17.96)).toBe('18.0')
  })
  it('空值显示 —', () => {
    expect(formatRatio(undefined)).toBe(EMPTY_DISPLAY)
  })
})

describe('formatPercent', () => {
  it('小数 ×100、1 位小数、带 %', () => {
    expect(formatPercent(0.62)).toBe('62.0%')
    expect(formatPercent(1.069)).toBe('106.9%')
    expect(formatPercent(0)).toBe('0.0%')
  })
  it('空值显示 —', () => {
    expect(formatPercent(null)).toBe(EMPTY_DISPLAY)
  })
})

describe('formatGrowth', () => {
  it('正数带 +，负数带 -，1 位小数', () => {
    expect(formatGrowth(0.028)).toBe('+2.8%')
    expect(formatGrowth(-0.015)).toBe('-1.5%')
  })
  it('0 不带符号', () => {
    expect(formatGrowth(0)).toBe('0.0%')
  })
  it('空值显示 —', () => {
    expect(formatGrowth(null)).toBe(EMPTY_DISPLAY)
  })
})

describe('formatDiff', () => {
  it('带符号，0 显示 0 ✓', () => {
    expect(formatDiff(1)).toBe('+1')
    expect(formatDiff(-1)).toBe('-1')
    expect(formatDiff(0)).toBe('0 ✓')
  })
  it('空值显示 —', () => {
    expect(formatDiff(undefined)).toBe(EMPTY_DISPLAY)
  })
})

describe('formatLargeNumber', () => {
  it('≥ 10 万以万为单位、1 位小数', () => {
    expect(formatLargeNumber(123456)).toBe('12.3 万')
    expect(formatLargeNumber(100000)).toBe('10.0 万')
  })
  it('小于 10 万按计数格式化', () => {
    expect(formatLargeNumber(99999)).toBe('99,999')
  })
  it('空值显示 —', () => {
    expect(formatLargeNumber(null)).toBe(EMPTY_DISPLAY)
  })
})

describe('formatDate / formatDateTime / formatDateTimeFull', () => {
  const local = new Date(2026, 9, 22, 15, 32, 7)

  it('日期 YYYY-MM-DD', () => {
    expect(formatDate(local)).toBe('2026-10-22')
    expect(formatDate('2026-10-22T15:32:07')).toBe('2026-10-22')
  })
  it('日期时间 YYYY-MM-DD HH:mm', () => {
    expect(formatDateTime(local)).toBe('2026-10-22 15:32')
    expect(formatDateTime('2026-10-22T15:32:07')).toBe('2026-10-22 15:32')
  })
  it('日志格式 YYYY-MM-DD HH:mm:ss', () => {
    expect(formatDateTimeFull(local)).toBe('2026-10-22 15:32:07')
  })
  it('个位数月日时分秒补零', () => {
    expect(formatDateTimeFull(new Date(2026, 0, 5, 9, 4, 3))).toBe('2026-01-05 09:04:03')
  })
  it('非法与空输入显示 —', () => {
    expect(formatDate(null)).toBe(EMPTY_DISPLAY)
    expect(formatDateTime('not-a-date')).toBe(EMPTY_DISPLAY)
    expect(formatDateTimeFull(undefined)).toBe(EMPTY_DISPLAY)
  })
})

describe('formatRelativeTime', () => {
  const now = new Date(2026, 9, 22, 15, 32, 0)

  it('1 分钟内显示刚刚', () => {
    expect(formatRelativeTime(new Date(2026, 9, 22, 15, 31, 30), now)).toBe('刚刚')
  })
  it('60 分钟内显示 n 分钟前', () => {
    expect(formatRelativeTime(new Date(2026, 9, 22, 15, 29, 0), now)).toBe('3 分钟前')
  })
  it('24 小时内显示 n 小时前', () => {
    expect(formatRelativeTime(new Date(2026, 9, 22, 13, 32, 0), now)).toBe('2 小时前')
  })
  it('超过 24 小时显示日期', () => {
    expect(formatRelativeTime(new Date(2026, 9, 20, 15, 32, 0), now)).toBe('2026-10-20')
  })
  it('空值显示 —', () => {
    expect(formatRelativeTime(null, now)).toBe(EMPTY_DISPLAY)
  })
})

describe('formatNumber', () => {
  it('千分位、最多 4 位小数', () => {
    expect(formatNumber(25000)).toBe('25,000')
    expect(formatNumber(12.5)).toBe('12.5')
    expect(formatNumber(0)).toBe('0')
    expect(formatNumber(1.12345)).toBe('1.1235')
  })
  it('空值显示 —', () => {
    expect(formatNumber(null)).toBe(EMPTY_DISPLAY)
    expect(formatNumber(undefined)).toBe(EMPTY_DISPLAY)
  })
})

describe('timeProgressOfYear', () => {
  it('已过天数（含今天）÷ 当年总天数，如 2026-10-22 → 295/365 ≈ 80.8%', () => {
    const progress = timeProgressOfYear(2026, new Date(2026, 9, 22, 15, 32))
    expect(progress).toBeCloseTo(295 / 365, 6)
    expect(formatPercent(progress)).toBe('80.8%')
  })
  it('闰年按 366 天计，年末最后一天为 100%', () => {
    expect(timeProgressOfYear(2028, new Date(2028, 0, 1))).toBeCloseTo(1 / 366, 6)
    expect(timeProgressOfYear(2028, new Date(2028, 11, 31))).toBe(1)
  })
  it('非当年按边界截断：未来年份为 0，过去年份为 1', () => {
    const now = new Date(2026, 9, 22)
    expect(timeProgressOfYear(2030, now)).toBe(0)
    expect(timeProgressOfYear(2025, now)).toBe(1)
  })
})

describe('timeProgressOfPlan', () => {
  it('规划期已过天数 ÷ 规划期总天数，如 2026-10-22 在 2026–2030 → 295/1826', () => {
    const progress = timeProgressOfPlan(2026, 2030, new Date(2026, 9, 22, 15, 32))
    expect(progress).toBeCloseTo(295 / 1826, 6)
  })
  it('末年最后一天为 100%；规划期前为 0，规划期后为 1', () => {
    expect(timeProgressOfPlan(2026, 2030, new Date(2030, 11, 31))).toBe(1)
    expect(timeProgressOfPlan(2026, 2030, new Date(2025, 5, 1))).toBe(0)
    expect(timeProgressOfPlan(2026, 2030, new Date(2031, 0, 1))).toBe(1)
  })
})
