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
    children: [
      {
        path: 'dashboard',
        redirect: '/',
      },
      {
        // P02 首页（计划卡片）
        path: 'plans',
        name: 'Plans',
        component: () => import('@/views/plans/index.vue'),
        meta: { title: '首页', hideForRolesOnly: ['UNIT_OWNER', 'STAFF'] },
      },
      {
        // P03 无计划上下文时由概览页选择当前账号可见的默认指标计划
        path: 'plan-overview',
        name: 'PlanOverviewEntry',
        component: () => import('@/views/plans/overview.vue'),
        meta: { title: '计划概览' },
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
        meta: { title: '指标总表', hideForRolesOnly: ['UNIT_OWNER', 'STAFF'] },
      },
      {
        path: 'plans/:planId/gantt',
        redirect: (to) => `/plans/${to.params.planId}/indicators`,
      },
      {
        // 关键产出指标进度可视化（计划上下文）
        path: 'plans/:planId/indicator-progress',
        name: 'IndicatorProgress',
        component: () => import('@/views/indicators/progress.vue'),
        meta: { title: '指标进度可视化', hideForRolesOnly: ['UNIT_OWNER', 'STAFF'] },
      },
      {
        // P04 指标总表（无计划上下文时提示先选计划）
        path: 'indicators',
        name: 'Indicators',
        component: () => import('@/views/indicators/index.vue'),
        meta: { title: '指标总表', hideForRolesOnly: ['UNIT_OWNER', 'STAFF'] },
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
        // P07 学院工作台（UNIT_OWNER 本单元；管理员 / 建设方向负责人只读视角）
        path: 'workbench',
        name: 'Workbench',
        component: () => import('@/views/workbench/index.vue'),
        meta: { title: '学院工作台' },
      },
      {
        path: 'training',
        name: 'TrainingSheetList',
        component: () => import('@/views/training/list.vue'),
        meta: { title: '职业培训填写', permissions: ['training:fill'], requiredRoles: ['UNIT_OWNER', 'STAFF'] },
      },
      {
        // 职业培训详情，从填写列表、计划卡或审核中心进入
        path: 'training/:sheetId',
        name: 'TrainingSheetDetail',
        component: () => import('@/views/training/index.vue'),
        meta: { title: '职业培训信息采集', permissions: ['training:fill', 'submission:review'] },
      },
      {
        // P08 任务填报（隐藏菜单，从学院工作台任务行进入）
        path: 'tasks/:taskId/submit',
        name: 'TaskSubmit',
        component: () => import('@/views/submission/index.vue'),
        meta: { title: '任务填报', permissions: ['submission:submit'] },
      },
      {
        // 材料库（跨学院材料检索、上传与预览）
        path: 'materials',
        name: 'Materials',
        component: () => import('@/views/materials/index.vue'),
        meta: { title: '材料库', permissions: ['material:view'] },
      },
      {
        // P09 审核中心
        path: 'reviews',
        name: 'Reviews',
        component: () => import('@/views/review/index.vue'),
        meta: { title: '审核中心', permissions: ['submission:review'], hideForRolesOnly: ['STAFF'] },
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
        // P11 统计报表（纯学院负责人仅可查看已下发到本单元的计划）
        path: 'stats',
        name: 'Stats',
        component: () => import('@/views/stats/index.vue'),
        meta: { title: '统计报表', permissions: ['stats:view'] },
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
        path: 'users',
        name: 'UserManagement',
        component: () => import('@/views/users/index.vue'),
        meta: { title: '用户管理', permissions: ['user:create'], requiredRoles: ['UNIT_OWNER'] },
      },
      {
        path: 'users/create',
        name: 'UserCreate',
        component: () => import('@/views/users/create.vue'),
        meta: { title: '用户添加', permissions: ['user:create'] },
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
    // 网络失败时尚无 userInfo，允许停留登录页，避免仅凭本地 Token 循环跳回首页
    return userStore.isLoggedIn && userStore.userInfo ? '/' : true
  }
  // 路径中的 ID 参数必须是雪花 ID（纯数字）；含占位符（如 {planId}）等非法值直接回首页，避免后端 400/500
  const hasBadIdParam = Object.entries(to.params).some(([key, value]) => {
    if (!key.toLowerCase().endsWith('id')) return false
    const first = Array.isArray(value) ? value[0] : value
    return typeof first !== 'string' || !/^\d+$/.test(first)
  })
  if (hasBadIdParam) {
    return '/'
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
      return { path: '/login', query: { redirect: to.fullPath } }
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
  const requiredRoles = to.meta.requiredRoles
  if (
    Array.isArray(requiredRoles) &&
    requiredRoles.length > 0 &&
    !requiredRoles.some((role) => userStore.roles.includes(role))
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
