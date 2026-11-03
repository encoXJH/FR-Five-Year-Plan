import assert from 'node:assert/strict'
import { chromium, firefox, webkit } from 'playwright'

const BASE = process.env.NAV_BASE_URL || 'http://localhost:5173'
const API = 'http://localhost:8081/api/v1'
const PLAN_ID = process.env.NAV_PLAN_ID || '2115975164633669633'
const browsers = { chromium, firefox, webkit }
const selected = (process.env.NAV_BROWSERS || 'chromium,firefox,webkit').split(',')
const results = []

async function tokens(request, username) {
  const response = await request.post(`${API}/auth/login`, { data: { username, password: 'Test@123456' } })
  return (await response.json()).data
}

async function authenticate(page, username) {
  const value = await tokens(page.request, username)
  await page.addInitScript(([accessToken, refreshToken]) => {
    sessionStorage.setItem('fzsu.accessToken', accessToken)
    sessionStorage.setItem('fzsu.refreshToken', refreshToken)
  }, [value.accessToken, value.refreshToken])
  return value
}

async function check(page, url, expected, label) {
  await page.goto(`${BASE}${url}`)
  const button = page.locator('[data-back-button]')
  await button.waitFor({ timeout: 30000 })
  assert.equal(await button.count(), 1, `${label} 应恰好一个返回键`)
  assert.equal(await page.locator('.header-actions [data-back-button], .header-right [data-back-button]').count(), 0, `${label} 不得在角落操作区`)
  await button.click()
  await page.waitForURL((target) => target.pathname === expected, { timeout: 15000 })
  results.push({ page: label, target: expected })
}

for (const name of selected) {
  const browser = await browsers[name].launch({ headless: true })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  const adminAuth = await authenticate(page, 'admin')
  const primary = [
    [`/plans/${PLAN_ID}`, '/plans', '计划概览'], ['/templates', '/plans', '模板管理'],
  ]
  for (const item of primary) await check(page, ...item)
  const withoutBack = [
    ['/plans', '计划首页'], ['/workbench', '学院工作台'], ['/todos', '待办与消息'],
    [`/plans/${PLAN_ID}/indicators`, '指标总表'], ['/materials', '材料库'], ['/reviews', '审核中心'],
    ['/adjustments', '调整申请与审批'], [`/stats?planId=${PLAN_ID}`, '统计报表'],
    ['/logs', '操作日志'], ['/system', '系统管理'],
  ]
  for (const [path, label] of withoutBack) {
    await page.goto(`${BASE}${path}`)
    await page.locator('.page-title').waitFor()
    assert.equal(await page.locator('[data-back-button]').count(), 0, `${label}不显示返回首页按钮`)
  }
  results.push({ page: '一级页面', target: '隐藏返回首页按钮' })
  const secondary = [
    [`/plans/${PLAN_ID}/indicator-progress`, `/plans/${PLAN_ID}/indicators`, '指标进度'],
    [`/plans/${PLAN_ID}/allocation`, `/plans/${PLAN_ID}/indicators`, '任务分解'],
    ['/adjustments/apply', '/adjustments', '发起调整'],
  ]
  for (const item of secondary) await check(page, ...item)
  await page.goto(`${BASE}/stats?planId=${PLAN_ID}&year=2026`)
  await page.locator('.page-title').waitFor()
  await page.getByText('可视化大屏', { exact: true }).first().click()
  await page.waitForURL((target) => target.pathname === `/screen/${PLAN_ID}`)
  const screenBack = page.locator('[data-back-button]')
  assert.equal(await screenBack.innerText(), '返回', '大屏按钮文案为返回')
  await screenBack.click()
  await page.waitForURL((url) => url.pathname === '/stats' && url.searchParams.get('planId') === PLAN_ID && url.searchParams.get('year') === '2026')
  results.push({ page: '可视化大屏', target: '返回进入前页面并保留筛选' })
  await page.goto(`${BASE}/plans/${PLAN_ID}/gantt`)
  await page.waitForURL((target) => target.pathname === `/plans/${PLAN_ID}/indicators`)
  results.push({ page: '旧独立甘特图链接', target: '重定向指标总表' })

  const statsResponse = await page.request.get(`${API}/plans/${PLAN_ID}/stats/tree`, { headers: { Authorization: `Bearer ${adminAuth.accessToken}` } })
  const tree = (await statsResponse.json()).data
  const indicatorId = tree.find((node) => node.level === 'A').id
  await check(page, `/indicators/${indicatorId}`, `/plans/${PLAN_ID}/indicators`, '指标详情')
  await page.goto(`${BASE}/stats?planId=${PLAN_ID}&year=2026`)
  await page.locator('.el-table__body').getByRole('button', { name: '详情', exact: true }).first().click()
  await page.getByRole('button', { name: '查看任务甘特图', exact: true }).click()
  await page.locator('[data-back-button]').click()
  await page.waitForURL((url) => url.pathname === '/stats' && url.searchParams.get('planId') === PLAN_ID && url.searchParams.get('year') === '2026')
  results.push({ page: '统计来源返回', target: '保留planId/year' })

  await page.goto(`${BASE}/plans/${PLAN_ID}/indicator-progress`)
  await page.setViewportSize({ width: 390, height: 844 })
  const height = await page.locator('[data-back-button]').evaluate((element) => element.getBoundingClientRect().height)
  assert.ok(height >= 44, '移动端返回键至少44px')
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > 390), false)
  await page.goto(`${BASE}/screen/${PLAN_ID}?kiosk=1`)
  await page.waitForTimeout(300)
  assert.equal(await page.locator('[data-back-button]').count(), 0, 'kiosk不显示返回键')
  const unexpectedErrors = errors.filter((message) => message !== 'Request aborted' && message !== 'AxiosError: Network Error')
  assert.deepEqual(unexpectedErrors, [], `${name}无脚本错误`)
  await page.close()

  const unitPage = await browser.newPage()
  const unitAuth = await authenticate(unitPage, 'c01')
  const taskResponse = await unitPage.request.get(`${API}/tasks?page=1&pageSize=1`, { headers: { Authorization: `Bearer ${unitAuth.accessToken}` } })
  const taskId = (await taskResponse.json()).data.items[0].id
  await check(unitPage, `/tasks/${taskId}/submit`, '/workbench', '任务填报')
  await unitPage.goto(`${BASE}/workbench`)
  assert.equal(await unitPage.locator('[data-back-button]').count(), 0, '学院工作台首页不显示返回键')
  await browser.close()
}
console.log(JSON.stringify(results, null, 2))
