import { defineStore } from 'pinia'
import { getUnreadNotificationCount } from '@/api/todo'

interface AppState {
  unreadCount: number
  lastPlanId: string | null
}

export const useAppStore = defineStore('app', {
  state: (): AppState => ({
    unreadCount: 0,
    lastPlanId: null,
  }),
  actions: {
    async refreshUnread(): Promise<void> {
      try {
        this.unreadCount = await getUnreadNotificationCount()
      } catch {
        // 角标失败不阻塞主流程
      }
    },
    setUnread(count: number) {
      this.unreadCount = Math.max(0, count)
    },
    setLastPlan(planId: string | null) {
      this.lastPlanId = planId
    },
  },
})
