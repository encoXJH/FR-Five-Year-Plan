import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', hideNav: true, hideTabbar: true },
  },
  {
    path: '/login/feishu-callback',
    name: 'FeishuCallback',
    component: () => import('@/views/login/FeishuCallback.vue'),
    meta: { title: '飞书登录', hideNav: true, hideTabbar: true },
  },
  {
    path: '/',
    component: () => import('@/components/Layout/index.vue'),
    children: [
      { path: '', redirect: '/plans' },
      {
        path: 'plans',
        name: 'Plans',
        component: () => import('@/views/plans/index.vue'),
        meta: { title: '首页', hideForRolesOnly: ['UNIT_OWNER'] },
      },
      {
        path: 'plans/:planId',
        name: 'PlanOverview',
        component: () => import('@/views/plans/overview.vue'),
        meta: { title: '计划概览' },
      },
      {
        path: 'plans/:planId/indicators',
        name: 'PlanIndicators',
        component: () => import('@/views/indicators/index.vue'),
        meta: { title: '指标总表', hideForRolesOnly: ['UNIT_OWNER'] },
      },
      {
        path: 'plans/:planId/gantt',
        redirect: (to) => `/plans/${to.params.planId}/indicators`,
      },
      {
        path: 'plans/:planId/indicator-progress',
        name: 'IndicatorProgress',
        component: () => import('@/views/indicators/progress.vue'),
        meta: { title: '指标进度', hideForRolesOnly: ['UNIT_OWNER'] },
      },
      {
        path: 'indicators',
        name: 'Indicators',
        component: () => import('@/views/indicators/index.vue'),
        meta: { title: '指标总表', hideForRolesOnly: ['UNIT_OWNER'] },
      },
      {
        path: 'indicators/:id',
        name: 'IndicatorDetail',
        component: () => import('@/views/indicators/detail.vue'),
        meta: { title: '指标详情' },
      },
      {
        path: 'plans/:planId/allocation',
        name: 'PlanAllocation',
        component: () => import('@/views/allocation/index.vue'),
        meta: { title: '分解下发', permissions: ['allocation:edit'] },
      },
      {
        path: 'allocation',
        name: 'Allocation',
        component: () => import('@/views/allocation/index.vue'),
        meta: { title: '分解下发', permissions: ['allocation:edit'] },
      },
      {
        path: 'templates',
        name: 'Templates',
        component: () => import('@/views/templates/index.vue'),
        meta: { title: '模板管理', permissions: ['plan:manage'] },
      },
      {
        path: 'workbench',
        name: 'Workbench',
        component: () => import('@/views/workbench/index.vue'),
        meta: { title: '学院工作台' },
      },
      {
        path: 'tasks/:taskId/submit',
        name: 'TaskSubmit',
        component: () => import('@/views/submission/index.vue'),
        meta: { title: '任务填报', permissions: ['submission:submit'], hideTabbar: true },
      },
      {
        path: 'materials',
        name: 'Materials',
        component: () => import('@/views/materials/index.vue'),
        meta: { title: '材料库', permissions: ['material:view'] },
      },
      {
        path: 'reviews',
        name: 'Reviews',
        component: () => import('@/views/review/index.vue'),
        meta: { title: '审核中心', permissions: ['submission:review'] },
      },
      {
        path: 'adjustments',
        name: 'Adjustments',
        component: () => import('@/views/adjustment/index.vue'),
        meta: { title: '调整申请与审批', permissions: ['adjustment:apply', 'adjustment:decide'] },
      },
      {
        path: 'adjustments/apply',
        name: 'AdjustmentApply',
        component: () => import('@/views/adjustment/apply.vue'),
        meta: { title: '发起调整申请', permissions: ['adjustment:apply'], hideTabbar: true },
      },
      {
        path: 'stats',
        name: 'Stats',
        component: () => import('@/views/stats/index.vue'),
        meta: { title: '统计报表', permissions: ['stats:view'] },
      },
      {
        path: 'logs',
        name: 'AuditLogs',
        component: () => import('@/views/logs/index.vue'),
        meta: { title: '操作日志', permissions: ['audit-log:view'] },
      },
      {
        path: 'todos',
        name: 'Todos',
        component: () => import('@/views/todos/index.vue'),
        meta: { title: '待办与消息' },
      },
      {
        path: 'system',
        name: 'System',
        component: () => import('@/views/system/index.vue'),
        meta: { title: '系统管理', permissions: ['system:manage'] },
      },
      {
        path: 'system/:tab',
        name: 'SystemPanel',
        component: () => import('@/views/system/detail.vue'),
        meta: { title: '系统管理', permissions: ['system:manage'] },
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/profile/index.vue'),
        meta: { title: '我的' },
      },
      {
        path: '403',
        name: 'Forbidden',
        component: () => import('@/views/error/Forbidden.vue'),
        meta: { title: '暂无访问权限' },
      },
    ],
  },
  {
    path: '/screen/:planId?',
    name: 'Screen',
    component: () => import('@/views/screen/index.vue'),
    meta: { title: '可视化大屏', permissions: ['screen:view'], hideNav: true, hideTabbar: true },
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const userStore = useUserStore()

  if (to.path === '/login' || to.path === '/login/feishu-callback') {
    return userStore.isLoggedIn ? userStore.homePath : true
  }
  const hasBadIdParam = Object.entries(to.params).some(([key, value]) => {
    if (!key.toLowerCase().endsWith('id')) return false
    const first = Array.isArray(value) ? value[0] : value
    return typeof first !== 'string' || !/^\d+$/.test(first)
  })
  if (hasBadIdParam) {
    return userStore.homePath
  }
  if (!userStore.isLoggedIn) {
    return {
      path: '/login',
      query: to.fullPath === '/' ? undefined : { redirect: to.fullPath },
    }
  }
  if (!userStore.userInfo) {
    try {
      await userStore.fetchMe()
      userStore.fetchEnums().catch(() => undefined)
    } catch {
      return '/login'
    }
  }
  if (to.path === '/') return userStore.homePath
  const hiddenFor = to.meta.hideForRolesOnly
  if (
    Array.isArray(hiddenFor) &&
    hiddenFor.length > 0 &&
    userStore.roles.length > 0 &&
    userStore.roles.every((role) => hiddenFor.includes(role))
  ) {
    return '/403'
  }
  const required = to.meta.permissions
  if (Array.isArray(required) && required.length > 0 && !userStore.can(...required)) {
    return '/403'
  }
  return true
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : ''
  document.title = title ? `${title} · 双高管理平台` : '双高管理平台'
})

export default router
