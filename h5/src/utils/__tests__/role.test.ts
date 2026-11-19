import { describe, expect, it } from 'vitest'
import {
  canPerformOwnerOnlyActions,
  homePathForRoles,
  isStaffOnly,
  usesTaskWorkbenchHome,
} from '../role'

describe('角色首页', () => {
  it.each([
    [['UNIT_OWNER']],
    [['STAFF']],
    [['UNIT_OWNER', 'STAFF']],
  ] as const)('纯学院负责人/干事角色进入工作台：%j', (roles) => {
    expect(usesTaskWorkbenchHome(roles)).toBe(true)
    expect(homePathForRoles(roles)).toBe('/workbench')
  })

  it('包含其他管理角色时保留计划首页', () => {
    expect(homePathForRoles(['STAFF', 'LEADER'])).toBe('/plans')
    expect(homePathForRoles([])).toBe('/plans')
  })
})

describe('纯 STAFF 可见操作', () => {
  it('仅 STAFF 时禁止负责人专属操作', () => {
    expect(isStaffOnly(['STAFF'])).toBe(true)
    expect(canPerformOwnerOnlyActions(['STAFF'])).toBe(false)
  })

  it('多角色取并集，兼具负责人角色时保留负责人操作', () => {
    expect(isStaffOnly(['STAFF', 'UNIT_OWNER'])).toBe(false)
    expect(canPerformOwnerOnlyActions(['STAFF', 'UNIT_OWNER'])).toBe(true)
    expect(canPerformOwnerOnlyActions(['STAFF', 'LEADER'])).toBe(true)
  })
})
