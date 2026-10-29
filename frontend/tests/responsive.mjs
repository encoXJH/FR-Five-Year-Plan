import { chromium } from 'playwright'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.SMOKE_BASE_URL || 'http://localhost:15173'
const PASSWORD = process.env.SMOKE_PASSWORD || 'Test@123456'
const PLAN_NAME = '智能生产装备与技术专业群建设规划（2026—2030年）'
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
  await page.goto(`${BASE}/login`)
  await page.locator('input[placeholder="工号"]').fill('admin')
  await page.locator('input[placeholder="密码"]').fill(PASSWORD)
  await page.getByRole('button', { name: /登\s*录/ }).click()
  await page.waitForURL('**/plans')
  await page.locator('.plan-grid').waitFor()
}

async function checkLayout(page, label) {
  await page.waitForTimeout(500)
  const layout = await page.evaluate(() => {
    const root = document.documentElement
    const visible = (element) => {
      const style = getComputedStyle(element)
      return style.display !== 'none' && style.visibility !== 'hidden'
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
    return {
      viewport: root.clientWidth,
      rootScrollWidth: root.scrollWidth,
      bodyScrollWidth: document.body.scrollWidth,
      offenders,
    }
  })
  const overflow = Math.max(layout.rootScrollWidth, layout.bodyScrollWidth) > layout.viewport + 1
  const record = { label, ...layout, overflow }
  results.push(record)
  if (overflow) failures.push(record)
  return layout
}

async function openPage(page, pathName, readySelector, label) {
  await page.goto(`${BASE}${pathName}`)
  await page.locator(readySelector).first().waitFor({ state: 'visible', timeout: 20000 })
  await checkLayout(page, label)
}

async function checkWizard(page, viewport) {
  if (viewport.width > 768) return
  const expand = page.locator('.indicator-card-expand:visible').first()
  if (await expand.count()) await expand.click()
  const open = page
    .getByRole('button', { name: /^(分解任务|继续分工)$/ })
    .filter({ visible: true })
    .first()
  if ((await open.count()) === 0) {
    results.push({ label: `${viewport.name}-wizard`, skipped: '当前计划已定稿，编辑向导由全流程冒烟覆盖' })
    return
  }
  await open.click()
  const drawer = page.locator('.decomposition-wizard:visible')
  await drawer.waitFor({ timeout: 10000 })
  await page.waitForTimeout(600)
  const geometry = await page.evaluate(() => {
    const drawer = document.querySelector('.decomposition-wizard')
    const save = Array.from(drawer?.querySelectorAll('button') ?? []).find((button) =>
      button.textContent?.includes('保存') && /保存( A\+|分工)/.test(button.textContent),
    )
    const drawerRect = drawer?.getBoundingClientRect()
    const saveRect = save?.getBoundingClientRect()
    return {
      drawerLeft: drawerRect?.left ?? null,
      drawerRight: drawerRect?.right ?? null,
      saveLeft: saveRect?.left ?? null,
      saveRight: saveRect?.right ?? null,
      viewport: window.innerWidth,
    }
  })
  const invalid =
    geometry.drawerLeft === null ||
    geometry.saveRight === null ||
    geometry.drawerLeft < -1 ||
    geometry.drawerRight > geometry.viewport + 1 ||
    geometry.saveLeft < -1 ||
    geometry.saveRight > geometry.viewport + 1
  results.push({ label: `${viewport.name}-wizard`, ...geometry, invalid })
  if (invalid) failures.push({ label: `${viewport.name}-wizard`, ...geometry })
  await page.screenshot({ path: path.join(ARTIFACTS, `${viewport.name}-wizard.png`) })
  await drawer.getByRole('button', { name: '取消', exact: true }).click()
  await drawer.waitFor({ state: 'hidden' })
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
    await login(page)
    await checkLayout(page, `${viewport.name}-plans`)
    await page.screenshot({ path: path.join(ARTIFACTS, `${viewport.name}-plans.png`) })

    const planCard = page.locator(
      `.plan-card:not(.plan-card--new):has(.plan-name:text-is("${PLAN_NAME}"))`,
    )
    if ((await planCard.count()) === 0) {
      throw new Error(`${viewport.name}: 未找到计划「${PLAN_NAME}」，请确认已加载权威测试数据且计划全名一致`)
    }
    await planCard.first().click()
    await page.waitForURL(/\/plans\/\d+$/)
    const planId = page.url().match(/\/plans\/(\d+)/)?.[1]
    if (!planId) throw new Error(`${viewport.name}: 无法获取计划 ID`)
    await page.locator('.kpi-row').waitFor()
    await checkLayout(page, `${viewport.name}-overview`)

    await openPage(page, `/plans/${planId}/indicators`, '.indicator-records', `${viewport.name}-indicators`)
    await page.screenshot({ path: path.join(ARTIFACTS, `${viewport.name}-indicators.png`) })
    await checkWizard(page, viewport)

    await openPage(page, `/plans/${planId}/allocation`, '.matrix-card', `${viewport.name}-allocation`)
    await page.screenshot({ path: path.join(ARTIFACTS, `${viewport.name}-allocation.png`) })
    await openPage(page, '/stats', '.stats-filter', `${viewport.name}-stats`)
    await openPage(page, '/workbench', '.kpi-row', `${viewport.name}-workbench`)

    if (pageErrors.length > 0) failures.push({ label: viewport.name, pageErrors })
    results.push({ label: `${viewport.name}-runtime`, pageErrors })
    await context.close()
  }
} finally {
  await browser.close()
}

console.log(JSON.stringify({ results, failures }, null, 2))
if (failures.length > 0) process.exitCode = 1
