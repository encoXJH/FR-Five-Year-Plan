import type { Role } from '@/types/enums'
import type { PermissionDefinition } from '@/types/system'

export const ROLE_ORDER: readonly Role[] = ['ADMIN', 'LEADER', 'SCHOOL_OWNER', 'UNIT_OWNER']

export type RolePermissionState = 'EDITABLE' | 'LOCKED' | 'UNAVAILABLE'

export function getRolePermissionState(
  role: Role,
  permission: PermissionDefinition,
): RolePermissionState {
  if (role === 'ADMIN' || permission.lockedRoles.includes(role)) return 'LOCKED'
  if (permission.configurableRoles.includes(role)) return 'EDITABLE'
  return 'UNAVAILABLE'
}

/** 只保留可编辑且已授权的项目，并强制补入核心锁定项。 */
export function selectRolePermissions(
  role: Role,
  definitions: PermissionDefinition[],
  grantedPermissions: string[],
): string[] {
  const granted = new Set(grantedPermissions)
  return definitions
    .filter((permission) => {
      const state = getRolePermissionState(role, permission)
      return state === 'LOCKED' || (state === 'EDITABLE' && granted.has(permission.code))
    })
    .map((permission) => permission.code)
}
