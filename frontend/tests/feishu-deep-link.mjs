import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const BASE = process.env.SMOKE_BASE_URL || 'http://localhost:5173'
const response = (data) => ({ code: 0, message: 'OK', data, requestId: 'feishu-deep-link-mock' })
const pageResult = (items = []) => ({ items, total: items.length, page: 1, pageSize: 50 })
const loginData = {
  accessToken: 'mock-access-token', accessTokenExpiresAt: '2026-11-24T23:59:59+08:00',
  refreshToken: 'mock-refresh-token', refreshTokenExpiresAt: '2026-12-01T23:59:59+08:00',
  user: { id: '1', username: 'admin', name: '平台管理员', roles: ['ADMIN'] },
  bindRequired: false, bindingTicket: null, bindingTicketExpiresAt: null,
}
const me = {
  user: loginData.user,
  roles: ['ADMIN'],
  permissions: ['submission:submit', 'submission:review', 'adjustment:apply', 'adjustment:decide', 'plan:manage'],
  dataScope: { type: 'ALL', unitId: null, unitIds: [], indicatorIds: [] },
  passwordChangeRequired: false,
}
const task = {
  id: '123', planId: 'plan-1', code: 'A01-04',
  indicator: { id: 'indicator-1', code: 'A01', name: '示例指标', statCaliber: 'INCREMENT', unitLabel: '项', targetDirection: 'HIGHER_BETTER' },
  unit: { id: 'unit-1', code: 'C01', name: '智能产业学院' }, owner: null,
  status: 'IN_PROGRESS', isHistorical: false, version: 0,
  plan: { id: 'plan-1', name: '示例计划', startYear: 2025, endYear: 2027 },
  years: [
    { year: 2025, target: 50, actualValue: 10, completionRate: 0.2, yearStatus: 'IN_PROGRESS', version: 0 },
    { year: 2026, target: 60, actualValue: 0, completionRate: 0, yearStatus: 'IN_PROGRESS', version: 0 },
    { year: 2027, target: 70, actualValue: null, completionRate: null, yearStatus: 'NOT_ACTIVATED', version: 0 },
  ],
}
const adjustment = {
  id: '456', type: 'POSTPONE', status: 'PENDING_LEADER_APPROVAL', currentNode: 'LEADER_APPROVAL',
  task: { id: '123', code: 'A01-04', isHistorical: false }, indicator: task.indicator,
  items: [{ fromYear: 2025, toYear: 2026, amount: 1 }], reason: '深链示例', applicant: null,
  version: 0, createdAt: '2026-11-24T10:00:00+08:00', snapshot: { before: [], after: [] }, steps: [], files: [],
}

const browser = await chromium.launch({ headless: process.env.SMOKE_HEADFUL !== '1' })
const context = await browser.newContext({
  baseURL: BASE,
  viewport: { width: 1440, height: 1000 },
  userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0.0.0 Lark/7.30.0',
})
const page = await context.newPage()
const scriptErrors = []
const externalFeishuRequests = []
const authorizeStates = []
const feishuLoginCodes = []
const reviewQueries = []
const adjustmentScopes = []
page.on('pageerror', (error) => scriptErrors.push(error.stack || error.message))
page.on('request', (request) => {
  if (/open\.feishu\.cn|open\.larksuite\.com|accounts\.feishu\.cn/.test(request.url())) {
    externalFeishuRequests.push(request.url())
  }
})
await page.route('**/api/v1/**', (route) => {
  const request = route.request()
  const url = new URL(request.url())
  const path = url.pathname
  if (path.endsWith('/auth/feishu/authorize-url')) {
    const state = url.searchParams.get('state')
    if (state) authorizeStates.push(state)
    const callback = `${BASE}/login/feishu-callback?code=mock-code&state=${state || 'probe'}`
    return route.fulfill({ json: response({ url: callback }) })
  }
  if (path.endsWith('/auth/feishu/login') && request.method() === 'POST') {
    feishuLoginCodes.push(request.postDataJSON().code)
    return route.fulfill({ json: response(loginData) })
  }
  if (path.endsWith('/auth/me')) return route.fulfill({ json: response(me) })
  if (path.endsWith('/dicts/enums')) return route.fulfill({ json: response({}) })
  if (path === '/api/v1/tasks/123') return route.fulfill({ json: response(task) })
  if (path === '/api/v1/tasks/123/submissions') return route.fulfill({ json: response(pageResult()) })
  if (path === '/api/v1/reviews/pending') {
    reviewQueries.push(Object.fromEntries(url.searchParams.entries()))
    return route.fulfill({ json: response(pageResult()) })
  }
  if (path === '/api/v1/training-reviews/pending') return route.fulfill({ json: response(pageResult()) })
  if (path === '/api/v1/adjustments') {
    adjustmentScopes.push(url.searchParams.get('scope'))
    return route.fulfill({ json: response(pageResult()) })
  }
  if (path === '/api/v1/adjustments/456') return route.fulfill({ json: response(adjustment) })
  if (path === '/api/v1/plans') return route.fulfill({ json: response([]) })
  if (path === '/api/v1/org-units') return route.fulfill({ json: response([]) })
  if (path === '/api/v1/notifications') return route.fulfill({ json: response(pageResult()) })
  return route.fulfill({ json: response({}) })
})

try {
  const target = '/tasks/123/submit?year=2025'
  await page.goto(`/login?redirect=${encodeURIComponent(target)}`)
  await page.waitForURL((url) => url.pathname === '/tasks/123/submit' && url.searchParams.get('year') === '2025', { timeout: 20000 })
  await page.getByText('2025 年度仅支持查看；只有当前自然年', { exact: false }).waitFor()
  assert.equal(feishuLoginCodes.at(-1), 'mock-code')
  assert.ok(authorizeStates.length > 0)
  assert.equal(await page.evaluate(() => sessionStorage.getItem('fzsu.feishu.oauthContext')), null)
  assert.equal(await page.evaluate(() => sessionStorage.getItem('fzsu.feishu.oauthState')), null)
  assert.ok(!page.url().includes('mock-access-token'))
  console.log('PASS PC 飞书 OAuth 免登后返回原任务及年度，历史年度只读')

  await page.evaluate(() => localStorage.setItem('metrics:current-plan', JSON.stringify({ id: 'cached-plan', name: '缓存计划' })))
  await page.goto('/reviews?reviewNode=LEADER_REVIEW')
  await page.waitForTimeout(800)
  assert.ok(reviewQueries.length > 0)
  assert.equal(reviewQueries.at(-1).reviewNode, 'LEADER_REVIEW')
  assert.equal(reviewQueries.at(-1).planId, undefined)
  await page.goto('/reviews')
  await page.waitForTimeout(800)
  assert.equal(reviewQueries.at(-1).planId, 'cached-plan')
  console.log('PASS PC 审核深链不被计划缓存干扰，普通入口仍保留缓存')

  await page.goto('/adjustments?scope=TODO&adjustmentId=456')
  await page.getByText('深链示例', { exact: true }).first().waitFor()
  assert.equal(adjustmentScopes.at(-1), 'TODO')
  console.log('PASS PC 调整深链进入 TODO 并打开指定详情')

  assert.deepEqual(scriptErrors, [])
  assert.deepEqual(externalFeishuRequests, [])
  console.log('PASS 全部流程未请求真实飞书接口、无浏览器脚本错误')
} finally {
  await browser.close()
}
