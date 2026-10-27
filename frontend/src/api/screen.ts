import { http } from '@/utils/request'
import type { ScreenData } from '@/types/stats'

/** 大屏聚合数据（一次请求拿到全部数据，服务端缓存 1–5 分钟；screen:view） */
export function getScreenData(planId: string, year?: number): Promise<ScreenData> {
  return http.get(`/screens/${planId}`, { year })
}
