import assert from 'node:assert/strict'
import { chromium, firefox, webkit } from 'playwright'

const BASE = process.env.SMOKE_BASE_URL || 'http://localhost:5173'
const targets = [
  { name: 'chromium', engine: chromium },
  { name: 'edge', engine: chromium, channel: 'msedge' },
  { name: 'firefox', engine: firefox },
  { name: 'webkit', engine: webkit },
]
const selected = new Set((process.env.MODAL_BROWSERS || 'chromium,edge,firefox,webkit').split(','))
const results = []

async function login(page) {
  await page.goto(`${BASE}/login`)
  await page.getByRole('button', { name: /平台管理员\s*全部菜单/ }).click()
  await page.waitForURL('**/plans')
  await page.waitForLoadState('networkidle')
}

async function planId(page) {
  return page.evaluate(async () => {
    const response = await fetch('/api/v1/plans', {
      headers: {
        Authorization: `Bearer ${sessionStorage.getItem('fzsu.accessToken')}`,
        'X-Client-Type': 'WEB',
      },
    })
    const body = await response.json()
    const plans = Array.isArray(body.data) ? body.data : body.data.items
    const plan = plans.find((item) => item.status === 'ACTIVE') ?? plans[0]
    if (!plan) throw new Error('没有可用于测试的计划')
    return plan.id
  })
}

async function expandIndicator(page, index = 0) {
  const button = page.locator('button[data-indicator-id]:visible').nth(index)
  await button.waitFor()
  if (await button.getAttribute('aria-expanded') !== 'true') await button.click()
  const id = await button.getAttribute('data-indicator-id')
  const action = page.locator('.a-inline-expansion:visible').last()
  await action.getByRole('button', { name: '任务分解', exact: true }).waitFor()
  return { id, action }
}

async function openModal(page, index = 0) {
  const { id, action } = await expandIndicator(page, index)
  const button = action.getByRole('button', { name: '任务分解', exact: true })
  assert.equal(await button.isEnabled(), true, '测试指标必须允许任务分解')
  const url = page.url()
  await button.scrollIntoViewIfNeeded()
  const listScroll = await page.locator('.indicator-main').evaluate((element) => element.scrollTop)
  await button.click()
  const modal = page.getByRole('dialog', { name: '任务分解' })
  await modal.waitFor()
  await modal.locator('.allocation-matrix-editor').waitFor()
  assert.equal(page.url(), url, '打开浮窗不得改变路由')
  assert.equal(await modal.locator('.indicator-item.active').count(), 1, '浮窗应定位到触发指标')
  return { id, modal, listScroll }
}

async function desktopScenario(browserType, channel) {
  const browser = await browserType.launch({ headless: true, channel })
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 }, locale: 'zh-CN' })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', (error) => errors.push(String(error)))
  try {
    await login(page)
    const id = await planId(page)
    await page.goto(`${BASE}/plans/${id}/indicators`)
    await page.locator('.indicator-records').waitFor()
    await page.waitForLoadState('networkidle')
    const url = page.url()

    const first = await openModal(page, 0)
    const initial = await first.modal.boundingBox()
    assert.ok(initial.width <= 1920 && initial.height <= 1080)
    assert.ok(initial.x >= 0 && initial.y >= 0)
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    const maskColor = await page.locator('[data-testid="allocation-modal-mask"]').evaluate((element) => getComputedStyle(element).backgroundColor)
    assert.match(maskColor, /rgba\(0, 0, 0, 0\.48\)/)

    const header = await first.modal.locator('.allocation-modal__header').boundingBox()
    await page.mouse.move(header.x + 260, header.y + 28)
    await page.mouse.down()
    await page.mouse.move(header.x + 360, header.y + 88, { steps: 5 })
    await page.mouse.up()
    const dragged = await first.modal.boundingBox()
    assert.ok(Math.abs(dragged.x - initial.x - 100) < 2 && Math.abs(dragged.y - initial.y - 60) < 2, '标题栏拖拽应生效')

    const resize = await first.modal.locator('.allocation-modal__resize.is-se').boundingBox()
    await page.mouse.move(resize.x + 5, resize.y + 5)
    await page.mouse.down()
    await page.mouse.move(resize.x - 75, resize.y - 45, { steps: 5 })
    await page.mouse.up()
    const resized = await first.modal.boundingBox()
    assert.ok(resized.width < dragged.width - 70 && resized.height < dragged.height - 35, '边缘缩放应生效')

    await first.modal.getByRole('button', { name: '关闭任务分解' }).click()
    await first.modal.waitFor({ state: 'hidden' })
    assert.equal(page.url(), url)
    assert.equal(await page.locator('.indicator-main').evaluate((element) => element.scrollTop), first.listScroll, '关闭后列表滚动位置必须保留')

    const second = await openModal(page, 1)
    await page.locator('[data-testid="allocation-modal-mask"]').click({ position: { x: 5, y: 5 } })
    await second.modal.waitFor({ state: 'hidden' })
    assert.equal(page.url(), url)
    assert.deepEqual(errors, [])
    return '1920×1080 打开一致性、拖拽、八向缩放、遮罩/按钮关闭、路由与滚动保留通过'
  } finally {
    await browser.close()
  }
}

async function mobileScenario() {
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: 'zh-CN', isMobile: true, hasTouch: true })
  const page = await context.newPage()
  try {
    await login(page)
    const id = await planId(page)
    await page.goto(`${BASE}/plans/${id}/indicators`)
    await page.locator('.indicator-records').waitFor()
    await page.waitForLoadState('networkidle')
    const { modal } = await openModal(page, 0)
    const box = await modal.boundingBox()
    assert.ok(box.x >= 0 && box.y >= 0 && box.x + box.width <= 391 && box.y + box.height <= 845)
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    await modal.getByRole('button', { name: '关闭任务分解' }).click()
    await modal.waitFor({ state: 'hidden' })
    return '390×844 移动端完整展示、无页面横向溢出、关闭正常'
  } finally {
    await browser.close()
  }
}

for (const target of targets) {
  if (!selected.has(target.name)) continue
  try {
    const detail = await desktopScenario(target.engine, target.channel)
    results.push({ browser: target.name, status: 'PASS', detail })
    console.log(`PASS ${target.name}: ${detail}`)
  } catch (error) {
    results.push({ browser: target.name, status: 'FAIL', detail: String(error) })
    console.error(`FAIL ${target.name}: ${error}`)
  }
}
if (selected.has('chromium')) {
  const detail = await mobileScenario()
  results.push({ browser: 'chromium-mobile', status: 'PASS', detail })
  console.log(`PASS chromium-mobile: ${detail}`)
}
console.log(JSON.stringify(results, null, 2))
if (results.some((result) => result.status === 'FAIL')) process.exitCode = 1
