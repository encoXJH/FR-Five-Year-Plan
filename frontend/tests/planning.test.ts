import { describe, expect, it } from 'vitest'
import { createIndicatorPayload, updateIndicatorPayload } from '@/api/planning'
import type { Indicator } from '@/types/api'

const source: Partial<Indicator> = {
  id: 99,
  plan_id: 1,
  direction_id: 2,
  indicator_code: 'I-001',
  indicator_name: '测试指标',
  indicator_desc: '完整统计口径',
  indicator_type_code: 'DEVELOPMENT',
  unit_code: 'ITEM',
  standard_unit_code: 'ITEM',
  parse_status: 'CONFIRMED',
  indicator_status: 'DRAFT',
  annual_targets: [{ id: 5, target_year: 2026, professional_target_value: 4, lock_version: 3 }],
  departments: [{ org_id: 8, relation_type: 'LEAD', responsibility_desc: '牵头' }],
}

describe('指标创建与更新请求 schema', () => {
  it('创建仅发送 IndicatorInput 允许字段', () => {
    const payload = createIndicatorPayload(source)
    expect(payload.indicator_code).toBe('I-001')
    expect(payload).not.toHaveProperty('parse_status')
    expect(payload).not.toHaveProperty('indicator_status')
    expect(payload).not.toHaveProperty('standard_unit_code')
    expect(payload).not.toHaveProperty('plan_id')
    expect(payload).not.toHaveProperty('id')
    const annual = payload.annual_targets as Array<Record<string, unknown>>
    expect(annual[0]).toEqual({ target_year: 2026, professional_target_value: 4, expected_version: 3 })
    expect(payload.departments).toEqual([{ org_id: 8, relation_type: 'LEAD', responsibility_desc: '牵头' }])
  })

  it('更新仅发送 IndicatorUpdate 允许字段并保留量化目标', () => {
    const payload = updateIndicatorPayload(source)
    expect(payload).not.toHaveProperty('indicator_code')
    expect(payload).not.toHaveProperty('indicator_status')
    expect(payload).not.toHaveProperty('plan_id')
    expect(payload).not.toHaveProperty('id')
    expect(payload.parse_status).toBe('CONFIRMED')
    expect(payload.standard_unit_code).toBe('ITEM')
    expect(payload.annual_targets).toEqual([{ target_year: 2026, professional_target_value: 4, expected_version: 3 }])
  })
})
