import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const H5_BASE = process.env.H5_BASE_URL || 'http://localhost:5174'
const PC_BASE = process.env.PC_BASE_URL || 'http://localhost:5173'
const PLAN_ID = '2124807326574514178'
const SHEET_ID = '2124807326637428737'
const response = (data) => ({ code: 0, message: 'OK', data, requestId: 'h5-training-entry-mock' })
const pageResult = (items = []) => ({ items, total: items.length, page: 1, pageSize: 20 })
const plan = {
  id: PLAN_ID,
  name: '2025—2026学年职业培训信息采集',
  startYear: 2025,
  endYear: 2026,
  baselineYear: 2024,
  status: 'ACTIVE',
  planType: 'TRAINING',
  completionRate: null,
  createdAt: '2026-11-23T19:23:00+08:00',
  updatedAt: '2026-11-23T19:23:00+08:00',
  indicatorCountA: 0,
  indicatorCountAPlus: 0,
  taskCountB: 0,
  version: 3,
}
const sheet = {
  id: SHEET_ID,
  planId: PLAN_ID,
  planName: plan.name,
  startYear: 2025,
  endYear: 2026,
  baselineYear: 2024,
  unitId: '14',
  unitName: '产业AI人才服务中心',
  ownerId: 'owner-1',
  ownerName: '田明月',
  status: 'APPROVED',
  projectCount: 1,
  version: 1,
  updatedAt: '2026-11-24T16:00:00+08:00',
  submitterRole: 'UNIT_OWNER',
  currentReviewNode: null,
  revisionNo: 1,
  submittedBy: 'owner-1',
  submittedByName: '田明月',
  submittedAt: '2026-11-24T15:00:00+08:00',
  canEdit: false,
  canReview: false,
}
const fields = [
  { key: 'c01', column: 1, label: '项目名称', group: 'BASIC', groupLabel: '项目基本', type: 'TEXT', required: true, options: [] },
  { key: 'c05', column: 5, label: '开班日期', group: 'BASIC', groupLabel: '项目基本', type: 'DATE', required: true, options: [] },
  { key: 'c06', column: 6, label: '结束日期', group: 'BASIC', groupLabel: '项目基本', type: 'DATE', required: true, options: [] },
  { key: 'c09', column: 9, label: '合同金额', group: 'FUNDING', groupLabel: '合同经费', type: 'DECIMAL', required: false, options: [] },
  { key: 'c10', column: 10, label: '财政资金', group: 'FUNDING', groupLabel: '合同经费', type: 'DECIMAL', required: false, options: [] },
  { key: 'c11', column: 11, label: '非财政资金', group: 'FUNDING', groupLabel: '合同经费', type: 'DECIMAL', required: false, options: [] },
  { key: 'c14', column: 14, label: '项目培训总人数', group: 'IMPLEMENTATION', groupLabel: '培训实施', type: 'INTEGER', required: true, options: [] },
]
const project = {
  id: 'project-1',
  rowNo: 1,
  data: {
    c01: '乡村电商人才培训班',
    c05: '2026-10-10',
    c06: '2026-10-12',
    c09: 8,
    c10: 3,
    c11: 2,
    c14: 36,
  },
  status: 'APPROVED',
  submitterRole: 'UNIT_OWNER',
  currentReviewNode: null,
  revisionNo: 1,
  submittedBy: 'owner-1',
  submittedAt: '2026-11-24T15:00:00+08:00',
  canEdit: false,
  canReview: false,
  reviewComment: null,
}

