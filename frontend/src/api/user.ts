import { http } from '@/utils/request'
import type { UserOption } from '@/types/user'

export function getUserOptions(): Promise<UserOption[]> {
  return http.get('/users/options')
}
