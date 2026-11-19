import type { Role } from '@/types/enums'

const ROLE_ORDER: readonly Role[] = ['ADMIN', 'LEADER', 'SCHOOL_OWNER', 'UNIT_OWNER', 'STAFF']

export const USER_CREATION_MATRIX: Readonly<Record<Role, readonly Role[]>> = {
  ADMIN: ['ADMIN', 'LEADER', 'SCHOOL_OWNER', 'UNIT_OWNER'],
  LEADER: ['LEADER', 'SCHOOL_OWNER', 'UNIT_OWNER'],
  SCHOOL_OWNER: ['SCHOOL_OWNER', 'UNIT_OWNER'],
  UNIT_OWNER: ['UNIT_OWNER', 'STAFF'],
  STAFF: [],
}

export function creatableRoles(creatorRoles: readonly Role[]): Role[] {
  return ROLE_ORDER.filter((targetRole) =>
    creatorRoles.some((creatorRole) => USER_CREATION_MATRIX[creatorRole].includes(targetRole)),
  )
}

/** 干事只能单独创建；选择其他角色时自动移除干事。 */
export function normalizeCreationRoles(
  selected: readonly Role[],
  previous: readonly Role[],
): Role[] {
  if (selected.includes('STAFF') && !previous.includes('STAFF')) return ['STAFF']
  return selected.filter((role) => role !== 'STAFF' || selected.length === 1)
}

export function creationNeedsOwnUnits(
  creatorRoles: readonly Role[],
  targetRoles: readonly Role[],
): boolean {
  return creatorRoles.includes('UNIT_OWNER')
    && (targetRoles.includes('UNIT_OWNER') || targetRoles.includes('STAFF'))
}

export function creationUnitIds(
  creatorRoles: readonly Role[],
  targetRoles: readonly Role[],
  selectedUnitIds: readonly string[],
  ownUnitIds: readonly string[],
): string[] {
  if (targetRoles.includes('STAFF')) return [...new Set(ownUnitIds)]
  if (!creationNeedsOwnUnits(creatorRoles, targetRoles)) return [...new Set(selectedUnitIds)]
  const own = new Set(ownUnitIds)
  return [...new Set(selectedUnitIds.filter((unitId) => own.has(unitId)))]
}

export function isTaskExecutorOnly(roles: readonly Role[]): boolean {
  return roles.length > 0 && roles.every((role) => role === 'UNIT_OWNER' || role === 'STAFF')
}

export function isStaffOnly(roles: readonly Role[]): boolean {
  return roles.length > 0 && roles.every((role) => role === 'STAFF')
}

export function homePathForRoles(roles: readonly Role[]): '/plans' | '/workbench' {
  return isTaskExecutorOnly(roles) ? '/workbench' : '/plans'
}
