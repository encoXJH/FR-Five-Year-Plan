import type { OrgUnitType, Role } from './enums'

/** 执行单元（GET /org-units；API 15.10，编码与类型创建后不可改） */
export interface OrgUnitAdmin {
  id: string
  code: string
  name: string
  type: OrgUnitType | string | null
  sort: number | null
  isEnabled: boolean
  isBAssignable: boolean
  version: number
}

export interface OrgUnitSaveRequest {
  code?: string
  name?: string
  type?: string | null
  sort?: number | null
  isEnabled?: boolean
  isBAssignable?: boolean
  version?: number
}

/** 专业群（GET /major-groups） */
export interface MajorGroup {
  id: string
  name: string
  unitId: string | null
  unitName: string | null
  ownerId: string | null
  ownerName: string | null
  isEnabled: boolean
  version: number
}

export interface MajorGroupSaveRequest {
  name?: string
  unitId?: string | null
  ownerId?: string | null
  isEnabled?: boolean
  version?: number
}

/** 用户（GET /users，系统管理；手机号已脱敏） */
export interface SystemUser {
  id: string
  username: string
  name: string
  phone?: string | null
  email?: string | null
  unitId: string | null
  unitName: string | null
  roles: Role[]
  isEnabled: boolean
  lastLoginAt?: string | null
  version: number
}

export interface SystemUserSaveRequest {
  name?: string
  username?: string
  password?: string
  phone?: string | null
  email?: string | null
  unitId?: string | null
  roles?: Role[]
  isEnabled?: boolean
  version?: number
}

/** PUT /users/{userId}/roles 请求体（敏感操作必须带 reason，API 第 10 章） */
export interface SetUserRolesRequest {
  roles: Role[]
  reason: string
  version?: number
}

/** 自定义字典项（GET /dicts/{dictType}；PUT 整体替换，按 code 匹配） */
export interface DictItem {
  /** 已存在项的 id（新增不传） */
  id?: string
  code: string
  label: string
  sort?: number | null
  isEnabled?: boolean
}

/** 系统参数项（GET /settings；逐项乐观锁） */
export interface SettingItem {
  id: string
  key: string
  value: string
  valueType: 'NUMBER' | 'BOOLEAN' | 'JSON' | 'STRING' | string
  description: string | null
  version: number
}

/** GET /settings 响应 */
export interface SystemSettings {
  items: SettingItem[]
}

/** PATCH /settings 请求项（key + value + version） */
export interface SettingPatchItem {
  key: string
  value: string
  version: number
}
