import { describe, expect, it } from 'vitest'
import {
  approvedReduction,
  buildMetricValue,
  CALIBER_INPUT_FIELDS,
  computeRatio,
  formatMetricValue,
  isApprovedValueValid,
  parseSubmissionRemark,
  projectAnnualCompletionRate,
  serializeSubmissionRemark,
} from '../submission'
import type { StatCaliber } from '@/types/enums'

const ALL_CALIBERS: StatCaliber[] = [
  'INCREMENT',
  'STOCK',
  'GROWTH',
  'RATIO',
  'DIRECT_PERCENT',
  'BOOLEAN',
]

describe('CALIBER_INPUT_FIELDS 口径→输入项映射（API 3.5）', () => {
  it('六种口径各有对应采集字段', () => {
    expect(CALIBER_INPUT_FIELDS.INCREMENT).toEqual(['amount'])
    expect(CALIBER_INPUT_FIELDS.STOCK).toEqual(['total'])
    expect(CALIBER_INPUT_FIELDS.GROWTH).toEqual(['total'])
    expect(CALIBER_INPUT_FIELDS.RATIO).toEqual(['numerator', 'denominator'])
    expect(CALIBER_INPUT_FIELDS.DIRECT_PERCENT).toEqual(['percent'])
    expect(CALIBER_INPUT_FIELDS.BOOLEAN).toEqual(['achieved'])
    expect(Object.keys(CALIBER_INPUT_FIELDS).sort()).toEqual([...ALL_CALIBERS].sort())
  })
})

describe('buildMetricValue', () => {
  it('只保留本口径字段，其余置 null', () => {
    const value = buildMetricValue('RATIO', {
      numerator: 62,
      denominator: 100,
      amount: 5,
      percent: 0.62,
      achieved: true,
    })
    expect(value).toEqual({
      amount: null,
      total: null,
      numerator: 62,
      denominator: 100,
      percent: null,
      achieved: null,
    })
  })

  it('未填的采集字段置 null 而不是 undefined', () => {
    expect(buildMetricValue('INCREMENT', {}).amount).toBeNull()
    expect(buildMetricValue('BOOLEAN', {}).achieved).toBeNull()
  })
})

describe('BOOLEAN 完成日期 remark 结构化保存', () => {
  it('保存与回显往返一致，重复序列化不叠加元数据', () => {
    const saved = serializeSubmissionRemark('BOOLEAN', '已完成验收', '2026-10-30')
    expect(saved).toContain('"completionDate":"2026-10-30"')
    expect(parseSubmissionRemark(saved)).toEqual({
      remark: '已完成验收',
      completionDate: '2026-10-30',
    })
    expect(serializeSubmissionRemark('BOOLEAN', saved, '2026-10-30')).toBe(saved)
  })

  it('清空日期会移除旧元数据并保留说明', () => {
    const saved = serializeSubmissionRemark('BOOLEAN', '补充说明', '2026-10-30')
    expect(serializeSubmissionRemark('BOOLEAN', saved, '')).toBe('补充说明')
    expect(serializeSubmissionRemark('BOOLEAN', '', null)).toBeNull()
  })

  it('其他口径不修改说明，非法元数据不静默吞掉文本', () => {
    expect(serializeSubmissionRemark('INCREMENT', '普通说明', '2026-10-30')).toBe('普通说明')
    expect(parseSubmissionRemark('[FZSU_BOOLEAN]bad json[/FZSU_BOOLEAN]')).toEqual({
      remark: '[FZSU_BOOLEAN]bad json[/FZSU_BOOLEAN]',
      completionDate: '',
    })
  })
})

describe('computeRatio 占比实时计算', () => {
  it('分子 ÷ 分母', () => {
    expect(computeRatio(62, 100)).toBeCloseTo(0.62)
  })

  it('分母为 0 / 空值返回 null（UI 显示 —）', () => {
    expect(computeRatio(1, 0)).toBeNull()
    expect(computeRatio(1, null)).toBeNull()
    expect(computeRatio(null, 100)).toBeNull()
    expect(computeRatio(undefined, undefined)).toBeNull()
  })
})

describe('isApprovedValueValid 认定值不能大于填报值（60005）', () => {
  it('INCREMENT：认定 ≤ 填报通过，大于不通过', () => {
    expect(isApprovedValueValid('INCREMENT', { amount: 2 }, { amount: 1 })).toBe(true)
    expect(isApprovedValueValid('INCREMENT', { amount: 2 }, { amount: 2 })).toBe(true)
    expect(isApprovedValueValid('INCREMENT', { amount: 2 }, { amount: 3 })).toBe(false)
  })

  it('RATIO：分子 / 分母任一被调大都判不通过', () => {
    const sub = { numerator: 62, denominator: 100 }
    expect(isApprovedValueValid('RATIO', sub, { numerator: 60, denominator: 100 })).toBe(true)
    expect(isApprovedValueValid('RATIO', sub, { numerator: 62, denominator: 120 })).toBe(false)
    expect(isApprovedValueValid('RATIO', sub, { numerator: 63, denominator: 100 })).toBe(false)
  })

  it('BOOLEAN：认定值必须与填报一致', () => {
    expect(isApprovedValueValid('BOOLEAN', { achieved: true }, { achieved: true })).toBe(true)
    expect(isApprovedValueValid('BOOLEAN', { achieved: true }, { achieved: false })).toBe(false)
  })

  it('填报值缺失时认定值不能出现', () => {
    expect(isApprovedValueValid('STOCK', { total: null }, { total: 5 })).toBe(false)
  })
})

