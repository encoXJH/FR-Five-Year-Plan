import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api'
import type { RoleCode, SessionUser } from '@/types/api'
import { normalizeRole } from '@/utils/permissions'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<SessionUser | null>(null)
  const initialized = ref(false)
  const roles = computed<RoleCode[]>(() => user.value?.roles || [])
  const primaryRole = computed<RoleCode | null>(() => roles.value[0] || null)
  const isLoggedIn = computed(() => Boolean(user.value))

  function normalizeUser(value: SessionUser): SessionUser {
    const rawRoles = (value.roles?.length ? value.roles : value.role_codes || []) as unknown as Array<string | { role_code?: string }>
    const roles = rawRoles.map((role) => normalizeRole(typeof role === 'string' ? role : role.role_code || '')).filter((role): role is RoleCode => Boolean(role))
    return { ...value, roles: [...new Set(roles)] }
  }

  async function fetchMe(force = false) {
    if (initialized.value && !force) return user.value
    try {
      user.value = normalizeUser(await authApi.me())
    } catch {
      user.value = null
    } finally {
      initialized.value = true
    }
    return user.value
  }

  async function login(account_name: string, password: string) {
    user.value = normalizeUser(await authApi.login(account_name, password))
    initialized.value = true
  }

  async function logout() {
    try { await authApi.logout() } finally {
      user.value = null
      initialized.value = true
      sessionStorage.removeItem('selected-plan-id')
    }
  }

  function clear() {
    user.value = null
    initialized.value = true
  }

  return { user, initialized, roles, primaryRole, isLoggedIn, fetchMe, login, logout, clear }
})
