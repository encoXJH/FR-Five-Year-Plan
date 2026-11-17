import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getRolePermissions, updateRolePermissions } from '../system'
import { http } from '@/utils/request'
import type { RoleItem, RolePermissionResponse } from '@/types/system'

vi.mock('@/utils/request', () => ({
  http: { get: vi.fn(), put: vi.fn() },
}))

const getMock = http.get as unknown as ReturnType<typeof vi.fn>
const putMock = http.put as unknown as ReturnType<typeof vi.fn>

const leaderRole: RoleItem = {
  role: 'LEADER',
  roleName: '校级负责人',
  permissions: ['stats:view', 'audit-log:view'],
  version: 3,
}

const response: RolePermissionResponse = {
  roles: [leaderRole],
  permissions: [
    {
      code: 'stats:view',
      name: '查看统计',
      group: '统计报表',
      description: '查看全校统计报表',
      configurableRoles: ['LEADER'],
      lockedRoles: [],
    },
    {
      code: 'audit-log:view',
      name: '查看日志',
      group: '操作日志',
      description: '查看操作日志',
      configurableRoles: [],
      lockedRoles: ['LEADER'],
    },
  ],
}

describe('角色权限 API 契约', () => {
  beforeEach(() => {
    getMock.mockReset()
    putMock.mockReset()
  })

  it('从统一前缀下的 role-permissions 获取角色和权限定义', async () => {
    getMock.mockResolvedValue(response)

    await expect(getRolePermissions()).resolves.toBe(response)
    expect(getMock).toHaveBeenCalledWith('/role-permissions')
  })

  it('整体提交指定角色的权限、原因和版本，并返回更新后的角色', async () => {
    const updated = { ...leaderRole, permissions: ['audit-log:view'], version: 4 }
    putMock.mockResolvedValue(updated)
    const request = {
      permissions: ['stats:view', 'audit-log:view'],
      reason: '调整校级负责人权限',
      version: 3,
    }

    await expect(updateRolePermissions('LEADER', request)).resolves.toBe(updated)
    expect(putMock).toHaveBeenCalledWith('/role-permissions/LEADER', request)
  })
})
