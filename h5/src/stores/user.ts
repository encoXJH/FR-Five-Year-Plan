import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import * as dictApi from '@/api/dict'
import type {
  CurrentUser,
  DataScope,
  FeishuBindingChallenge,
  FeishuBindRequest,
  LoginRequest,
  LoginResponse,
} from '@/types/auth'
import type { EnumDict, Role } from '@/types/enums'
import { clearTokens, getAccessToken, getRefreshToken, saveTokens } from '@/utils/request'
import { clearCurrentPlan } from '@/utils/currentPlan'
import type { ClientEnv } from '@/utils/feishu'

/** 后端 /auth/me 返回 unitId / unitName（无 unit 对象），统一归一化供页面显示与数据范围判断 */
function normalizeUser(user: CurrentUser): CurrentUser {
  if (user.unit) return user
  return {
    ...user,
    unit: user.unitId
      ? { id: user.unitId, code: '', name: user.unitName ?? '' }
      : null,
  }
}

interface UserState {
  accessToken: string | null
  refreshToken: string | null
  userInfo: CurrentUser | null
  roles: Role[]
  permissions: string[]
  dataScope: DataScope | null
  enums: EnumDict
}

export const useUserStore = defineStore('user', {
  state: (): UserState => ({
    accessToken: getAccessToken(),
    refreshToken: getRefreshToken(),
    userInfo: null,
    roles: [],
    permissions: [],
    dataScope: null,
    enums: {},
  }),
  getters: {
    isLoggedIn: (state) => state.accessToken !== null,
    isUnitOwnerOnly: (state) => state.roles.length > 0
      && state.roles.every((role) => role === 'UNIT_OWNER'),
    homePath(): string {
      return this.isUnitOwnerOnly ? '/workbench' : '/plans'
    },
    /** 任一权限码命中即视为有权限（菜单、按钮过滤用） */
    can: (state) => {
      return (...codes: string[]) => codes.some((code) => state.permissions.includes(code))
    },
  },
  actions: {
    setTokens(accessToken: string, refreshToken: string) {
      this.accessToken = accessToken
      this.refreshToken = refreshToken
      saveTokens(accessToken, refreshToken)
    },
    async completeLogin(data: LoginResponse) {
      this.setTokens(data.accessToken, data.refreshToken)
      this.userInfo = normalizeUser(data.user)
      this.roles = data.user.roles
      await this.fetchMe()
      this.fetchEnums().catch(() => undefined)
    },
    async login(payload: LoginRequest) {
      const data = await authApi.login(payload)
      await this.completeLogin(data)
    },
    /** 飞书免登：已绑定直接登录；首次登录只返回绑定挑战，不写 Token */
    async loginFeishu(code: string, clientType: ClientEnv): Promise<FeishuBindingChallenge | null> {
      const data = await authApi.feishuLogin(code, clientType)
      if (data.bindRequired) return data
      await this.completeLogin(data)
      return null
    },
    /** 首次绑定经校内核验成功后，复用正常登录收尾 */
    async bindFeishu(payload: FeishuBindRequest, clientType: ClientEnv): Promise<void> {
      const data = await authApi.feishuBind(payload, clientType)
      await this.completeLogin(data)
    },
    async logout() {
      try {
        await authApi.logout()
      } catch {
        // 服务端注销失败不阻塞本地退出
      }
      clearTokens()
      clearCurrentPlan()
      this.$reset()
    },
    async fetchMe() {
      const me = await authApi.fetchMe()
      this.userInfo = normalizeUser(me.user)
      this.roles = me.roles
      this.permissions = me.permissions
      this.dataScope = me.dataScope
    },
    async refresh() {
      if (!this.refreshToken) {
        throw new Error('refresh token missing')
      }
      const data = await authApi.refresh({ refreshToken: this.refreshToken })
      this.setTokens(data.accessToken, data.refreshToken)
    },
    async fetchEnums() {
      this.enums = await dictApi.fetchEnums()
    },
  },
})
