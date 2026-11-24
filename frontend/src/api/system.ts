import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  DictItem,
  MajorGroup,
  MajorGroupSaveRequest,
  OrgDepartmentNode,
  OrgUnitAdmin,
  OrgUnitSaveRequest,
  RoleItem,
  RolePermissionResponse,
  RolePermissionSaveRequest,
  SetUserRolesRequest,
  SettingPatchItem,
  StaffPasswordResetRequest,
  StaffUser,
  StaffUserQuery,
  StaffUserUpdateRequest,
  SystemUserDeleteRequest,
  SystemSettings,
  SystemUser,
  SystemUserSaveRequest,
  UserImportConfirmRequest,
  UserImportConfirmResult,
  UserImportPreviewResult,
} from '@/types/system'
import type { Role } from '@/types/enums'

/** GET /org-departments/tree（system:manage；全校组织架构只读树） */
export function getOrgDepartmentTree(): Promise<OrgDepartmentNode[]> {
  return http.get('/org-departments/tree')
}

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

/** GET /users/staff（user:create + UNIT_OWNER；本学院全部纯干事） */
export function listStaffUsers(params: StaffUserQuery): Promise<PageResult<StaffUser>> {
  return http.get('/users/staff', params)
}

export function createUser(data: SystemUserSaveRequest): Promise<SystemUser> {
  return http.post('/users', data)
}

/** GET /users/import-template（用户批量导入模板，xlsx 文件流） */
export function downloadUserImportTemplate(): Promise<Blob> {
  return http.get('/users/import-template', undefined, { responseType: 'blob' })
}

/** POST /users/import-preview（multipart file，返回逐行校验结果） */
export function previewUserImport(file: File): Promise<UserImportPreviewResult> {
  const form = new FormData()
  form.append('file', file)
  return http.post('/users/import-preview', form)
}

/** POST /users/import-confirm（同一 importId 重试须复用 Idempotency-Key） */
export function confirmUserImport(
  data: UserImportConfirmRequest,
  idempotencyKey: string,
): Promise<UserImportConfirmResult> {
  return http.post('/users/import-confirm', data, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

/** PATCH /users/{userId}（修改 / 停用，带 version） */
export function updateUser(userId: string, data: SystemUserSaveRequest): Promise<SystemUser> {
  return http.patch(`/users/${userId}`, data)
}

/** PATCH /users/staff/{userId}（姓名、手机号、主学院或启停，带 version） */
export function updateStaffUser(userId: string, data: StaffUserUpdateRequest): Promise<StaffUser> {
  return http.patch(`/users/staff/${userId}`, data)
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

/** DELETE /users/{userId}（system:manage；逻辑删除，必须带 version 和删除原因） */
export function deleteUser(userId: string, data: SystemUserDeleteRequest): Promise<void> {
  return http.delete(`/users/${userId}`, { data })
}

/** POST /users/staff/{userId}/password-reset（带 version，重置后吊销该用户全部 refreshToken） */
export function resetStaffUserPassword(userId: string, data: StaffPasswordResetRequest): Promise<void> {
  return http.post(`/users/staff/${userId}/password-reset`, data)
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

/** GET /role-permissions（角色权限定义及四类角色的当前授权） */
export function getRolePermissions(): Promise<RolePermissionResponse> {
  return http.get('/role-permissions')
}

/** PUT /role-permissions/{role}（整体替换该角色权限，带原因和乐观锁版本） */
export function updateRolePermissions(
  role: Role,
  data: RolePermissionSaveRequest,
): Promise<RoleItem> {
  return http.put(`/role-permissions/${role}`, data)
}
