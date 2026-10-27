import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录' },
  },
  {
    // 飞书 OAuth 回调（浏览器环境）：授权后携带 code 回本页换取平台 Token
    path: '/login/feishu-callback',
    name: 'FeishuCallback',
    component: () => import('@/views/login/FeishuCallback.vue'),
    meta: { title: '飞书登录' },
  },
  {
    path: '/',
    component: () => import('@/components/Layout/index.vue'),
    redirect: '/plans',
    children: [
      {
        path: 'dashboard',
        redirect: '/plans',
      },
      {
        // P02 首页（计划卡片）
        path: 'plans',
        name: 'Plans',
        component: () => import('@/views/plans/index.vue'),
        meta: { title: '首页' },
      },
      {
        // P03 计划概览
        path: 'plans/:planId',
        name: 'PlanOverview',
        component: () => import('@/views/plans/overview.vue'),
        meta: { title: '计划概览' },
      },
      {
        // P04 指标总表（计划上下文）
        path: 'plans/:planId/indicators',
        name: 'PlanIndicators',
        component: () => import('@/views/indicators/index.vue'),
        meta: { title: '指标总表' },
      },
      {
        // P04 指标总表（无计划上下文时提示先选计划）
        path: 'indicators',
        name: 'Indicators',
        component: () => import('@/views/indicators/index.vue'),
        meta: { title: '指标总表' },
      },
      {
        // P05 指标详情
        path: 'indicators/:id',
        name: 'IndicatorDetail',
        component: () => import('@/views/indicators/detail.vue'),
        meta: { title: '指标详情' },
      },
      {
        // P06 分解矩阵（计划上下文）
        path: 'plans/:planId/allocation',
        name: 'PlanAllocation',
        component: () => import('@/views/allocation/index.vue'),
        meta: { title: '分解下发', permissions: ['allocation:edit'] },
      },
      {
        // P06 分解矩阵（无计划上下文时提示先选计划）
        path: 'allocation',
        name: 'Allocation',
        component: () => import('@/views/allocation/index.vue'),
        meta: { title: '分解下发', permissions: ['allocation:edit'] },
      },
      {
        // P14 模板管理
        path: 'templates',
        name: 'Templates',
        component: () => import('@/views/templates/index.vue'),
        meta: { title: '模板管理', permissions: ['plan:manage'] },
      },
      {
        // P07 学院工作台（UNIT_OWNER 本单元；管理员 / 校级负责人只读视角）
        path: 'workbench',
        name: 'Workbench',
        component: () => import('@/views/workbench/index.vue'),
        meta: { title: '学院工作台' },
      },
      {
        // P08 任务填报（隐藏菜单，从学院工作台任务行进入）
        path: 'tasks/:taskId/submit',
        name: 'TaskSubmit',
        component: () => import('@/views/submission/index.vue'),
        meta: { title: '任务填报', permissions: ['submission:submit'] },
      },
      {
        // P09 审核中心
        path: 'reviews',
        name: 'Reviews',
        component: () => import('@/views/review/index.vue'),
        meta: { title: '审核中心', permissions: ['submission:review'] },
      },
      {
        // P10 调整申请 / 审批列表
        path: 'adjustments',
        name: 'Adjustments',
        component: () => import('@/views/adjustment/index.vue'),
        meta: { title: '调整申请与审批', permissions: ['adjustment:apply', 'adjustment:decide'] },
      },
      {
        // P10 发起调整（隐藏菜单，从列表页或学院工作台任务行进入）
        path: 'adjustments/apply',
        name: 'AdjustmentApply',
        component: () => import('@/views/adjustment/apply.vue'),
        meta: { title: '发起调整申请', permissions: ['adjustment:apply'] },
      },
      {
        // P11 统计报表（纯学院负责人不可见，UI 规范 6.1 / A4）
        path: 'stats',
        name: 'Stats',
        component: () => import('@/views/stats/index.vue'),
        meta: { title: '统计报表', hideForRolesOnly: ['UNIT_OWNER'] },
      },
      {
        // P13 操作日志
        path: 'logs',
        name: 'AuditLogs',
        component: () => import('@/views/logs/index.vue'),
        meta: { title: '操作日志', permissions: ['audit-log:view'] },
      },
      {
        // P16 待办与消息中心（全部登录角色可用）
        path: 'todos',
        name: 'Todos',
        component: () => import('@/views/todos/index.vue'),
        meta: { title: '待办与消息' },
      },
      {
        // P15 系统管理
        path: 'system',
        name: 'System',
        component: () => import('@/views/system/index.vue'),
        meta: { title: '系统管理', permissions: ['system:manage'] },
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
    // P12 可视化大屏：1920×1080 等比缩放，独立于后台布局（不带侧边栏）
    path: '/screen/:planId?',
    name: 'Screen',
    component: () => import('@/views/screen/index.vue'),
    meta: { title: '可视化大屏', permissions: ['screen:view'] },
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const userStore = useUserStore()

  if (to.path === '/login' || to.path === '/login/feishu-callback') {
    return userStore.isLoggedIn ? '/' : true
  }
  // 路径中的 ID 参数必须是雪花 ID（纯数字）；含占位符（如 {planId}）等非法值直接回首页，避免后端 400/500
  const hasBadIdParam = Object.entries(to.params).some(([key, value]) => {
    if (!key.toLowerCase().endsWith('id')) return false
    const first = Array.isArray(value) ? value[0] : value
    return typeof first !== 'string' || !/^\d+$/.test(first)
  })
  if (hasBadIdParam) {
    return '/plans'
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
    } catch {
      return '/login'
    }
  }
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
  document.title = title ? `${title} · 五年计划指标管理平台` : '五年计划指标管理平台'
})

export default router
