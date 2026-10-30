import { commandConfig, get, getList, patch, pick, post, put, request, withLock } from './client'
import type { DictionaryItem, ID, ListResult, LogMeta, OperationLog, OrgAlias, Organization, PageQuery, PlatformSetting, ProjectMember, UserAccount } from '@/types/api'
import { toBackendRoleCode } from '@/utils/permissions'

type ConfigRecord = { id: ID; config_key: string; config_value: string | number | boolean | Record<string, unknown>; description?: string; lock_version?: number }
function settingPayload(row: ConfigRecord): PlatformSetting {
  return { id: row.id, setting_key: row.config_key, setting_value: row.config_value, description: row.description, value_type: typeof row.config_value === 'number' ? 'NUMBER' : typeof row.config_value === 'boolean' ? 'BOOLEAN' : typeof row.config_value === 'object' ? 'JSON' : 'STRING', lock_version: row.lock_version }
}

export const governanceApi = {
  logs: (params?: PageQuery) => getList<OperationLog>('/logs', params),
  logMeta: () => get<LogMeta>('/logs/meta'),
  exportLogs: (params: PageQuery) => request<Blob>({ url: '/logs/export', method: 'GET', params, responseType: 'blob' }),
  organizations: (params?: PageQuery) => getList<Organization>('/organizations', params),
  createOrganization: (data: Partial<Organization>) => post<Organization>('/organizations', pick(data, ['org_code', 'org_name', 'org_short_name', 'org_type', 'parent_id', 'sort_no']), commandConfig()),
  updateOrganization: (id: ID, data: Partial<Organization>) => patch<Organization>(`/organizations/${id}`, withLock(pick(data, ['org_name', 'org_short_name', 'org_type', 'parent_id', 'sort_no', 'status']), data.lock_version), commandConfig()),
  aliases: (params?: PageQuery) => getList<OrgAlias>('/aliases', params),
  createAlias: (data: Partial<OrgAlias>) => post<OrgAlias>(`/organizations/${data.org_id}/aliases`, pick(data, ['alias_name', 'source_name']), commandConfig()),
  updateAlias: (id: ID, data: Partial<OrgAlias>) => post<OrgAlias>(`/aliases/${id}/confirm`, pick(data, ['org_id', 'confirm_status']), commandConfig()),
  users: (params?: PageQuery) => getList<UserAccount>('/users', { ...params, role: params?.role ? toBackendRoleCode(String(params.role)) : undefined }),
  createUser: (data: Partial<UserAccount> & { password?: string; role_codes?: string[] }) => post<UserAccount>('/users', { ...pick(data, ['account_name', 'user_name', 'primary_org_id', 'password']), role_codes: (data.role_codes || data.roles || []).map(toBackendRoleCode) }, commandConfig()),
  updateUser: (id: ID, data: Partial<UserAccount> & { reason?: string; handover_user_id?: ID }) => {
    if (data.status === 'DISABLED') return post<UserAccount>(`/users/${id}/disable`, withLock(pick(data, ['handover_user_id', 'reason']), data.lock_version), commandConfig())
    return patch<UserAccount>(`/users/${id}`, withLock(pick(data, ['user_name', 'primary_org_id', 'status']), data.lock_version), commandConfig())
  },
  assignRoles: async (id: ID, roles: string[]) => {
    const users = await getList<UserAccount>('/users', { page_size: 500 })
    const target = users.items.find((item) => String(item.id) === String(id))
    const current: string[] = target?.roles || []
    const backendRoles = roles.map(toBackendRoleCode)
    await Promise.all(backendRoles.filter((role) => !current.includes(role)).map((role) => post(`/users/${id}/roles`, { role_code: role }, commandConfig())))
    await Promise.all(current.filter((role) => !backendRoles.includes(role)).map((role) => post(`/users/${id}/roles/${role}/revoke`, {}, commandConfig())))
    return { ...target, roles } as UserAccount
  },
  members: (params?: PageQuery) => getList<ProjectMember>('/project-members', params),
  createMember: (data: Partial<ProjectMember>) => post<ProjectMember>('/project-members', pick(data, ['plan_id', 'user_id', 'org_id', 'member_role', 'is_primary', 'effective_to']), commandConfig()),
  updateMember: (id: ID, data: Partial<ProjectMember> & { reason?: string }) => post<ProjectMember>(`/project-members/${id}/handover`, withLock({ new_user_id: data.user_id, reason: data.reason || '负责人交接' }, data.lock_version), commandConfig()),
  dictionaries: async (dictType: string, params?: PageQuery): Promise<ListResult<DictionaryItem>> => {
    if (dictType === 'construction-years') {
      const result = await get<{ items: number[]; total: number }>('/construction-years')
      return { items: result.items.map((year) => ({ id: year, dict_code: String(year), dict_name: `${year} 年`, code: String(year), name: `${year} 年`, status: 'ENABLED', is_builtin: 1 })), total: result.total }
    }
    if (dictType === 'material-formats') {
      const result = await getList<ConfigRecord>('/configs', params)
      const items = result.items.filter((item) => /material|file|video|preview/i.test(item.config_key)).map((item) => ({ ...settingPayload(item), dict_code: item.config_key, dict_name: item.description || item.config_key, code: item.config_key, name: item.description || item.config_key, status: 'ENABLED' } as DictionaryItem))
      return { items, total: items.length }
    }
    return getList<DictionaryItem>(`/dictionaries/${dictType}`, params)
  },
  createDictionary: (dictType: string, data: Partial<DictionaryItem>) => post<DictionaryItem>(`/dictionaries/${dictType}`, data, commandConfig()),
  updateDictionary: (dictType: string, id: ID, data: Partial<DictionaryItem>) => patch<DictionaryItem>(`/dictionaries/${dictType}/${id}`, pick(data, ['description', 'sort_no', 'status', 'display_suffix']), commandConfig()),
  settings: async (params?: PageQuery) => {
    const result = await getList<ConfigRecord>('/configs', params)
    return { items: result.items.map(settingPayload), total: result.total }
  },
  updateSetting: (key: string, data: Partial<PlatformSetting>) => put<PlatformSetting>(`/configs/${key}`, withLock({ config_value: data.setting_value, description: data.description }, data.lock_version), commandConfig()),
}
