import assert from 'node:assert/strict'
import { chromium, firefox, webkit } from 'playwright'

const BASE = process.env.GANTT_BASE_URL || 'http://localhost:5173'
const TASK_COUNT = Number(process.env.GANTT_TASK_COUNT || 100)
const selected = (process.env.GANTT_BROWSERS || 'chromium,edge,firefox,webkit').split(',')
const results = []

function payload(count) {
  const nodes = [{ id: 'a', parentId: null, kind: 'indicator', code: 'A01', name: '完整任务甘特图', caliber: 'INCREMENT', unitLabel: '项', startYear: 2026, endYear: 2030, cumulativeRate: 0.6, years: [] }]
  for (let year = 2026; year <= 2030; year += 1) nodes[0].years.push({ year, target: 100, actualValue: 60, completionRate: 0.6, status: 'IN_PROGRESS' })
  for (let index = 0; index < count; index += 1) {
    const years = []
    for (let year = 2026; year <= 2030; year += 1) years.push({ year, target: 10, actualValue: year <= 2026 ? 10 : 0, completionRate: year <= 2026 ? 1 : 0, status: year <= 2026 ? 'ACHIEVED' : 'NOT_STARTED' })
    nodes.push({ id: `b${index}`, parentId: 'a', kind: 'task', code: `A01-C${String(index + 1).padStart(3, '0')}`, name: `学院任务 ${index + 1}`, unitName: '智能产业学院', caliber: 'INCREMENT', unitLabel: '项', startYear: 2026, endYear: 2030, cumulativeRate: 0.2, years })
  }
  return {
    plan: { id: '123', name: '甘特图专项计划', startYear: 2026, endYear: 2030, status: 'EXECUTING' }, years: [2026, 2027, 2028, 2029, 2030], nodes: count === 0 ? [] : nodes,
    milestones: [{ id: 'm1', indicatorId: 'a', name: '阶段验收', relatedYear: 2028, dueDate: '2028-06-30', status: 'PENDING', version: 1 }],
    dependencies: count > 1 ? [{ id: 'd1', predecessorTaskId: 'b0', successorTaskId: 'b1', type: 'FS', version: 1 }] : [],
    criticalPath: { status: 'OK', taskIds: count > 1 ? ['b0', 'b1'] : [], dependencyIds: count > 1 ? ['d1'] : [] },
    graph: { truncated: false, omittedDependencies: 0, message: null },
  }
}

