import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import type { RoleCode } from '@/types/api'

const all: RoleCode[] = ['ADMIN', 'SCHOOL_LEADER', 'SCHOOL_OWNER', 'COLLEGE_OWNER']
const business: RoleCode[] = ['SCHOOL_LEADER', 'SCHOOL_OWNER', 'COLLEGE_OWNER']
const school: RoleCode[] = ['SCHOOL_LEADER', 'SCHOOL_OWNER']

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('@/views/auth/LoginView.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/', component: () => import('@/layouts/MainLayout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '待办首页', roles: all } },
      { path: 'dashboard/leader', name: 'leader-dashboard', component: () => import('@/views/LeaderDashboardView.vue'), meta: { title: '数据看板', roles: ['SCHOOL_LEADER'] } },
      { path: 'planning/directories', name: 'directories', component: () => import('@/views/planning/DirectoryView.vue'), meta: { title: '建设目录', roles: ['ADMIN', ...school] } },
      { path: 'planning/plans', name: 'plans', component: () => import('@/views/planning/PlanView.vue'), meta: { title: '建设计划', roles: school } },
      { path: 'planning/indicators', name: 'indicators', component: () => import('@/views/planning/IndicatorTreeView.vue'), meta: { title: '方向与指标', roles: school } },
      { path: 'planning/indicators/:id', name: 'indicator-detail', component: () => import('@/views/planning/IndicatorDetailView.vue'), meta: { title: '指标量化', roles: school } },
      { path: 'planning/indicators/:id/decompose', name: 'decomposition', component: () => import('@/views/execution/TaskDecompositionView.vue'), meta: { title: '任务分解', roles: ['SCHOOL_OWNER'] } },
      { path: 'planning/imports', name: 'imports', component: () => import('@/views/planning/ImportView.vue'), meta: { title: 'Excel 导入', roles: ['SCHOOL_OWNER'] } },
      { path: 'planning/imports/:id', name: 'import-detail', component: () => import('@/views/planning/ImportPreviewView.vue'), meta: { title: '导入预览与确认', roles: ['SCHOOL_OWNER'] } },
      { path: 'execution/tasks', name: 'tasks', component: () => import('@/views/execution/CollegeTaskView.vue'), meta: { title: '学院任务', roles: business } },
      { path: 'execution/tasks/:id', name: 'task-detail', component: () => import('@/views/execution/TaskDetailView.vue'), meta: { title: '任务执行', roles: business } },
      { path: 'execution/milestones/:milestoneId/report', name: 'submission-editor', component: () => import('@/views/execution/SubmissionEditorView.vue'), meta: { title: '年度填报', roles: ['COLLEGE_OWNER'] } },
      { path: 'execution/reviews', name: 'reviews', component: () => import('@/views/execution/ReviewQueueView.vue'), meta: { title: '校级审核', roles: school } },
      { path: 'execution/reviews/:submissionId', name: 'review-detail', component: () => import('@/views/execution/ReviewDetailView.vue'), meta: { title: '审核工作台', roles: school } },
      { path: 'gantt', name: 'gantt', component: () => import('@/views/GanttView.vue'), meta: { title: '分层甘特图', roles: business } },
      { path: 'adjustments/carryovers', name: 'carryovers', component: () => import('@/views/adjustments/CarryoverView.vue'), meta: { title: '年度顺延与补位', roles: business } },
      { path: 'adjustments/transfers', name: 'transfers', component: () => import('@/views/adjustments/TransferView.vue'), meta: { title: '跨院划转', roles: business } },
      { path: 'adjustments/transfers/:id', name: 'transfer-detail', component: () => import('@/views/adjustments/TransferDetailView.vue'), meta: { title: '划转单详情', roles: business } },
      { path: 'logs', name: 'logs', component: () => import('@/views/LogView.vue'), meta: { title: '操作日志', roles: all } },
      { path: 'admin/organizations', name: 'organizations', component: () => import('@/views/admin/OrganizationView.vue'), meta: { title: '组织与别名', roles: ['ADMIN'] } },
      { path: 'admin/users', name: 'users', component: () => import('@/views/admin/UserView.vue'), meta: { title: '用户与角色', roles: ['ADMIN'] } },
      { path: 'admin/members', name: 'members', component: () => import('@/views/admin/MemberView.vue'), meta: { title: '项目成员', roles: ['ADMIN'] } },
      { path: 'admin/dictionaries', name: 'dictionaries', component: () => import('@/views/admin/DictionaryView.vue'), meta: { title: '业务字典', roles: ['ADMIN'] } },
      { path: 'admin/settings', name: 'settings', component: () => import('@/views/admin/SettingsView.vue'), meta: { title: '运行设置', roles: ['ADMIN'] } },
      { path: '403', name: 'forbidden', component: () => import('@/views/errors/ForbiddenView.vue'), meta: { title: '无权访问', roles: all } },
    ],
  },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/errors/NotFoundView.vue'), meta: { public: true, title: '页面不存在' } },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(async (to) => {
  document.title = `${String(to.meta.title || '管理平台')} · 五年建设任务管理平台`
  if (to.meta.public) return true
  const auth = useAuthStore()
  await auth.fetchMe()
  if (!auth.isLoggedIn) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.meta.roles && !auth.roles.some((role) => (to.meta.roles as RoleCode[]).includes(role))) return { name: 'forbidden' }
  return true
})

export default router
