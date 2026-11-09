import { chromium } from 'playwright'

const BASE_URL = process.env.H5_BASE_URL || 'http://localhost:5174'
const results = []
const consoleErrors = []

function record(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` — ${detail}` : ''}`)
}

async function login(page, username, password = 'Test@123456') {
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'domcontentloaded' })
  await page.locator('input[placeholder="工号"]').fill(username)
  await page.locator('input[placeholder="密码"]').fill(password)
  await page.locator('button:has-text("登 录")').click()
  await page.waitForURL((url) => !url.pathname.endsWith('/login'), { timeout: 20000 })
  await page.waitForTimeout(800)
}

async function main() {
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
    deviceScaleFactor: 2,
  })
  const page = await context.newPage()
  page.on('pageerror', (error) => consoleErrors.push(`pageerror: ${error.message}`))
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('401')) {
      consoleErrors.push(`console: ${message.text()}`)
    }
  })

  await login(page, 'admin')
  const planName = `H5自动化测试-${Date.now()}`

  // 1. 新建空白计划
  await page.locator('button:has-text("新建")').click()
  await page.waitForTimeout(400)
  await page.locator('button:has-text("下一步")').click()
  await page.locator('input[placeholder*="五年发展规划"]').fill(planName)
  await page.locator('button:has-text("创建计划")').click()
  await page.waitForURL(/\/plans\/\d+$/, { timeout: 20000 })
  await page.waitForTimeout(800)
  const newPlanId = page.url().split('/').pop()
  record('新建计划', /\/plans\/\d+$/.test(page.url()), `planId=${newPlanId}`)

  // 2. 指标总表新增非量化指标
  await page.locator('.module-item:has-text("指标总表")').click()
  await page.waitForURL(/\/indicators/, { timeout: 15000 })
  await page.waitForTimeout(1000)
  await page.locator('button:has-text("新增")').click()
  await page.waitForTimeout(500)
  const indicatorName = `H5自动化指标-${Date.now()}`
  await page.locator('.edit-sheet input').first().fill(indicatorName)
  const quantSwitch = page.locator('.edit-sheet .switch-field .van-switch').first()
  if (await quantSwitch.count()) await quantSwitch.click()
  await page.locator('button:has-text("创建指标")').click()
  await page.waitForTimeout(1800)
  record('新增非量化指标', (await page.locator('.indicator-name').filter({ hasText: indicatorName }).count()) > 0)

  // 3. 删除指标（验证编辑模式删除入口）
  const card = page.locator('.indicator-card').filter({ hasText: indicatorName }).first()
  if (await card.count()) {
    await card.locator('.action-link:has-text("更多")').click()
    await page.waitForTimeout(400)
    await page.locator('.van-action-sheet__item:has-text("删除指标")').click()
    await page.waitForTimeout(500)
    await page.locator('.van-dialog__confirm').click()
    await page.waitForTimeout(1200)
    record('删除指标', (await page.locator('.indicator-name').filter({ hasText: indicatorName }).count()) === 0)
  } else {
    record('删除指标', false, '未找到新建指标卡片')
  }

  // 4. 删除计划（草稿计划 + 原因）
  await page.goto(`${BASE_URL}/plans`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1000)
  const planCard = page.locator('.plan-card').filter({ hasText: planName }).first()
  if (await planCard.count()) {
    await planCard.locator('.more-btn').click()
    await page.waitForTimeout(400)
    await page.locator('.van-action-sheet__item:has-text("删除计划")').click()
    await page.waitForTimeout(400)
    await page.locator('.reason-sheet textarea').fill('H5 自动化测试清理')
    await page.locator('.reason-sheet button:has-text("确认删除")').click()
    await page.waitForTimeout(1500)
    record('删除计划', (await page.locator('.plan-card').filter({ hasText: planName }).count()) === 0)
  } else {
    record('删除计划', false, '未找到新建计划卡片')
  }

  // 5. 调整申请试算（不提交）
  await page.goto(`${BASE_URL}/adjustments/apply?taskId=2118170417664237569`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1800)
  const typeField = page.locator('.field:has(label:has-text("调整类型")) .van-field')
  if (await typeField.count()) {
    await typeField.click()
    await page.waitForTimeout(300)
    await page.locator('.type-option').filter({ hasText: '延期' }).first().click()
    await page.locator('.field:has(label:has-text("原年度")) .van-field').click()
    await page.waitForTimeout(300)
    await page.locator('.van-picker__confirm:visible').click()
    await page.waitForTimeout(300)
    const amountInput = page.locator('.field:has(label:has-text("调整数量")) input')
    if (await amountInput.count()) await amountInput.fill('1')
    await page.locator('.field:has(label:has-text("目标年份")) .van-field').click()
    await page.waitForTimeout(300)
    await page.locator('.van-picker__confirm:visible').click()
    await page.waitForTimeout(300)
    await page.locator('textarea').last().fill('H5 自动化测试试算，不提交')
    await page.locator('button:has-text("试算并预览")').click()
    await page.waitForTimeout(2000)
    const previewVisible = await page.locator('text=试算结果').count()
    record('调整申请试算预览', previewVisible > 0)
  } else {
    record('调整申请试算预览', false, '未加载到调整类型')
  }

  // 6. 字典新增 / 保存 / 删除
  await page.goto(`${BASE_URL}/system/dicts`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1200)
  await page.locator('button:has-text("新增字典项")').click()
  await page.waitForTimeout(300)
  const dictLabel = `H5测试${Date.now()}`
  const dictCode = `H5${Date.now().toString().slice(-8)}`
  const dictRow = page.locator('.dict-row').last()
  await dictRow.locator('input').nth(0).fill(dictCode)
  await dictRow.locator('input').nth(1).fill(dictLabel)
  await page.locator('button:has-text("保存")').click()
  await page.waitForTimeout(1500)
  const dictFound = await page.locator('.dict-row input').evaluateAll(
    (inputs, label) => inputs.some((input) => input.value === label),
    dictLabel,
  )
  record('字典新增并保存', dictFound)
  const dictIndex = await page.locator('.dict-row input').evaluateAll(
    (inputs, label) => inputs.findIndex((input) => input.value === label),
    dictLabel,
  )
  const rowIndex = Math.floor(dictIndex / 3)
  if (dictFound && rowIndex >= 0) {
    await page.locator('.dict-row').nth(rowIndex).locator('button:has-text("删除")').click()
    await page.waitForTimeout(300)
    await page.locator('.van-dialog__confirm').click()
    await page.waitForTimeout(300)
    await page.locator('button:has-text("保存")').click()
    await page.waitForTimeout(1500)
    const stillFound = await page.locator('.dict-row input').evaluateAll(
      (inputs, label) => inputs.some((input) => input.value === label),
      dictLabel,
    )
    record('字典删除并保存', !stillFound)
  } else {
    record('字典删除并保存', false, '新增行未找到')
  }

  await browser.close()
  const failed = results.filter((item) => !item.ok)
  console.log('\n===== DEEP SUMMARY =====')
  console.log(`checks=${results.length} failed=${failed.length} consoleErrors=${consoleErrors.length}`)
  if (failed.length) {
    console.log(failed.map((item) => `${item.name}: ${item.detail}`).join('\n'))
    process.exitCode = 1
  }
  if (consoleErrors.length) console.log(consoleErrors.join('\n'))
}

main()
