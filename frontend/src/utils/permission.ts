import type { Directive } from 'vue'
import { useUserStore } from '@/stores/user'

/**
 * 按钮级权限指令（界面设计规范 6.3）：无权限时不渲染（从 DOM 移除）。
 * 用法：v-permission="'allocation:edit'" 或 v-permission="['a', 'b']"（任一命中即可）。
 * 注意：只能表达「权限」判断；叠加状态条件时仍用 v-if。
 */
export const vPermission: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const userStore = useUserStore()
    const codes = Array.isArray(binding.value) ? binding.value : [binding.value]
    if (codes.length > 0 && !userStore.can(...codes)) {
      el.parentNode?.removeChild(el)
    }
  },
}
