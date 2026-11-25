import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const BASE = process.env.SMOKE_BASE_URL || 'http://localhost:5173'
const TRAINING_ID = '2124807326574514178'
const INDICATOR_ID = '2118170415512559618'
const response = (data) => ({ code: 0, message: 'OK', data, requestId: 'leader-training-context-mock' })
const pageResult = (items = []) => ({ items, total: items.length, page: 1, pageSize: 20 })
const plan = (id, name, planType) => ({
  id, name, planType,
  startYear: 2026,
  endYear: 2030,
  baselineYear: 2025,
  status: 'ACTIVE',
  completionRate: 0,
  createdAt: '2026-11-24T10:00:00+08:00',
  updatedAt: '2026-11-24T10:00:00+08:00',
  indicatorCountA: 1,
  indicatorCountAPlus: 0,
  taskCountB: 1,
  version: 1,
})
const training = plan(TRAINING_ID, '2025—2026学年职业培训信息采集', 'TRAINING')
const indicator = plan(INDICATOR_ID, '学校建设目标清单', 'INDICATOR')
const detail = {
  ...indicator,
  settleDeadline: null,
  indicatorsFinalized: false,
  finalizedAt: null,
  years: [2026, 2027, 2028, 2029, 2030].map((year) => ({ year, status: 'ACTIVE', settledAt: null })),
}
const personnel = {
  id: 'user-1',
  username: '2600130158',
  name: '黄恺杰',
  phone: '138****5678',
  email: null,
  unitId: 'unit-1',
  unitName: '智能产业学院',
  roles: ['UNIT_OWNER'],
  isEnabled: true,
  lastLoginAt: '2026-11-25T16:00:00+08:00',
  version: 1,
  unitIds: ['unit-1'],
  ownerUserId: null,
}
const otherPersonnel = {
  ...personnel,
  id: 'user-2',
  username: '2500130348',
  name: '许钧泓',
  unitId: 'unit-2',
  unitName: '数字金融产业学院',
  roles: ['STAFF'],
  unitIds: ['unit-2'],
}
const personnelList = [personnel, otherPersonnel]
const orgUnits = [
  { id: 'unit-1', code: '01', name: '智能产业学院', type: 'COLLEGE', isEnabled: true, isBAssignable: true, sort: 1 },
  { id: 'unit-2', code: '02', name: '数字金融产业学院', type: 'COLLEGE', isEnabled: true, isBAssignable: true, sort: 2 },
]

