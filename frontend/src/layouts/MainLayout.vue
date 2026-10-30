<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Bell, Calendar, DataAnalysis, Document, Files, Grid, List, OfficeBuilding, Operation, RefreshRight, SetUp, Setting, SwitchButton, User, UserFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import { planningApi } from '@/api'
import type { ConstructionPlan, RoleCode } from '@/types/api'
import { roleLabels } from '@/utils/dictionaries'

interface MenuItem { label: string; path: string; icon: Component; roles: RoleCode[]; group?: string }
const all: RoleCode[] = ['ADMIN', 'SCHOOL_LEADER', 'SCHOOL_OWNER', 'COLLEGE_OWNER']
const business: RoleCode[] = ['SCHOOL_LEADER', 'SCHOOL_OWNER', 'COLLEGE_OWNER']
const school: RoleCode[] = ['SCHOOL_LEADER', 'SCHOOL_OWNER']
const menus: MenuItem[] = [
  { label: '待办首页', path: '/dashboard', icon: Grid, roles: all },
  { label: '数据看板', path: '/dashboard/leader', icon: DataAnalysis, roles: ['SCHOOL_LEADER'] },
  { label: '建设计划', path: '/planning/plans', icon: Calendar, roles: school, group: '计划与指标' },
  { label: 'Excel 导入', path: '/planning/imports', icon: Document, roles: ['SCHOOL_OWNER'] },
  { label: '学院任务', path: '/execution/tasks', icon: Files, roles: business, group: '执行与认定' },
  { label: '校级审核', path: '/execution/reviews', icon: Operation, roles: school },
  { label: '分层甘特图', path: '/gantt', icon: DataAnalysis, roles: business },
  { label: '年度顺延与补位', path: '/adjustments/carryovers', icon: RefreshRight, roles: business, group: '动态调整' },
  { label: '跨院划转', path: '/adjustments/transfers', icon: SwitchButton, roles: business },
  { label: '操作日志', path: '/logs', icon: List, roles: all, group: '治理支撑' },
  { label: '组织与别名', path: '/admin/organizations', icon: OfficeBuilding, roles: ['ADMIN'], group: '系统管理' },
  { label: '用户与角色', path: '/admin/users', icon: User, roles: ['ADMIN'] },
  { label: '项目成员', path: '/admin/members', icon: UserFilled, roles: ['ADMIN'] },
  { label: '业务字典', path: '/admin/dictionaries', icon: SetUp, roles: ['ADMIN'] },
  { label: '运行设置', path: '/admin/settings', icon: Setting, roles: ['ADMIN'] },
]
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const app = useAppStore()
const plans = ref<ConstructionPlan[]>([])
const visibleMenus = computed(() => auth.roles.includes('ADMIN') ? menus : menus.filter((item) => item.roles.some((role) => auth.roles.includes(role))))
const activePath = computed(() => {
  if (route.path.startsWith('/planning/indicators')) return '/planning/indicators'
  if (route.path.startsWith('/planning/imports')) return '/planning/imports'
  if (route.path.startsWith('/execution/tasks') || route.path.includes('/milestones/')) return '/execution/tasks'
  if (route.path.startsWith('/execution/reviews')) return '/execution/reviews'
  if (route.path.startsWith('/adjustments/transfers')) return '/adjustments/transfers'
  return route.path
})
const roleText = computed(() => auth.roles.map((role) => roleLabels[role]).join(' / ') || '未分配角色')
const showBack = computed(() => !['dashboard', 'forbidden'].includes(String(route.name)))
const fallbackRoute = computed(() => {
  const name = String(route.name)
  if (name === 'decomposition') return `/planning/indicators/${route.params.id}`
  if (name === 'indicator-detail') return '/planning/indicators'
  if (name === 'import-detail') return '/planning/imports'
  if (name === 'review-detail') return '/execution/reviews'
  if (name === 'transfer-detail') return '/adjustments/transfers'
  if (name === 'task-detail' || name === 'submission-editor') return '/execution/tasks'
  return '/dashboard'
})
function goBack() {
  const previous = String(window.history.state?.back || '')
  if (previous && previous !== '/login') router.back()
  else router.replace(fallbackRoute.value)
}

