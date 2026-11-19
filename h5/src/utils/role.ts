import type { Role } from '@/types/enums'

const TASK_EXECUTOR_ROLES: readonly Role[] = ['UNIT_OWNER', 'STAFF']

export function hasOnlyRoles(roles: readonly Role[], allowedRoles: readonly Role[]): boolean {
  return roles.length > 0 && roles.every((role) => allowedRoles.includes(role))
}

export function isStaffOnly(roles: readonly Role[]): boolean {
  return hasOnlyRoles(roles, ['STAFF'])
}

export function usesTaskWorkbenchHome(roles: readonly Role[]): boolean {
  return hasOnlyRoles(roles, TASK_EXECUTOR_ROLES)
}

export function homePathForRoles(roles: readonly Role[]): string {
  return usesTaskWorkbenchHome(roles) ? '/workbench' : '/plans'
}

export function canPerformOwnerOnlyActions(roles: readonly Role[]): boolean {
  return !isStaffOnly(roles)
}
