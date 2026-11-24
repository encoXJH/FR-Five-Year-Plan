import { describe, expect, it, vi } from 'vitest'
import {
  canArchivePlanCard,
  canDeletePlanCard,
  canExportTrainingPlan,
  trainingPlanExportFilename,
} from './planCard'

describe('计划卡培训导出菜单', () => {
  it('仅培训计划且具备填报或审核权限时显示', () => {
    const can = vi.fn(() => true)

    expect(canExportTrainingPlan('TRAINING', can)).toBe(true)
    expect(can).toHaveBeenCalledWith('training:fill', 'submission:review')
    expect(canExportTrainingPlan('TRAINING', () => false)).toBe(false)
    expect(canExportTrainingPlan('INDICATOR', can)).toBe(false)
    expect(canExportTrainingPlan(undefined, can)).toBe(false)
  })

  it('清理计划名称中的文件名非法字符', () => {
    expect(trainingPlanExportFilename('2025/2026:职业培训*?'))
      .toBe('2025_2026_职业培训__-职业培训信息表.xlsx')
  })
})

describe('计划卡归档与删除菜单', () => {
  it('有管理权限且未归档时可归档，不区分计划类型', () => {
    expect(canArchivePlanCard(true, 'DRAFT')).toBe(true)
    expect(canArchivePlanCard(true, 'ACTIVE')).toBe(true)
    expect(canArchivePlanCard(true, 'ARCHIVED')).toBe(false)
    expect(canArchivePlanCard(false, 'ACTIVE')).toBe(false)
  })

  it('仅有管理权限的草稿或已归档计划可删除', () => {
    expect(canDeletePlanCard(true, 'DRAFT')).toBe(true)
    expect(canDeletePlanCard(true, 'ARCHIVED')).toBe(true)
    expect(canDeletePlanCard(true, 'ACTIVE')).toBe(false)
    expect(canDeletePlanCard(false, 'DRAFT')).toBe(false)
    expect(canDeletePlanCard(false, 'ARCHIVED')).toBe(false)
  })
})
