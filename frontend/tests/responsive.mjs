import { chromium } from 'playwright'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.SMOKE_BASE_URL || 'http://localhost:5173'
const PASSWORD = process.env.SMOKE_PASSWORD || 'Test@123456'
const PLAN_NAME = process.env.SMOKE_PLAN_NAME || ''
const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ARTIFACTS = path.join(__dirname, 'artifacts', 'responsive')
fs.mkdirSync(ARTIFACTS, { recursive: true })

const FEISHU_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Lark/7.40.0'
const viewports = [
  { name: 'desktop', width: 1280, height: 900, touch: false, feishu: false },
  { name: 'tablet', width: 768, height: 1024, touch: true, feishu: true },
  { name: 'mobile', width: 428, height: 926, touch: true, feishu: true },
]
const failures = []
const results = []

async function login(page) {
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  // 飞书UA下会自动尝试JSAPI免登，等待降级到账号密码表单
  await page.locator('input[placeholder="工号"]').waitFor({ state: 'visible', timeout: 30000 })
  await page.locator('input[placeholder="工号"]').fill('admin')
  await page.locator('input[placeholder="密码"]').fill(PASSWORD)
  await page.getByRole('button', { name: '登 录', exact: true }).click()
  await page.waitForURL('**/plans')
  await page.locator('.plan-grid').waitFor()
}

async function checkLayout(page, label) {
  await page.waitForTimeout(500)
  const layout = await page.evaluate(() => {
    const root = document.documentElement
    const visible = (element) => {
      const style = getComputedStyle(element)
      return style.display !== 'none' && style.visibility !== 'hidden' && element.getClientRects().length > 0
    }
    const offenders = Array.from(document.querySelectorAll('body *'))
      .filter((element) => {
        if (!visible(element)) return false
        const rect = element.getBoundingClientRect()
        return rect.width > 0 && (rect.right > window.innerWidth + 2 || rect.left < -2)
      })
      .slice(0, 8)
      .map((element) => ({
        tag: element.tagName,
        className: typeof element.className === 'string' ? element.className : '',
        left: Math.round(element.getBoundingClientRect().left),
        right: Math.round(element.getBoundingClientRect().right),
      }))
    const actionIssues = window.innerWidth <= 768
      ? Array.from(document.querySelectorAll('.app-action-row .el-button'))
        .filter((element) => visible(element) && element.getBoundingClientRect().height < 43.5)
        .map((element) => element.textContent?.trim())
      : []
    const rightAlignedActions = Array.from(document.querySelectorAll('.app-action-row'))
      .filter((element) => visible(element) && getComputedStyle(element).justifyContent === 'flex-end')
      .length
    return {
      viewport: root.clientWidth,
      rootScrollWidth: root.scrollWidth,
      bodyScrollWidth: document.body.scrollWidth,
      independentAPlusText: document.body.innerText.includes('A+'),
      offenders,
      actionIssues,
      rightAlignedActions,
    }
  })
  const overflow = Math.max(layout.rootScrollWidth, layout.bodyScrollWidth) > layout.viewport + 1
  const record = { label, ...layout, overflow }
  results.push(record)
  if (overflow) failures.push(record)
  if (layout.independentAPlusText) failures.push({ label: `${label}-a-plus`, independentAPlusText: true })
  if (layout.actionIssues.length > 0) failures.push({ label: `${label}-action-size`, actionIssues: layout.actionIssues })
  if (layout.rightAlignedActions > 0) failures.push({ label: `${label}-action-alignment`, rightAlignedActions: layout.rightAlignedActions })
  return layout
}

async function openPage(page, pathName, readySelector, label) {
  await page.goto(`${BASE}${pathName}`)
  await page.locator(readySelector).first().waitFor({ state: 'visible', timeout: 20000 })
  await checkLayout(page, label)
}

async function checkAllocationEntry(page, viewport) {
  if (viewport.width > 768) return
  const expand = page.locator('.indicator-card-expand:visible').first()
  if (await expand.count()) await expand.click()
  const open = page
    .getByRole('button', { name: /^(分解任务|分工管理)$/ })
    .filter({ visible: true })
    .first()
  if ((await open.count()) === 0) {
    results.push({ label: `${viewport.name}-allocation-entry`, skipped: '当前计划没有可分工对象' })
    return
  }
  await open.click()
  await page.waitForURL(/\/allocation\?indicatorId=\d+/)
  await page.locator('.matrix-card').waitFor()
  await checkLayout(page, `${viewport.name}-allocation-entry`)
  const legacyReadonly = (await page.locator('body').innerText()).includes('历史分解数据')
  if (legacyReadonly) {
    const editableControls = await page.locator('.add-row-bar:visible, .sticky-bar:visible').count()
    results.push({ label: `${viewport.name}-legacy-readonly`, editableControls })
    if (editableControls !== 0) failures.push({ label: `${viewport.name}-legacy-readonly`, editableControls })
  }
  await page.screenshot({ path: path.join(ARTIFACTS, `${viewport.name}-allocation-entry.png`) })
  await page.goBack()
  await page.locator('.indicator-records').waitFor()
}

