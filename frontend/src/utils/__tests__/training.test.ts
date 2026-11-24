import { describe, expect, it } from 'vitest'
import {
  calculateTrainingFunding,
  calculateTrainingValues,
  toTrainingProjectInputs,
  validateTrainingProject,
  validateTrainingProjects,
} from '../training'
import type { TrainingField, TrainingFieldType, TrainingProjectInput } from '@/types/training'

function field(
  key: string,
  type: TrainingFieldType,
  required = false,
): TrainingField {
  return {
    key,
    column: Number(key.slice(1)),
    label: key,
    group: 'test',
    groupLabel: '测试分组',
    type,
    required,
    options: [],
  }
}

function project(data: TrainingProjectInput['data'], rowNo = 1): TrainingProjectInput {
  return { rowNo, data }
}

describe('培训项目自动合计', () => {
  it('按三个固定区间汇总人数，并以 c10+c11 计算到账经费', () => {
    const result = calculateTrainingValues({
      c10: 0.1,
      c11: 0.2,
      c12: 36,
      c18: 1,
      c19: '2',
      c31: 3,
      c33: 4,
      c43: 5,
      c45: 6,
      c53: 7,
    })

    expect(result.c32).toBe(6)
    expect(result.c44).toBe(9)
    expect(result.c54).toBe(13)
    expect(result.c12).toBe(36)
    expect(calculateTrainingFunding(result)).toBe(0.3)
  })

  it('未填写任何构成项时合计保持空值，保存前重排行号并计算', () => {
    expect(calculateTrainingValues({}).c32).toBeNull()
    expect(calculateTrainingFunding({})).toBeNull()

    const result = toTrainingProjectInputs([
      { id: '9', rowNo: 8, data: { c18: 2 } },
      { rowNo: 3, data: { c33: 4 } },
    ])
    expect(result.map((item) => item.rowNo)).toEqual([1, 2])
    expect(result[0]).toMatchObject({ id: '9', data: { c32: 2 } })
    expect(result[1].data.c44).toBe(4)
  })
})

describe('培训项目校验', () => {
  it('草稿允许缺必填项，提交必须补齐', () => {
    const fields = [field('c01', 'TEXT', true)]
    const value = project({})

    expect(validateTrainingProject(value, fields, 'draft')).toEqual([])
    expect(validateTrainingProject(value, fields, 'submit')).toEqual([
      { rowNo: 1, fieldKey: 'c01', message: '请填写c01' },
    ])
    expect(validateTrainingProjects([], fields, 'submit')[0].message).toContain('至少填写一个')
  })

  it('拒绝无效日期、日期倒置、负数和非整数人数', () => {
    const fields = [field('c05', 'DATE'), field('c06', 'DATE'), field('c18', 'INTEGER')]
    const invalidDate = validateTrainingProject(
      project({ c05: '2026-02-30', c18: -1 }),
      fields,
      'draft',
    )
    expect(invalidDate.map((item) => item.message)).toEqual([
      'c05须为有效日期',
      'c18不能为负数',
    ])

    const reversed = validateTrainingProject(
      project({ c05: '2026-03-02', c06: '2026-03-01', c18: 1.5 }),
      fields,
      'draft',
    )
    expect(reversed.map((item) => item.message)).toEqual([
      'c18须为整数',
      '项目结束日期不能早于开班日期',
    ])
  })

  it('校验总人数与培训对象合计一致，两个分类合计不得超过总人数', () => {
    const issues = validateTrainingProject(
      project({ c14: 5, c18: 6, c33: 7, c45: 8 }),
      [],
      'submit',
    )

    expect(issues.map((item) => item.fieldKey)).toEqual(['c14', 'c44', 'c54'])
    expect(issues[0].message).toContain('6 人')
  })

  it('校验到账经费不超过合同金额，并允许完整有效项目', () => {
    const invalid = validateTrainingProject(
      project({ c09: 10, c10: 6, c11: 5 }),
      [],
      'draft',
    )
    expect(invalid).toHaveLength(1)
    expect(invalid[0].fieldKey).toBe('c11')

    const valid = validateTrainingProject(
      project({
        c01: '人工智能培训',
        c05: '2026-03-01',
        c06: '2026-03-02',
        c09: 11,
        c10: 6,
        c11: 5,
        c14: 2,
        c18: 2,
      }),
      [field('c01', 'TEXT', true), field('c05', 'DATE', true), field('c06', 'DATE', true)],
      'submit',
    )
    expect(valid).toEqual([])
  })
})