describe('approvedReduction 核减量', () => {
  it('返回第一个被核减字段的差值', () => {
    expect(approvedReduction('INCREMENT', { amount: 2 }, { amount: 1 })).toBe(1)
    expect(approvedReduction('RATIO', { numerator: 62, denominator: 100 }, { numerator: 62, denominator: 90 })).toBe(10)
  })

  it('无核减返回 null', () => {
    expect(approvedReduction('INCREMENT', { amount: 2 }, { amount: 2 })).toBeNull()
    expect(approvedReduction('BOOLEAN', { achieved: true }, { achieved: true })).toBeNull()
  })
})

describe('projectAnnualCompletionRate 年度目标超限提示投影', () => {
  it('INCREMENT 累计已认定值，等于目标与超过目标可区分', () => {
    expect(projectAnnualCompletionRate('INCREMENT', 10, { actualValue: 6 }, { amount: 4 })).toBe(1)
    expect(projectAnnualCompletionRate('INCREMENT', 10, { actualValue: 6 }, { amount: 5 })).toBe(1.1)
  })

  it('STOCK 与 GROWTH 使用候选最新总量，不累加已认定值', () => {
    for (const caliber of ['STOCK', 'GROWTH'] as const) {
      expect(projectAnnualCompletionRate(caliber, 100, { actualValue: 500 }, { total: 100 })).toBe(1)
      expect(projectAnnualCompletionRate(caliber, 100, { actualValue: 80 }, { total: 101 })).toBe(1.01)
    }
  })

  it('RATIO 分别累计已认定与候选分子分母', () => {
    const recognized = { actualNumerator: 40, actualDenominator: 100 }
    expect(projectAnnualCompletionRate('RATIO', 0.4, recognized, { numerator: 20, denominator: 50 })).toBe(1)
    expect(projectAnnualCompletionRate('RATIO', 0.4, recognized, { numerator: 20, denominator: 40 }))
      .toBeCloseTo(60 / 140 / 0.4)
  })

  it('DIRECT_PERCENT 使用候选最新百分比', () => {
    expect(projectAnnualCompletionRate('DIRECT_PERCENT', 0.8, { actualValue: 0.9 }, { percent: 0.8 })).toBe(1)
    expect(projectAnnualCompletionRate('DIRECT_PERCENT', 0.8, { actualValue: 0.7 }, { percent: 0.81 }))
      .toBeCloseTo(1.0125)
  })

  it('BOOLEAN 与无有效目标不投影', () => {
    expect(projectAnnualCompletionRate('BOOLEAN', true, null, { achieved: true })).toBeNull()
    expect(projectAnnualCompletionRate('INCREMENT', 0, null, { amount: 1 })).toBeNull()
    expect(projectAnnualCompletionRate('INCREMENT', null, null, { amount: 1 })).toBeNull()
  })

  it('候选输入不完整时不显示超限提示', () => {
    expect(projectAnnualCompletionRate('INCREMENT', 10, null, {})).toBeNull()
    expect(projectAnnualCompletionRate('RATIO', 0.4, null, { numerator: 1 })).toBeNull()
  })
})

describe('formatMetricValue 按口径展示（UI 规范 3.4）', () => {
  it('INCREMENT 带 + 前缀和单位', () => {
    expect(formatMetricValue({ amount: 1 }, 'INCREMENT', '项')).toBe('+1 项')
    expect(formatMetricValue({ amount: 0 }, 'INCREMENT', '项')).toBe('0 项')
  })

  it('RATIO 显示 分子 / 分母（占比 %）', () => {
    expect(formatMetricValue({ numerator: 62, denominator: 100 }, 'RATIO')).toBe('62 / 100（62.0%）')
    expect(formatMetricValue({ numerator: 1, denominator: 0 }, 'RATIO')).toBe('1 / 0')
  })

  it('DIRECT_PERCENT 小数 ×100 显示 %', () => {
    expect(formatMetricValue({ percent: 0.62 }, 'DIRECT_PERCENT')).toBe('62.0%')
  })

  it('BOOLEAN 显示 是 / 否', () => {
    expect(formatMetricValue({ achieved: true }, 'BOOLEAN')).toBe('是')
    expect(formatMetricValue({ achieved: false }, 'BOOLEAN')).toBe('否')
  })

  it('空值显示 —', () => {
    expect(formatMetricValue(null, 'INCREMENT')).toBe('—')
    expect(formatMetricValue({ amount: null }, 'INCREMENT')).toBe('—')
  })
})