const browser = await chromium.launch({ headless: process.env.SMOKE_HEADFUL !== '1' })
const context = await browser.newContext({
  viewport: { width: 390, height: 844 },
  isMobile: true,
  hasTouch: true,
  deviceScaleFactor: 2,
  userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Lark/7.30.0 Mobile',
})
const page = await context.newPage()
const scriptErrors = []
const writes = []
const trainingQueries = []
page.on('pageerror', (error) => scriptErrors.push(error.message))
page.on('request', (request) => {
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method()) && !request.url().endsWith('/auth/logout')) {
    writes.push(`${request.method()} ${request.url()}`)
  }
})
await page.addInitScript(({ planId, h5Origin }) => {
  sessionStorage.setItem('fzsu.accessToken', 'mock-access-token')
  sessionStorage.setItem('fzsu.refreshToken', 'mock-refresh-token')
  if (window.location.origin === h5Origin && !sessionStorage.getItem('h5-training-entry-initialized')) {
    localStorage.setItem('metrics:current-plan', JSON.stringify({ id: planId, name: '培训缓存应被清理' }))
    sessionStorage.setItem('h5-training-entry-initialized', '1')
  }
}, { planId: PLAN_ID, h5Origin: new URL(H5_BASE).origin })
await page.route('**/api/v1/**', (route) => {
  const url = new URL(route.request().url())
  const path = url.pathname
  if (path.endsWith('/auth/me')) {
    return route.fulfill({ json: response({
      user: { id: '1', username: 'admin', name: '平台管理员', roles: ['ADMIN'] },
      roles: ['ADMIN'],
      permissions: ['plan:manage', 'training:fill', 'submission:review'],
      dataScope: { type: 'ALL', unitId: null, unitIds: [], indicatorIds: [] },
      passwordChangeRequired: false,
    }) })
  }
  if (path.endsWith('/dicts/enums')) return route.fulfill({ json: response({}) })
  if (path === '/api/v1/plans') return route.fulfill({ json: response([plan]) })
  if (path === '/api/v1/training-sheets') {
    trainingQueries.push(url.searchParams.get('planId'))
    return route.fulfill({ json: response([sheet]) })
  }
  if (path === `/api/v1/training-sheets/${SHEET_ID}`) {
    return route.fulfill({ json: response({ sheet, fields, projects: [project], reviews: [] }) })
  }
  if (path === '/api/v1/todos' || path === '/api/v1/notifications') {
    return route.fulfill({ json: response(pageResult()) })
  }
  if (path === '/api/v1/notifications/unread-count') return route.fulfill({ json: response(0) })
  return route.fulfill({ json: response({}) })
})

try {
  await page.goto(`${H5_BASE}/plans`)
  await page.locator('.plan-card').click()
  await page.waitForURL((url) =>
    url.origin === new URL(PC_BASE).origin
    && url.pathname === `/training/${SHEET_ID}`
    && url.searchParams.get('from') === 'plans'
    && url.searchParams.get('source') === 'h5', { timeout: 20000 })
  assert.deepEqual(trainingQueries, [PLAN_ID])
  await page.getByText('乡村电商人才培训班', { exact: true }).first().waitFor()
  await page.getByText('当前为审核通过项目预览', { exact: false }).waitFor()
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true)
  console.log('PASS 手机端培训计划进入响应式详情并显示项目数据，无横向溢出')

  await page.getByRole('button', { name: '查看项目', exact: true }).click()
  const drawer = page.locator('.training-project-drawer')
  const projectNameInput = drawer.locator('.el-form-item').filter({ hasText: '项目名称' }).locator('input')
  await projectNameInput.waitFor()
  assert.equal(await projectNameInput.inputValue(), '乡村电商人才培训班')
  assert.equal(await projectNameInput.isDisabled(), true)
  console.log('PASS 390px 项目抽屉可查看完整字段且通过项目只读')

  await drawer.getByRole('button', { name: '关闭', exact: true }).click()
  await page.getByRole('button', { name: /返回计划首页/ }).click()
  await page.waitForURL((url) => url.origin === new URL(H5_BASE).origin && url.pathname.endsWith('/plans'), { timeout: 20000 })
  await page.getByText('当前项目 未选', { exact: false }).waitFor()
  assert.equal(await page.evaluate(() => localStorage.getItem('metrics:current-plan')), null)
  assert.deepEqual(scriptErrors, [])
  assert.deepEqual(writes, [])
  console.log('PASS 返回 H5 计划首页并清除错误培训缓存，全程无业务写入或脚本错误')
} finally {
  await browser.close()
}