const browser = await chromium.launch({ headless: process.env.SMOKE_HEADFUL !== '1' })
const page = await browser.newPage({ baseURL: BASE, viewport: { width: 1440, height: 1000 } })
const scriptErrors = []
const trainingPlanRequests = []
const userRequests = []
const staffRequests = []
const writes = []
page.on('pageerror', (error) => scriptErrors.push(error.message))
page.on('request', (request) => {
  const url = new URL(request.url())
  if (url.searchParams.get('planId') === TRAINING_ID || url.pathname.includes(`/plans/${TRAINING_ID}`)) {
    trainingPlanRequests.push(`${request.method()} ${url.pathname}${url.search}`)
  }
  if (url.pathname === '/api/v1/users/staff') staffRequests.push(`${request.method()} ${url.pathname}${url.search}`)
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method()) && !request.url().endsWith('/auth/logout')) {
    writes.push(`${request.method()} ${request.url()}`)
  }
})
await page.addInitScript((trainingId) => {
  sessionStorage.setItem('fzsu.accessToken', 'mock-access-token')
  sessionStorage.setItem('fzsu.refreshToken', 'mock-refresh-token')
  localStorage.setItem('metrics:current-plan', JSON.stringify({
    id: trainingId,
    name: '2025—2026学年职业培训信息采集',
    planType: 'TRAINING',
  }))
}, TRAINING_ID)
let currentRoles = ['LEADER']
await page.route('**/api/v1/**', (route) => {
  const url = new URL(route.request().url())
  const path = url.pathname
  if (path.endsWith('/auth/me')) {
    return route.fulfill({ json: response({
      user: { id: 'leader-1', username: 'leader', name: '当前账号', roles: currentRoles },
      roles: currentRoles,
      permissions: [
        'system:manage', 'user:create', 'submission:review', 'stats:view',
        'screen:view', 'audit-log:view', 'material:view', 'adjustment:decide',
      ],
      dataScope: { type: 'ALL', unitId: null, unitIds: [], indicatorIds: [] },
      passwordChangeRequired: false,
    }) })
  }
  if (path.endsWith('/dicts/enums')) return route.fulfill({ json: response({}) })
  if (path === '/api/v1/plans') return route.fulfill({ json: response([training, indicator]) })
  if (path === `/api/v1/plans/${INDICATOR_ID}`) return route.fulfill({ json: response(detail) })
  if (path === `/api/v1/plans/${INDICATOR_ID}/stats/overview`) {
    return route.fulfill({ json: response({ warnings: [] }) })
  }
  if (path === `/api/v1/plans/${INDICATOR_ID}/stats/by-unit`
      || path === `/api/v1/plans/${INDICATOR_ID}/stats/by-indicator`
      || path === `/api/v1/plans/${INDICATOR_ID}/stats/tree`) {
    return route.fulfill({ json: response([]) })
  }
  if (path === '/api/v1/users') {
    userRequests.push(url)
    const unitId = url.searchParams.get('unitId')
    const items = unitId ? personnelList.filter((item) => item.unitId === unitId) : personnelList
    return route.fulfill({ json: response(pageResult(items)) })
  }
  if (path === '/api/v1/org-units') return route.fulfill({ json: response(orgUnits) })
  if (path === '/api/v1/reviews/pending' || path === '/api/v1/training-reviews/pending'
      || path === '/api/v1/todos' || path === '/api/v1/notifications'
      || path === '/api/v1/files/materials') {
    return route.fulfill({ json: response(pageResult()) })
  }
  if (path === '/api/v1/notifications/unread-count') return route.fulfill({ json: response(0) })
  if (path === '/api/v1/files/materials/indicators'
      || path === '/api/v1/files/materials/counts'
      || path === '/api/v1/training-sheets'
      || path === '/api/v1/org-departments/tree'
      || path === '/api/v1/major-groups'
      || path === '/api/v1/directions'
      || path === `/api/v1/plans/${INDICATOR_ID}/directions`
      || path === `/api/v1/plans/${INDICATOR_ID}/indicators`) {
    return route.fulfill({ json: response([]) })
  }
  return route.fulfill({ json: response({}) })
})

async function assertNoTrainingError() {
  assert.equal(await page.getByText('职业培训信息采集计划不支持指标计划操作', { exact: false }).count(), 0)
}

