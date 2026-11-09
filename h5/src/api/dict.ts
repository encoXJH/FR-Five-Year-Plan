import { http } from '@/utils/request'
import type { EnumDict } from '@/types/enums'

export function fetchEnums(): Promise<EnumDict> {
  return http.get('/dicts/enums')
}
