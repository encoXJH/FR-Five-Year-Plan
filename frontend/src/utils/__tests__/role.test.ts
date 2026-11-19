import { describe, expect, it } from 'vitest'
import {
  creatableRoles,
  creationNeedsOwnUnits,
  creationUnitIds,
  homePathForRoles,
  isStaffOnly,
  normalizeCreationRoles,
} from '../role'
import type { Role } from '@/types/enums'

describe('用户创建角色矩阵', () => {
  it.each<[Role[], Role[]]>([
    [['ADMIN'], ['ADMIN', 'LEADER', 'SCHOOL_OWNER', 'UNIT_OWNER']],
    [['LEADER'], ['LEADER', 'SCHOOL_OWNER', 'UNIT_OWNER']],
    [['SCHOOL_OWNER'], ['SCHOOL_OWNER', 'UNIT_OWNER']],
    [['UNIT_OWNER'], ['UNIT_OWNER', 'STAFF']],
    [['STAFF'], []],
  ])('创建人 %s 的可创建角色为 %s', (creatorRoles, expected) => {
    expect(creatableRoles(creatorRoles)).toEqual(expected)
  })

  it('多角色按矩阵取并集，干事仍只能由学院负责人创建', () => {
    expect(creatableRoles(['LEADER', 'UNIT_OWNER'])).toEqual([
      'LEADER',
      'SCHOOL_OWNER',
      'UNIT_OWNER',
      'STAFF',
    ])
    expect(creatableRoles(['LEADER', 'STAFF'])).not.toContain('STAFF')
  })

  it('新选择干事时移除其他角色，改选其他角色时移除干事', () => {
    expect(normalizeCreationRoles(['UNIT_OWNER', 'STAFF'], ['UNIT_OWNER'])).toEqual(['STAFF'])
    expect(normalizeCreationRoles(['STAFF', 'UNIT_OWNER'], ['STAFF'])).toEqual(['UNIT_OWNER'])
  })
})

describe('创建用户的单元约束', () => {
  it('干事隐藏归属输入后自动携带创建人的全部单元', () => {
    expect(creationUnitIds(['UNIT_OWNER'], ['STAFF'], ['other'], ['101', '102'])).toEqual(['101', '102'])
  })

  it('学院负责人创建学院负责人时仅保留本人单元', () => {
    expect(creationNeedsOwnUnits(['UNIT_OWNER'], ['UNIT_OWNER'])).toBe(true)
    expect(creationUnitIds(['UNIT_OWNER'], ['UNIT_OWNER'], ['101', '999'], ['101'])).toEqual(['101'])
  })

  it('管理员创建非学院角色不受本人单元限制', () => {
    expect(creationUnitIds(['ADMIN'], ['LEADER'], ['101', '102'], [])).toEqual(['101', '102'])
  })
})

describe('角色首页与纯干事识别', () => {
  it.each<[Role[], '/plans' | '/workbench']>([
    [['UNIT_OWNER'], '/workbench'],
    [['STAFF'], '/workbench'],
    [['UNIT_OWNER', 'STAFF'], '/workbench'],
    [['STAFF', 'LEADER'], '/plans'],
    [['ADMIN'], '/plans'],
  ])('角色 %s 的首页为 %s', (roles, expected) => {
    expect(homePathForRoles(roles)).toBe(expected)
  })

  it('只有纯 STAFF 使用我的任务视角', () => {
    expect(isStaffOnly(['STAFF'])).toBe(true)
    expect(isStaffOnly(['STAFF', 'UNIT_OWNER'])).toBe(false)
    expect(isStaffOnly([])).toBe(false)
  })
})
