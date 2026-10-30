import { get, post } from './client'
import type { SessionUser } from '@/types/api'

export const authApi = {
  login: (account_name: string, password: string) => post<SessionUser>('/auth/login', { account_name, password }),
  logout: () => post<void>('/auth/logout'),
  me: () => get<SessionUser>('/auth/me'),
}
