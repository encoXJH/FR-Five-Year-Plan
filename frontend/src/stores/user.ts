import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import * as dictApi from '@/api/dict'
import type { CurrentUser, DataScope, LoginRequest } from '@/types/auth'
import type { EnumDict, Role } from '@/types/enums'
import { clearTokens, getAccessToken, getRefreshToken, saveTokens } from '@/utils/request'
import type { ClientEnv } from '@/utils/feishu'

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
    async login(payload: LoginRequest) {
      const data = await authApi.login(payload)
      this.setTokens(data.accessToken, data.refreshToken)
      this.userInfo = data.user
      this.roles = data.user.roles
      await this.fetchMe()
      this.fetchEnums().catch(() => undefined)
    },
    /** 飞书免登：code 换 Token 后的收尾与账号密码登录一致 */
    async loginFeishu(code: string, clientType: ClientEnv) {
      const data = await authApi.feishuLogin(code, clientType)
      this.setTokens(data.accessToken, data.refreshToken)
      this.userInfo = data.user
      this.roles = data.user.roles
      await this.fetchMe()
      this.fetchEnums().catch(() => undefined)
    },
    async logout() {
      try {
        await authApi.logout()
      } catch {
        // 服务端注销失败不阻塞本地退出
      }
      clearTokens()
      this.$reset()
    },
    async fetchMe() {
      const me = await authApi.fetchMe()
      this.userInfo = me.user
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
