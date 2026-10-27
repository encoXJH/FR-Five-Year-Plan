import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  DictItem,
  MajorGroup,
  MajorGroupSaveRequest,
  OrgUnitAdmin,
  OrgUnitSaveRequest,
  SetUserRolesRequest,
  SettingPatchItem,
  SystemSettings,
  SystemUser,
  SystemUserSaveRequest,
} from '@/types/system'

/** GET /org-units（已登录；含 isEnabled 等管理字段） */
export function listOrgUnitsAdmin(): Promise<OrgUnitAdmin[]> {
  return http.get('/org-units')
}

/** POST /org-units（system:manage） */
export function createOrgUnit(data: OrgUnitSaveRequest): Promise<OrgUnitAdmin> {
  return http.post('/org-units', data)
}

/** PATCH /org-units/{unitId}（可改 name / sort / isEnabled，带 version） */
export function updateOrgUnit(unitId: string, data: OrgUnitSaveRequest): Promise<OrgUnitAdmin> {
  return http.patch(`/org-units/${unitId}`, data)
}

/** GET /major-groups */
export function listMajorGroups(): Promise<MajorGroup[]> {
  return http.get('/major-groups')
}

export function createMajorGroup(data: MajorGroupSaveRequest): Promise<MajorGroup> {
  return http.post('/major-groups', data)
}

/** PATCH /major-groups/{majorGroupId}（可改 name / unitId / ownerId / isEnabled，带 version） */
export function updateMajorGroup(
  majorGroupId: string,
  data: MajorGroupSaveRequest,
): Promise<MajorGroup> {
  return http.patch(`/major-groups/${majorGroupId}`, data)
}

export type SystemUserQuery = {
  unitId?: string
  role?: string
  isEnabled?: boolean
  keyword?: string
  page?: number
  pageSize?: number
}

/** GET /users（system:manage；分页） */
export function listUsers(params: SystemUserQuery): Promise<PageResult<SystemUser>> {
  return http.get('/users', params)
}

export function createUser(data: SystemUserSaveRequest): Promise<SystemUser> {
  return http.post('/users', data)
}

/** PATCH /users/{userId}（修改 / 停用，带 version） */
export function updateUser(userId: string, data: SystemUserSaveRequest): Promise<SystemUser> {
  return http.patch(`/users/${userId}`, data)
}

/** PUT /users/{userId}/roles（敏感操作，必须带 reason，API 第 10 章；响应为 { user }） */
export function setUserRoles(
  userId: string,
  data: SetUserRolesRequest,
): Promise<{ user: SystemUser }> {
  return http.put(`/users/${userId}/roles`, data)
}

/** POST /users/{userId}/password-reset（API 15.10；重置后吊销该用户全部 refreshToken） */
export function resetUserPassword(userId: string, password: string): Promise<void> {
  return http.post(`/users/${userId}/password-reset`, { password })
}

/** GET /dicts/{dictType}（已登录；只返回启用项） */
export function getDictItems(dictType: string): Promise<DictItem[]> {
  return http.get(`/dicts/${dictType}`)
}

/** PUT /dicts/{dictType}（system:manage；整体替换，按 code 匹配，未出现的既有编码逻辑删除） */
export function saveDictItems(dictType: string, items: DictItem[]): Promise<DictItem[]> {
  return http.put(`/dicts/${dictType}`, { items })
}

/** GET /settings（system:manage） */
export function getSettings(): Promise<SystemSettings> {
  return http.get('/settings')
}

/** PATCH /settings（system:manage；逐项 key + value + version，任一失败整体不落库） */
export function updateSettings(items: SettingPatchItem[]): Promise<SystemSettings> {
  return http.patch('/settings', { items })
}