async function loadPlans() {
  if (!auth.roles.some((role) => business.includes(role))) return
  try {
    const result = await planningApi.plans({ page_size: 100 })
    plans.value = result.items
    const current = plans.value.find((item) => String(item.id) === app.selectedPlanId)
    if (current) app.setPlan(current.id, current.start_year, current.end_year)
    else if (app.selectedPlanId) app.clearPlan()
  } catch { plans.value = [] }
}
function changePlan(id: string) {
  if (!id) { app.clearPlan(); return }
  const plan = plans.value.find((item) => String(item.id) === id)
  if (plan) app.setPlan(plan.id, plan.start_year, plan.end_year)
}
async function logout() {
  await auth.logout()
  ElMessage.success('已安全退出')
  router.replace('/login')
}
onMounted(() => {
  loadPlans()
  window.addEventListener('plans-changed', loadPlans)
})
onBeforeUnmount(() => window.removeEventListener('plans-changed', loadPlans))
</script>

<template>
  <div class="app-shell" :class="{ 'is-collapsed': app.sidebarCollapsed }">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">五</div>
        <div v-if="!app.sidebarCollapsed" class="brand-copy"><strong>五年建设</strong><span>任务管理平台</span></div>
      </div>
      <el-scrollbar class="menu-scroll">
        <nav class="main-menu">
          <template v-for="item in visibleMenus" :key="item.path">
            <div v-if="item.group && !app.sidebarCollapsed" class="menu-group">{{ item.group }}</div>
            <el-tooltip :content="item.label" placement="right" :disabled="!app.sidebarCollapsed">
              <router-link :to="item.path" class="menu-item" :class="{ active: activePath === item.path }">
                <el-icon><component :is="item.icon" /></el-icon><span v-if="!app.sidebarCollapsed">{{ item.label }}</span>
              </router-link>
            </el-tooltip>
          </template>
        </nav>
      </el-scrollbar>
      <button class="collapse-button" @click="app.toggleSidebar"><el-icon><Operation /></el-icon><span v-if="!app.sidebarCollapsed">收起导航</span></button>
    </aside>
    <section class="workspace">
      <header class="topbar">
        <div class="topbar-heading"><el-button v-if="showBack" text circle class="global-back" aria-label="返回上一页" title="返回上一页" @click="goBack"><el-icon :size="19"><ArrowLeft /></el-icon></el-button><div><div class="page-title">{{ route.meta.title }}</div><div class="page-crumb">五年建设任务管理 / {{ route.meta.title }}</div></div></div>
        <div class="top-actions">
          <el-select v-if="plans.length" :model-value="app.selectedPlanId" placeholder="选择当前计划" clearable filterable class="plan-selector" @change="changePlan">
            <el-option v-for="plan in plans" :key="plan.id" :value="String(plan.id)" :label="plan.plan_name"><span>{{ plan.plan_name }}</span><span class="option-note">{{ plan.start_year }}—{{ plan.end_year }}</span></el-option>
          </el-select>
          <el-badge :value="0" :hidden="true"><el-button text circle @click="router.push('/dashboard')"><el-icon :size="19"><Bell /></el-icon></el-button></el-badge>
          <el-dropdown>
            <button class="user-button"><span class="avatar">{{ auth.user?.user_name?.slice(0, 1) || '用' }}</span><span><strong>{{ auth.user?.user_name }}</strong><small>{{ roleText }}</small></span></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item disabled>{{ auth.user?.primary_org_name || '未设置主属组织' }}</el-dropdown-item><el-dropdown-item divided @click="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>
      <main class="content-area"><router-view /></main>
    </section>
  </div>
</template>

<style scoped>
.topbar-heading { display: flex; align-items: center; gap: 10px; min-width: 0; }
.global-back { flex: 0 0 auto; margin-left: -8px; color: #526078; }
.global-back:hover { color: var(--el-color-primary); background: #eef4ff; }
</style>
