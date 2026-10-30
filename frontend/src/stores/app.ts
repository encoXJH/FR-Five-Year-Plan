import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export const useAppStore = defineStore('app', () => {
  const stored = sessionStorage.getItem('selected-plan-id')
  const selectedPlanId = ref<string>(stored || '')
  const sidebarCollapsed = ref(false)
  const planYears = ref<number[]>([])
  const currentYear = computed(() => new Date().getFullYear())
  function setPlan(id: string | number, startYear?: number, endYear?: number) {
    selectedPlanId.value = String(id)
    sessionStorage.setItem('selected-plan-id', selectedPlanId.value)
    if (startYear && endYear) planYears.value = Array.from({ length: endYear - startYear + 1 }, (_, index) => startYear + index)
  }
  function clearPlan() {
    selectedPlanId.value = ''
    planYears.value = []
    sessionStorage.removeItem('selected-plan-id')
  }
  function toggleSidebar() { sidebarCollapsed.value = !sidebarCollapsed.value }
  return { selectedPlanId, sidebarCollapsed, planYears, currentYear, setPlan, clearPlan, toggleSidebar }
})