const browser = await chromium.launch({ headless: process.env.SMOKE_HEADFUL !== '1' })
try {
  for (const viewport of viewports) {
    const context = await browser.newContext({
      baseURL: BASE,
      viewport: { width: viewport.width, height: viewport.height },
      hasTouch: viewport.touch,
      isMobile: viewport.touch,
      userAgent: viewport.feishu ? FEISHU_UA : undefined,
    })
    const page = await context.newPage()
    const pageErrors = []
    page.on('pageerror', (error) => pageErrors.push(error.message))
    // 非飞书环境下飞书SDK CDN可能不可达，拦截使其快速失败降级到账号密码表单
    await page.route('**/lf1-cdn-tos.bytegoofy.com/**', (route) => route.abort())
    await login(page)
    await checkLayout(page, `${viewport.name}-plans`)
    await page.screenshot({ path: path.join(ARTIFACTS, `${viewport.name}-plans.png`) })

    const planCard = PLAN_NAME
      ? page.locator(`.plan-card:not(.plan-card--new):has(.plan-name:text-is("${PLAN_NAME}"))`)
      : page.locator('.plan-card:not(.plan-card--new)').first()
    if ((await planCard.count()) === 0) {
      throw new Error(`${viewport.name}: 未找到可验收计划`)
    }
    await planCard.first().click()
    await page.waitForURL(/\/plans\/\d+\/indicators$/)
    const planId = page.url().match(/\/plans\/(\d+)/)?.[1]
    if (!planId) throw new Error(`${viewport.name}: 无法获取计划 ID`)
    await openPage(page, `/plans/${planId}`, '.page-title', `${viewport.name}-overview`)

    await openPage(page, `/plans/${planId}/indicators`, '.indicator-records', `${viewport.name}-indicators`)
    await page.screenshot({ path: path.join(ARTIFACTS, `${viewport.name}-indicators.png`) })
    if (await page.getByRole('button', { name: '计划甘特图', exact: true }).count()) {
      failures.push({ label: `${viewport.name}-standalone-gantt-entry`, message: '独立甘特图入口未移除' })
    }
    await page.getByRole('button', { name: '数据管理', exact: true }).click()
    await page.locator('.el-dropdown-menu:visible').waitFor()
    if (await page.getByRole('menuitem', { name: '导出 Excel', exact: true }).count() === 0) {
      failures.push({ label: `${viewport.name}-indicator-data-menu`, message: '缺少导出 Excel' })
    }
    await page.keyboard.press('Escape')
    await checkAllocationEntry(page, viewport)

    if (await page.getByRole('button', { name: /^(查看详情|详情)$/ }).count() === 0) {
      await page.locator('.el-table__expand-icon:visible, .indicator-card-expand:visible').first().click()
    }
    await page.getByRole('button', { name: /^(查看详情|详情)$/ }).first().click()
    await page.waitForURL(/\/indicators\/\d+/)
    await page.locator('.indicator-gantt').first().waitFor()
    await checkLayout(page, `${viewport.name}-indicator-gantt`)
    await page.screenshot({ path: path.join(ARTIFACTS, `${viewport.name}-indicator-gantt.png`) })

    await openPage(page, `/plans/${planId}/allocation`, '.matrix-card', `${viewport.name}-allocation`)
    await page.screenshot({ path: path.join(ARTIFACTS, `${viewport.name}-allocation.png`) })
    await openPage(page, '/stats', '.stats-filter', `${viewport.name}-stats`)
    await openPage(page, '/workbench', '.workbench-page', `${viewport.name}-workbench`)
    await openPage(page, '/todos', '.el-tabs', `${viewport.name}-todos`)
    await openPage(page, '/materials', '.page-title', `${viewport.name}-materials`)
    await openPage(page, '/reviews', '.page-title', `${viewport.name}-reviews`)
    await openPage(page, '/adjustments', '.page-title', `${viewport.name}-adjustments`)
    await openPage(page, '/templates', '.page-title', `${viewport.name}-templates`)
    await openPage(page, '/logs', '.page-title', `${viewport.name}-logs`)
    await openPage(page, '/system', '.page-title', `${viewport.name}-system`)
    for (const tab of ['执行单元', '主要建设方向', '专业群', '用户与角色', '字典', '参数配置', '飞书集成']) {
      const tabButton = page.getByRole('tab', { name: tab, exact: true })
      if (await tabButton.count()) {
        await tabButton.click()
        await checkLayout(page, `${viewport.name}-system-${tab}`)
        if (tab === '用户与角色' && await page.locator('.more-dropdown:visible').count()) {
          await page.locator('.more-dropdown:visible').first().click()
          await page.locator('.el-dropdown-menu:visible').waitFor()
          await page.keyboard.press('Escape')
        }
      }
    }

    if (pageErrors.length > 0) failures.push({ label: viewport.name, pageErrors })
    results.push({ label: `${viewport.name}-runtime`, pageErrors })
    await context.close()
  }
} finally {
  await browser.close()
}

console.log(JSON.stringify({ results, failures }, null, 2))
if (failures.length > 0) process.exitCode = 1
