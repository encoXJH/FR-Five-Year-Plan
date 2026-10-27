import { describe, expect, it } from 'vitest'
import { diffObjects, flattenObject, formatDiffValue, prettyJson } from '../jsonDiff'

describe('flattenObject', () => {
  it('平铺嵌套对象为点号路径', () => {
    expect(flattenObject({ a: 1, b: { c: 2, d: { e: 3 } } })).toEqual({
      a: 1,
      'b.c': 2,
      'b.d.e': 3,
    })
  })

  it('空对象作为叶子值保留', () => {
    expect(flattenObject({ a: {}, b: 1 })).toEqual({ a: {}, b: 1 })
  })

  it('数组不展开，整体作为叶子值', () => {
    expect(flattenObject({ a: [1, 2] })).toEqual({ a: [1, 2] })
  })
})

describe('diffObjects', () => {
  it('识别 changed / added / removed', () => {
    const diff = diffObjects(
      { name: '旧', year: 2026, extra: 'x' },
      { name: '新', year: 2026, note: '新增' },
    )
    expect(diff).toEqual([
      { field: 'name', before: '旧', after: '新', changeType: 'changed' },
      { field: 'extra', before: 'x', after: undefined, changeType: 'removed' },
      { field: 'note', before: undefined, after: '新增', changeType: 'added' },
    ])
  })

  it('嵌套字段按点号路径比对', () => {
    const diff = diffObjects({ yearTargets: { '2026': 1, '2027': 2 } }, { yearTargets: { '2026': 3, '2027': 2 } })
    expect(diff).toEqual([
      { field: 'yearTargets.2026', before: 1, after: 3, changeType: 'changed' },
    ])
  })

  it('数组内容不同视为 changed', () => {
    const diff = diffObjects({ roles: ['ADMIN'] }, { roles: ['ADMIN', 'LEADER'] })
    expect(diff).toHaveLength(1)
    expect(diff[0].changeType).toBe('changed')
  })

  it('无差异返回空数组', () => {
    expect(diffObjects({ a: 1, b: { c: [1] } }, { a: 1, b: { c: [1] } })).toEqual([])
  })

  it('null 入参按空对象处理', () => {
    const diff = diffObjects(null, { a: 1 })
    expect(diff).toEqual([{ field: 'a', before: undefined, after: 1, changeType: 'added' }])
    expect(diffObjects({ a: 1 }, null)).toEqual([
      { field: 'a', before: 1, after: undefined, changeType: 'removed' },
    ])
    expect(diffObjects(null, null)).toEqual([])
  })
})

describe('formatDiffValue', () => {
  it('undefined 显示（空），对象显示紧凑 JSON', () => {
    expect(formatDiffValue(undefined)).toBe('（空）')
    expect(formatDiffValue(null)).toBe('null')
    expect(formatDiffValue({ a: 1 })).toBe('{"a":1}')
    expect(formatDiffValue('文本')).toBe('文本')
    expect(formatDiffValue(0)).toBe('0')
  })
})

describe('prettyJson', () => {
  it('美化输出，null 返回空串', () => {
    expect(prettyJson({ a: 1 })).toBe('{\n  "a": 1\n}')
    expect(prettyJson(null)).toBe('')
    expect(prettyJson(undefined)).toBe('')
  })
})
