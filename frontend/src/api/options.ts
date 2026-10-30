import { getList } from './client'
import type { ListResult, Organization, PageQuery, ProjectMember, RoleCode, UserAccount } from '@/types/api'

export const optionsApi = {
  users: async (params?: PageQuery): Promise<ListResult<UserAccount>> => {
    if (params?.role === 'COLLEGE_OWNER' && params.plan_id) {
      const result = await getList<ProjectMember>('/project-members', { plan_id: params.plan_id })
      const items = result.items.filter((item) => item.member_role === 'COLLEGE_OWNER' && !item.effective_to).map((item) => ({ id: item.user_id, account_name: item.account_name || String(item.user_id), user_name: item.user_name || `用户 #${item.user_id}`, primary_org_id: item.org_id || '', primary_org_name: item.org_name, status: 'ENABLED', roles: ['COLLEGE_OWNER' as RoleCode] }))
      return { items, total: items.length }
    }
    return getList<UserAccount>('/users', params)
  },
  organizations: (params?: PageQuery) => getList<Organization>('/organizations', params),
}
