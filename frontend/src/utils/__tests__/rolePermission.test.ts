import { describe, expect, it } from 'vitest'
import { getRolePermissionState, selectRolePermissions } from '../rolePermission'
import type { Role } from '@/types/enums'
import type { PermissionDefinition } from '@/types/system'

function permission(
  code: string,
  configurableRoles: Role[] = [],
  lockedRoles: Role[] = [],
): PermissionDefinition {
  return {
    code,
    name: code,
    group: '测试权限',
    description: `${code} 说明`,
    configurableRoles,
    lockedRoles,
  }
}

describe('角色权限可编辑状态', () => {
  it('仅 configurableRoles 中的角色可编辑，其他组合不可授权', () => {
    const item = permission('stats:view', ['LEADER'])

    expect(getRolePermissionState('LEADER', item)).toBe('EDITABLE')
    expect(getRolePermissionState('SCHOOL_OWNER', item)).toBe('UNAVAILABLE')
  })

  it('lockedRoles 优先于 configurableRoles，授权项核心锁定', () => {
    const item = permission('audit-log:view', ['LEADER'], ['LEADER'])

    expect(getRolePermissionState('LEADER', item)).toBe('LOCKED')
  })

  it('管理员的全部权限都核心锁定', () => {
    const item = permission('submission:submit', ['UNIT_OWNER'])

    expect(getRolePermissionState('ADMIN', item)).toBe('LOCKED')
  })
})

describe('角色权限选择结果', () => {
  const definitions = [
    permission('stats:view', ['LEADER']),
    permission('audit-log:view', [], ['LEADER']),
    permission('submission:submit', ['UNIT_OWNER']),
  ]

  it('保留可编辑授权项并强制包含锁定项，排除不可配置项和未知权限码', () => {
    expect(
      selectRolePermissions('LEADER', definitions, [
        'stats:view',
        'submission:submit',
        'unknown:permission',
      ]),
    ).toEqual(['stats:view', 'audit-log:view'])
  })

  it('可编辑项未授权时不选中，锁定项仍必须提交', () => {
    expect(selectRolePermissions('LEADER', definitions, [])).toEqual(['audit-log:view'])
  })

  it('管理员始终选择全部权限', () => {
    expect(selectRolePermissions('ADMIN', definitions, [])).toEqual([
      'stats:view',
      'audit-log:view',
      'submission:submit',
    ])
  })
})
