import { chromium } from 'playwright'
import fs from 'node:fs'
import path from 'node:path'

const BASE_URL = process.env.H5_BASE_URL || 'http://localhost:5174'
const SHOT_DIR = path.resolve('tests/e2e/screenshots')
fs.mkdirSync(SHOT_DIR, { recursive: true })

const errors = []
const results = []

function record(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` — ${detail}` : ''}`)
}

async function attachConsole(page, scope) {
  page.on('pageerror', (error) => {
    errors.push(`[${scope}] pageerror: ${error.message}`)
  })
  page.on('console', (message) => {
    if (message.type() !== 'error') return
    const text = message.text()
    if (text.includes('favicon') || text.includes('net::ERR_') || text.includes('401')) return
    errors.push(`[${scope}] console: ${text}`)
  })
}

async function checkOverflow(page, label) {
  const metrics = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
    bodyScrollWidth: document.body.scrollWidth,
  }))
  const overflow = Math.max(metrics.scrollWidth, metrics.bodyScrollWidth) - metrics.innerWidth
  record(`横向溢出检查 · ${label}`, overflow <= 2, `overflow=${overflow}px`)
  return overflow
}

async function screenshot(page, name) {
  await page.screenshot({ path: path.join(SHOT_DIR, `${name}.png`), fullPage: false })
}

async function goto(page, url) {
  await page.goto(`${BASE_URL}${url}`, { waitUntil: 'domcontentloaded' })
  await page.waitForLoadState('networkidle').catch(() => undefined)
  await page.waitForTimeout(500)
}

async function login(page, username, password = 'Test@123456') {
  await goto(page, '/login')
  await page.locator('input[placeholder="工号"]').fill(username)
  await page.locator('input[placeholder="密码"]').fill(password)
  await page.locator('button:has-text("登 录")').click()
  await page.waitForURL((url) => !url.pathname.endsWith('/login'), { timeout: 20000 })
  await page.waitForLoadState('networkidle').catch(() => undefined)
  await page.waitForTimeout(800)
}

async function openPlanOverview(page) {
  await page.locator('.plan-card').first().click()
  await page.waitForURL(/\/plans\/\d+$/, { timeout: 15000 })
  await page.waitForTimeout(800)
}

async function adminJourney(page) {
  await login(page, 'admin')
  record('管理员登录并进入计划首页', page.url().includes('/plans'))
  await checkOverflow(page, '首页')
  await screenshot(page, 'admin-plans-390')

  await openPlanOverview(page)
  await checkOverflow(page, '计划概览')
  await screenshot(page, 'admin-overview-390')

  const planId = page.url().split('/').pop()
  await page.locator('.module-item:has-text("指标总表")').click()
  await page.waitForURL(/\/indicators/, { timeout: 15000 })
  await page.waitForTimeout(1200)
  await checkOverflow(page, '指标总表')
  await screenshot(page, 'admin-indicators-390')

  const firstCode = page.locator('.code-btn').first()
  if (await firstCode.count()) {
    await firstCode.click()
    await page.waitForTimeout(1200)
    record('指标总表展开学院任务', true)
    await checkOverflow(page, '指标总表展开')
  }

  const detailLink = page.locator('.action-link:has-text("详情")').first()
  if (await detailLink.count()) {
    await detailLink.click()
    await page.waitForURL(/\/indicators\/\d+/, { timeout: 15000 })
    await page.waitForTimeout(1000)
    await checkOverflow(page, '指标详情')
    await screenshot(page, 'admin-indicator-detail-390')
  }

  await goto(page, `/plans/${planId}/indicator-progress`)
  await checkOverflow(page, '指标进度可视化')
  await screenshot(page, 'admin-indicator-progress-390')

  await goto(page, `/plans/${planId}/allocation`)
  await page.waitForTimeout(1200)
  await checkOverflow(page, '任务分解')
  record('任务分解矩阵渲染', (await page.locator('.indicator-selector').count()) > 0)
  await screenshot(page, 'admin-allocation-390')

  await goto(page, '/templates')
  await checkOverflow(page, '模板管理')
  await screenshot(page, 'admin-templates-390')

  await goto(page, '/adjustments/apply')
  await checkOverflow(page, '发起调整申请')
  await screenshot(page, 'admin-adjustment-apply-390')

  await goto(page, '/workbench')
  const taskCards = page.locator('.task-card')
  record('学院工作台任务列表渲染', (await taskCards.count()) > 0, `cards=${await taskCards.count()}`)
  await checkOverflow(page, '学院工作台')
  await screenshot(page, 'admin-workbench-390')
  if (await taskCards.count()) {
    await taskCards.first().locator('button:has-text("详情")').click()
    await page.waitForTimeout(900)
    record('任务详情弹层打开', await page.locator('.task-sheet').isVisible())
    await page.locator('.task-sheet .close-btn').click()
  }

  await goto(page, '/reviews')
  await checkOverflow(page, '审核中心')
  await screenshot(page, 'admin-reviews-390')

  await goto(page, '/adjustments')
  await checkOverflow(page, '调整申请与审批')
  await screenshot(page, 'admin-adjustments-390')
  const adjustCards = page.locator('.adjust-card')
  if (await adjustCards.count()) {
    await adjustCards.first().click()
    await page.waitForTimeout(900)
    record('调整详情弹层打开', await page.locator('.detail-sheet').isVisible())
    await page.locator('.detail-sheet .close-btn').click()
  }

  await goto(page, '/stats')
  await page.waitForTimeout(1200)
  const canvases = await page.locator('canvas').count()
  record('统计报表图表渲染', canvases >= 2, `canvas=${canvases}`)
  await checkOverflow(page, '统计报表')
  await screenshot(page, 'admin-stats-390')

  await goto(page, '/materials')
  await checkOverflow(page, '材料库')
  if (await page.locator('.selection-btn').count()) {
    await page.locator('.selection-btn').click()
    await page.waitForTimeout(500)
    const firstIndicator = page.locator('.indicator-main').first()
    if (await firstIndicator.count()) {
      await firstIndicator.click()
      await page.waitForTimeout(1200)
      record('材料库选择指标后加载材料', true)
      await checkOverflow(page, '材料库结果')
    }
    await screenshot(page, 'admin-materials-390')
  }

  await goto(page, '/logs')
  await checkOverflow(page, '操作日志')
  const logCards = page.locator('.log-card')
  if (await logCards.count()) {
    await logCards.first().click()
    await page.waitForTimeout(500)
    record('操作日志详情打开', await page.locator('.log-summary').isVisible())
    await page.locator('.van-popup .close-btn').last().click()
  }

  await goto(page, '/todos')
  await checkOverflow(page, '待办与消息')
  await screenshot(page, 'admin-todos-390')

  await goto(page, '/system')
  await checkOverflow(page, '系统管理菜单')
  await page.locator('.menu-item:has-text("执行单元")').click()
  await page.waitForTimeout(1000)
  record('系统管理执行单元页打开', page.url().includes('/system/units'))
  await checkOverflow(page, '系统管理执行单元')
  await screenshot(page, 'admin-system-390')

  for (const [tab, label] of [
    ['org', '组织架构'],
    ['major-groups', '专业群'],
    ['users', '用户管理'],
    ['directions', '建设方向'],
    ['dicts', '字典管理'],
    ['settings', '系统参数'],
    ['feishu', '飞书集成'],
  ]) {
    await goto(page, `/system/${tab}`)
    await checkOverflow(page, `系统管理 · ${label}`)
  }

  await goto(page, '/profile')
  await checkOverflow(page, '我的')
  await screenshot(page, 'admin-profile-390')

  await goto(page, `/screen/${planId}`)
  await page.waitForTimeout(1500)
  const screenCanvas = await page.locator('canvas').count()
  record('可视化大屏渲染', screenCanvas >= 1, `canvas=${screenCanvas}`)
  await checkOverflow(page, '可视化大屏')
  await screenshot(page, 'admin-screen-390')
}

async function unitOwnerJourney(browser) {
  const context = await browser.newContext({
    viewport: { width: 360, height: 800 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
  })
  const page = await context.newPage()
  await attachConsole(page, 'c01')
  await login(page, 'c01')
  record('学院负责人登录进入工作台', page.url().includes('/workbench'))
  await checkOverflow(page, '学院工作台（c01）')
  await screenshot(page, 'c01-workbench-360')

  const fillButton = page.locator('.task-card button:has-text("填报")').first()
  if ((await fillButton.count()) === 0) {
    record('学院任务存在可填报入口', false, '当前没有可填报任务')
    await context.close()
    return
  }
  await fillButton.click()
  await page.waitForURL(/\/tasks\/\d+\/submit/, { timeout: 15000 })
  await page.waitForTimeout(1200)
  record('任务填报页打开', await page.locator('.page-head h1').isVisible())
  await checkOverflow(page, '任务填报')
  await screenshot(page, 'c01-submission-360')

  const taskId = page.url().match(/\/tasks\/(\d+)\/submit/)?.[1]
  const valueInput = page.locator('.metric-input input').first()
  if (await valueInput.count()) {
    await valueInput.fill('1')
    await page.locator('button:has-text("存草稿")').click()
    await page.waitForTimeout(1500)
    const saved = await page.locator('.van-toast').filter({ hasText: '草稿已保存' }).count()
    record('填报存草稿', saved > 0)

    // 清理本次生成的草稿，避免污染演示数据
    const cleanup = await page.evaluate(async (id) => {
      const token = sessionStorage.getItem('fzsu.accessToken')
      const headers = { Authorization: `Bearer ${token}` }
      const list = await fetch(`/api/v1/tasks/${id}/submissions`, { headers }).then((r) => r.json())
      const draft = (list.data?.items ?? []).find((item) => item.status === 'DRAFT')
      if (!draft) return 'no-draft'
      const response = await fetch(`/api/v1/submissions/${draft.id}`, { method: 'DELETE', headers })
      return response.ok ? 'deleted' : `failed-${response.status}`
    }, taskId)
    record('清理测试草稿', cleanup === 'deleted' || cleanup === 'no-draft', cleanup)
  }

  await goto(page, '/todos')
  await checkOverflow(page, '待办与消息（c01）')
  await goto(page, '/materials')
  await checkOverflow(page, '材料库（c01）')
  await context.close()
}

async function viewportSweep(browser) {
  const viewports = [
    { width: 320, height: 568, name: '320' },
    { width: 768, height: 1024, name: '768' },
  ]
  for (const viewport of viewports) {
    const context = await browser.newContext({
      viewport: { width: viewport.width, height: viewport.height },
      isMobile: true,
      hasTouch: true,
    })
    const page = await context.newPage()
    await attachConsole(page, `viewport-${viewport.name}`)
    await login(page, 'admin')
    for (const route of ['/plans', '/workbench', '/todos', '/profile', '/stats', '/materials']) {
      await goto(page, route)
      await checkOverflow(page, `${route} @${viewport.name}`)
    }
    await screenshot(page, `admin-${viewport.name}`)
    await context.close()
  }
}

async function main() {
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 3,
    isMobile: true,
    hasTouch: true,
    userAgent:
      'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
  })
  const page = await context.newPage()
  await attachConsole(page, 'admin')
  try {
    await adminJourney(page)
  } catch (error) {
    record('管理员全流程巡检', false, error.message)
  }
  await context.close()

  try {
    await unitOwnerJourney(browser)
  } catch (error) {
    record('学院负责人流程巡检', false, error.message)
  }

  try {
    await viewportSweep(browser)
  } catch (error) {
    record('多尺寸巡检', false, error.message)
  }

  await browser.close()
  const failed = results.filter((item) => !item.ok)
  console.log('\n===== SUMMARY =====')
  console.log(`checks=${results.length} failed=${failed.length} consoleErrors=${errors.length}`)
  if (errors.length) console.log(errors.join('\n'))
  if (failed.length) {
    console.log(failed.map((item) => `${item.name}: ${item.detail}`).join('\n'))
    process.exitCode = 1
  }
}

main()
