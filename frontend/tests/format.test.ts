import { describe, expect, it } from 'vitest'
import { formatBusinessValue, percentWidth, remainingGap, semanticText } from '@/utils/format'

describe('业务值显示与核算工具', () => {
  it('严格区分空值、数值零、不适用和未分配', () => {
    expect(formatBusinessValue(null)).toBe('未提供')
    expect(formatBusinessValue(undefined, { unit: '个' })).toBe('未提供')
    expect(formatBusinessValue(0, { unit: '个' })).toBe('0 个')
    expect(formatBusinessValue(null, { raw: '—' })).toBe('不适用')
    expect(formatBusinessValue(null, { raw: '无' })).toBe('未分配')
    expect(semanticText('待补')).toBe('待补口径')
  })

  it('分别处理是否、比值、百分比和文本口径', () => {
    expect(formatBusinessValue(null, { valueKind: 'BOOLEAN', boolValue: 0 })).toBe('否')
    expect(formatBusinessValue(null, { valueKind: 'BOOLEAN', boolValue: 1 })).toBe('是')
    expect(formatBusinessValue(null, { valueKind: 'BOOLEAN', boolValue: null })).toBe('未提供')
    expect(formatBusinessValue(null, { text: '1:350' })).toBe('1:350')
    expect(formatBusinessValue('15', { valueKind: 'PERCENT', unit: '%' })).toBe('15 %')
    expect(formatBusinessValue(null, { valueKind: 'NONE' })).toBe('不适用')
  })

  it('缺口不小于零，进度条 capped 但保留实际比率输入', () => {
    expect(remainingGap(10, 4, 2)).toBe(4)
    expect(remainingGap(10, 12, 0)).toBe(0)
    expect(remainingGap(null, 0, 0)).toBeNull()
    expect(percentWidth(135.5)).toBe(100)
    expect(percentWidth(45.5)).toBe(45.5)
    expect(percentWidth(-1)).toBe(0)
  })
})