try {
  await page.goto('/system')
  await page.locator('.el-tabs__item').first().waitFor()
  const labels = await page.locator('.el-tabs__item').allTextContents()
  assert.deepEqual(labels.map((item) => item.trim()), [
    '执行单元', '组织架构', '主要建设方向', '专业群', '用户与角色',
  ])
  console.log('PASS 校级负责人系统管理仅显示指定五个页签')

  await page.locator('.el-tabs__item').filter({ hasText: '主要建设方向' }).click()
  await page.getByRole('button', { name: '新增 主要建设方向', exact: true }).waitFor()
  assert.equal(await page.getByText('仅平台管理员或校级负责人可配置主要建设方向', { exact: true }).count(), 0)
  console.log('PASS 校级负责人可配置主要建设方向、总负责人及 A 归属')

  await page.locator('.el-tabs__item').filter({ hasText: '用户与角色' }).click()
  await page.getByText('全校人员查询', { exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '新增用户' }).count(), 0)
  assert.equal(await page.locator('.el-table:visible').getByText('操作', { exact: true }).count(), 0)
  assert.equal(await page.getByRole('button', { name: /编辑|设置角色|更多/ }).count(), 0)
  await page.locator('[aria-label="按状态筛选"]:visible').first().waitFor()
  await page.getByPlaceholder('姓名 / 工号').fill('2600130158')
  const searchResponse = page.waitForResponse((res) => {
    const url = new URL(res.url())
    return url.pathname === '/api/v1/users' && url.searchParams.get('keyword') === '2600130158'
  })
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await searchResponse
  await page.locator('.el-table:visible').getByText('黄恺杰', { exact: true }).waitFor()
  assert.ok(userRequests.some((url) => url.searchParams.get('keyword') === '2600130158'
    && url.searchParams.get('page') === '1'))
  console.log('PASS 校级用户目录支持全校人员查找且无新增、编辑、角色、密码或删除入口')

  await page.goto('/materials')
  await page.waitForTimeout(500)
  await assertNoTrainingError()
  console.log('PASS 培训缓存不会传入材料库指标接口')

  await page.goto('/stats')
  await page.waitForTimeout(500)
  await assertNoTrainingError()
  console.log('PASS 培训缓存不会传入统计报表指标接口')

  await page.goto('/reviews')
  await page.waitForTimeout(500)
  await assertNoTrainingError()
  console.log('PASS 审核中心不受培训计划缓存影响')

  await page.goto('/plan-overview')
  await page.waitForURL((url) => url.pathname === `/plans/${INDICATOR_ID}`, { timeout: 15000 })
  await assertNoTrainingError()
  console.log('PASS 通用计划概览自动回退到 INDICATOR 计划')

  currentRoles = ['ADMIN']
  await page.goto('/system')
  await page.locator('.el-tabs__item').filter({ hasText: '主要建设方向' }).click()
  await page.getByText('主要建设方向与总负责人', { exact: true }).waitFor()
  const directionPlanSelect = page.locator('.el-tab-pane:visible .panel-toolbar .el-select').first()
  await page.waitForFunction((expectedName) => {
    const selects = [...document.querySelectorAll('.el-tab-pane .panel-toolbar .el-select')]
    return selects.some((select) => select.getClientRects().length > 0
      && (select.textContent?.includes(expectedName)
        || select.querySelector('input')?.value === expectedName))
  }, indicator.name)
  await directionPlanSelect.click()
  await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').first().waitFor()
  const directionPlanOptions = await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').allTextContents()
  assert.ok(directionPlanOptions.some((text) => text.includes(indicator.name)))
  assert.ok(directionPlanOptions.every((text) => !text.includes(training.name)))
  await page.keyboard.press('Escape')
  await assertNoTrainingError()
  console.log('PASS 管理员主要建设方向排除 TRAINING，不再复现截图中的指标计划报错')

  currentRoles = ['UNIT_OWNER']
  await page.goto('/users')
  await page.getByRole('heading', { name: '用户管理', exact: true }).waitFor()
  await page.getByText('全校人员查询', { exact: true }).waitFor()
  const directoryTable = page.locator('.el-table:visible')
  await directoryTable.getByText('黄恺杰', { exact: true }).waitFor()
  await directoryTable.getByText('许钧泓', { exact: true }).waitFor()
  assert.equal(await directoryTable.getByText('学院项目负责人', { exact: true }).count(), 1)
  assert.equal(await directoryTable.getByText('干事', { exact: true }).count(), 1)
  assert.equal(await directoryTable.getByText('操作', { exact: true }).count(), 0)
  assert.equal(await page.getByRole('button', { name: /新增干事|新增用户|编辑|设置角色|更多/ }).count(), 0)
  await page.locator('.panel-toolbar .el-select').nth(1).click()
  await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').first().waitFor()
  const directoryRoleOptions = await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').allTextContents()
  assert.deepEqual(directoryRoleOptions.map((text) => text.trim()), ['学院项目负责人', '干事'])
  await page.keyboard.press('Escape')
  await page.locator('.panel-toolbar .el-select').first().click()
  const unitOption = page.locator('.el-select-dropdown:visible .el-select-dropdown__item')
    .filter({ hasText: '02 数字金融产业学院' })
  await unitOption.waitFor()
  const unitResponse = page.waitForResponse((res) => {
    const url = new URL(res.url())
    return url.pathname === '/api/v1/users' && url.searchParams.get('unitId') === 'unit-2'
  })
  await unitOption.click()
  await unitResponse
  await directoryTable.getByText('许钧泓', { exact: true }).waitFor()
  assert.equal(await directoryTable.getByText('黄恺杰', { exact: true }).count(), 0)
  assert.ok(userRequests.some((url) => url.searchParams.get('unitId') === 'unit-2'))
  console.log('PASS 独立用户管理可按学院查询全校人员并展示角色，页面强制只读')

  assert.deepEqual(trainingPlanRequests, [])
  assert.deepEqual(staffRequests, [])
  assert.deepEqual(scriptErrors, [])
  assert.deepEqual(writes, [])
  console.log('PASS 全部页面均未向指标接口发送培训计划 ID，无脚本错误或业务写入')
} finally {
  await browser.close()
}