async function run(name, launcher, options = {}) {
  const browser = await launcher.launch({ headless: true, ...options })
  const page = await browser.newPage({ viewport: { width: 1366, height: 900 } })
  const errors = []
  page.on('pageerror', (error) => { if (error.message !== 'Request aborted') errors.push(error.stack || error.message) })
  page.on('requestfailed', (request) => { if (!request.url().includes('/auth/feishu/')) errors.push(`REQUEST_FAILED ${request.url()} ${request.failure()?.errorText}`) })
  page.on('console', (message) => { if (message.type() === 'error' && !message.text().includes('401 (Unauthorized)') && message.text() !== 'Request aborted') errors.push(message.text()) })
  let requests = 0
  await page.route('**/api/v1/plans/123/gantt*', async (route) => {
    requests += 1
    await route.fulfill({ json: { code: 0, message: 'ok', requestId: 'gantt-test', data: payload(TASK_COUNT) } })
  })
  await page.route('**/api/v1/indicators/123', (route) => route.fulfill({
    json: { code: 0, message: 'ok', requestId: 'indicator-test', data: {
      id: '123', planId: '123', level: 'A', code: 'A01', name: '完整任务甘特图', nature: 'DEVELOPMENT',
      statCaliber: 'INCREMENT', isQuantifiable: true, status: 'DISTRIBUTED', editable: false,
      children: [], bSummaries: [],
    } },
  }))
  await page.route('**/api/v1/plans/123', (route) => route.fulfill({
    json: { code: 0, message: 'ok', requestId: 'plan-test', data: {
      id: '123', name: '甘特图专项计划', startYear: 2026, endYear: 2030, baselineYear: 2025,
      status: 'ACTIVE', version: 1,
    } },
  }))
  const auth = await page.request.post('http://localhost:8080/api/v1/auth/login', { data: { username: 'admin', password: 'Test@123456' } })
  const tokens = (await auth.json()).data
  await page.addInitScript(([accessToken, refreshToken]) => {
    sessionStorage.setItem('fzsu.accessToken', accessToken)
    sessionStorage.setItem('fzsu.refreshToken', refreshToken)
  }, [tokens.accessToken, tokens.refreshToken])
  const start = performance.now()
  await page.goto(`${BASE}/indicators/123`)
  if (TASK_COUNT === 0) {
    await page.getByText('暂无甘特图数据', { exact: true }).waitFor()
    assert.equal(requests, 1)
    results.push({ browser: name, tasks: 0, empty: true, requests, errors: errors.length })
    await browser.close()
    return
  }
  await page.locator('.indicator-gantt canvas').waitFor({ timeout: 30000 }).catch(async (error) => { await diagnose(page, errors, requests); throw error })
  const loadMs = performance.now() - start
  assert.equal(requests, 1, '首屏只能请求一次聚合接口')
  assert.ok(await page.locator('.gantt-label').count() <= 30, '只能渲染虚拟窗口行')
  await page.getByRole('button', { name: '放大', exact: true }).click()
  assert.ok(await page.locator('.zoom-actions').getByText('2×').isVisible())
  await page.locator('.gantt-label').nth(1).click()
  assert.ok(await page.locator('.node-detail').isVisible())
  const initialScrollHeight = await page.locator('.gantt-body').evaluate((element) => element.scrollHeight)
  await page.locator('.gantt-body').evaluate((element) => { element.scrollTop = element.scrollHeight })
  await page.waitForTimeout(250)
  const finalScrollHeight = await page.locator('.gantt-body').evaluate((element) => element.scrollHeight)
  assert.equal(finalScrollHeight, initialScrollHeight, '虚拟滚动高度必须稳定，不能产生额外空白')
  assert.ok((await page.locator('.gantt-label').last().textContent()).includes(String(TASK_COUNT)), '滚动后可访问最后任务')
  for (const viewport of [{ width: 320, height: 700 }, { width: 390, height: 844 }, { width: 428, height: 926 }, { width: 768, height: 1024 }, { width: 1024, height: 768 }, { width: 1366, height: 768 }, { width: 1920, height: 1080 }, { width: 2560, height: 1440 }]) {
    await page.setViewportSize(viewport)
    await page.waitForTimeout(150)
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1)
    assert.equal(overflow, false, `${viewport.width}px 页面不得横向溢出`)
  }
  if (errors.length) throw new Error(`浏览器运行异常：${JSON.stringify(errors)}`)
  results.push({ browser: name, tasks: TASK_COUNT, loadMs: Math.round(loadMs), renderedRows: await page.locator('.gantt-label').count(), requests, errors: errors.length })
  if (name === 'chromium') {
    await page.getByRole('button', { name: '全规划期', exact: true }).click()
    await page.locator('.gantt-body').evaluate((element) => { element.scrollTop = 0 })
    await page.waitForTimeout(200)
    const rect = await page.locator('.gantt-canvas').boundingBox()
    const yearWidth = (rect.width - 34) / 5
    await page.mouse.move(rect.x + 12 + yearWidth * 0.5, rect.y + 64 * 1.5)
    await page.mouse.down()
    await page.mouse.move(rect.x + 12 + yearWidth * 2.5, rect.y + 64 * 1.5, { steps: 8 })
    await page.mouse.up()
    await page.waitForURL(/adjustments\/apply/, { timeout: 10000 })
    assert.equal(new URL(page.url()).searchParams.get('type'), 'POSTPONE')
  }
  await browser.close()
}

async function diagnose(page, errors, requests) {
  console.error(JSON.stringify({ errors, requests, text: (await page.locator('body').innerText()).slice(-2000) }, null, 2))
}

try {
  for (const name of selected) {
    if (name === 'chromium') await run(name, chromium)
    if (name === 'edge') await run(name, chromium, { channel: 'msedge' })
    if (name === 'firefox') await run(name, firefox)
    if (name === 'webkit') await run(name, webkit)
  }
  console.log(JSON.stringify(results, null, 2))
} catch (error) {
  console.error(JSON.stringify({ results, error: String(error) }, null, 2))
  process.exitCode = 1
}
