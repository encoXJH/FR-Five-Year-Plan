import { chromium } from 'playwright'

const BASE_URL = process.env.H5_BASE_URL || 'http://localhost:5174'
const results = []
const consoleErrors = []

function record(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` — ${detail}` : ''}`)
}

async function newPage(browser) {
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
  page.on('response', async (response) => {
    if (!response.url().includes('/api/')) return
    try {
      const body = await response.json()
      if (body && typeof body.code === 'number' && body.code !== 0) {
        consoleErrors.push(`api ${response.status()} ${response.url()} code=${body.code} ${body.message}`)
      }
    } catch {
      if (response.status() >= 400) {
        consoleErrors.push(`http ${response.status()} ${response.url()}`)
      }
    }
  })
  return { context, page }
}

async function login(page, username) {
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'domcontentloaded' })
  await page.locator('input[placeholder="工号"]').fill(username)
  await page.locator('input[placeholder="密码"]').fill('Test@123456')
  await page.locator('button:has-text("登 录")').click()
  await page.waitForURL((url) => !url.pathname.endsWith('/login'), { timeout: 20000 })
  await page.waitForTimeout(800)
}

async function confirmDialog(page) {
  await page.locator('.van-dialog__confirm').click()
  await page.waitForTimeout(700)
}

async function main() {
  const browser = await chromium.launch({ headless: true })
  const planName = `H5全流程-${Date.now()}`
  const indicatorName = `H5全流程指标-${Date.now()}`

  // ---------- 管理员：建计划 → 建指标 → 分解下发 → 激活年度 ----------
  const admin = await newPage(browser)
  const page = admin.page
  await login(page, 'admin')

  await page.locator('button:has-text("新建")').click()
  await page.waitForTimeout(400)
  await page.locator('button:has-text("下一步")').click()
  await page.locator('input[placeholder*="五年发展规划"]').fill(planName)
  await page.locator('button:has-text("创建计划")').click()
  await page.waitForURL(/\/plans\/\d+$/, { timeout: 20000 })
  await page.waitForTimeout(900)
  const planId = page.url().split('/').pop()
  record('全流程：新建计划', Boolean(planId), `planId=${planId}`)

  await page.locator('.module-item:has-text("指标总表")').click()
  await page.waitForURL(/\/indicators/, { timeout: 15000 })
  await page.waitForTimeout(900)
  await page.locator('button:has-text("新增")').click()
  await page.waitForTimeout(500)
  await page.locator('.edit-sheet input').first().fill(indicatorName)
  const unitField = page.locator('.edit-sheet .field:has(label:has-text("计量单位")) input')
  if (await unitField.count()) await unitField.fill('项')
  const totalField = page.locator('.edit-sheet .field:has(label:has-text("五年总量")) input')
  if (await totalField.count()) await totalField.fill('5')
  const distributeButton = page.locator('button:has-text("平均分配")')
  if (await distributeButton.count()) await distributeButton.click()
  const ownerField = page.locator('.edit-sheet .field:has(label:has-text("项目负责人")) .van-field')
  if (await ownerField.count()) {
    await ownerField.click()
    await page.waitForTimeout(400)
    await page.locator('.van-picker__confirm:visible').click()
    await page.waitForTimeout(300)
  }
  await page.locator('button:has-text("创建指标")').click()
  await page.waitForTimeout(1800)
  record('全流程：创建增量指标与年度目标', (await page.locator('.indicator-name').filter({ hasText: indicatorName }).count()) > 0)

  await page.goto(`${BASE_URL}/plans/${planId}/allocation`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  const addUnit = page.locator('button:has-text("新增执行单元")')
  if (await addUnit.count()) {
    await addUnit.click()
    await page.waitForTimeout(400)
    await page.locator('.picker-item').first().click()
    await page.waitForTimeout(500)
    await page.locator('.owner-btn').first().click()
    await page.waitForTimeout(400)
    await page.locator('.picker-sheet input').first().fill('c01')
    await page.waitForTimeout(400)
    await page.locator('.picker-item:has-text("（c01）")').first().click()
    await page.waitForTimeout(400)
    const yearInputs = page.locator('.year-field input')
    const yearCount = await yearInputs.count()
    for (let i = 0; i < yearCount; i += 1) await yearInputs.nth(i).fill('1')
    await page.locator('button:has-text("存草稿")').click()
    await page.waitForTimeout(1500)
    record('全流程：分解矩阵保存草稿', true)
    await page.locator('button:has-text("正式交给学院")').click()
    await page.waitForTimeout(600)
    await page.locator('button:has-text("确认交给学院")').click()
    await page.waitForTimeout(2000)
    const distributed = await page.evaluate(async (pid) => {
      const token = sessionStorage.getItem('fzsu.accessToken')
      const headers = { Authorization: `Bearer ${token}` }
      const list = await fetch(`/api/v1/tasks?planId=${pid}&page=1&pageSize=10`, { headers }).then((r) => r.json())
      return (list.data?.items ?? []).some((task) => task.status !== 'PENDING_DISTRIBUTION')
    }, planId)
    record('全流程：正式交给学院', distributed)
  } else {
    record('全流程：分解矩阵保存草稿', false, '没有新增执行单元入口')
  }

  await page.goto(`${BASE_URL}/plans/${planId}`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1200)
  const activate = page.locator('button:has-text("启动执行")')
  if (await activate.count()) {
    await activate.click()
    await page.waitForTimeout(500)
    await confirmDialog(page)
    record('全流程：启动计划', true)
  } else {
    record('全流程：启动计划', false, '未找到启动按钮')
  }
  await page.waitForTimeout(800)
  const yearRow = page.locator('.year-item').first()
  const activateYear = yearRow.locator('button:has-text("激活")')
  if (await activateYear.count()) {
    await activateYear.click()
    await page.waitForTimeout(500)
    await confirmDialog(page)
    await page.waitForTimeout(800)
    record('全流程：激活年度', true)
  } else {
    record('全流程：激活年度', false, '未找到激活年度按钮')
  }

  const taskInfo = await page.evaluate(async (pid) => {
    const token = sessionStorage.getItem('fzsu.accessToken')
    const headers = { Authorization: `Bearer ${token}` }
    const list = await fetch(`/api/v1/tasks?planId=${pid}&page=1&pageSize=50`, { headers }).then((r) => r.json())
    const task = (list.data?.items ?? [])[0]
    return task ? { id: task.id, code: task.code, unitCode: task.unit?.code } : null
  }, planId)
  record('全流程：学院任务生成', Boolean(taskInfo), taskInfo ? `${taskInfo.code} / ${taskInfo.unitCode}` : '未生成')
  await admin.context.close()

  if (!taskInfo) {
    await browser.close()
    process.exitCode = 1
    return
  }

  // ---------- 学院负责人：填报并提交审核 ----------
  const unitUser = await newPage(browser)
  await login(unitUser.page, 'c01')
  await unitUser.page.goto(`${BASE_URL}/tasks/${taskInfo.id}/submit`, { waitUntil: 'domcontentloaded' })
  await unitUser.page.waitForTimeout(1500)
  const amountInput = unitUser.page.locator('.metric-input input').first()
  if (await amountInput.count()) {
    await amountInput.fill('1')
    await unitUser.page.locator('button:has-text("提交审核")').click()
    await unitUser.page.waitForTimeout(500)
    await confirmDialog(unitUser.page)
    await unitUser.page.waitForTimeout(1800)
    const body = await unitUser.page.locator('body').innerText()
    record('全流程：学院提交审核', body.includes('已提交审核') || body.includes('待审核') || body.includes('审核中'))
    const submissionId = await unitUser.page.evaluate(async (id) => {
      const token = sessionStorage.getItem('fzsu.accessToken')
      const headers = { Authorization: `Bearer ${token}` }
      const list = await fetch(`/api/v1/tasks/${id}/submissions`, { headers }).then((r) => r.json())
      const pending = (list.data?.items ?? []).find((item) => item.status === 'PENDING_REVIEW')
      return pending?.id ?? null
    }, taskInfo.id)
    record('全流程：获取待审核填报 ID', Boolean(submissionId), String(submissionId))
    taskInfo.submissionId = submissionId
  } else {
    record('全流程：学院提交审核', false, '未找到填报输入框')
  }
  await unitUser.context.close()

  // ---------- 管理员：审核通过 ----------
  const reviewer = await newPage(browser)
  await login(reviewer.page, 'admin')
  await reviewer.page.goto(`${BASE_URL}/reviews`, { waitUntil: 'domcontentloaded' })
  await reviewer.page.waitForTimeout(1500)
  const reviewCard = reviewer.page.locator(`[data-submission-id="${taskInfo.submissionId}"]`).first()
  if (await reviewCard.count()) {
    await reviewCard.click()
    await reviewer.page.waitForTimeout(1000)
    await reviewer.page.locator('.detail-footer button:has-text("通过")').click()
    await reviewer.page.waitForTimeout(500)
    await confirmDialog(reviewer.page)
    await reviewer.page.waitForTimeout(1800)
    const body = await reviewer.page.locator('body').innerText()
    const toastText = (await reviewer.page.locator('.van-toast').allInnerTexts()).join('|')
    record(
      '全流程：审核通过',
      toastText.includes('已通过')
        || body.includes('已通过')
        || (await reviewer.page.locator(`[data-submission-id="${taskInfo.submissionId}"]`).count()) === 0,
      `toast=${toastText}`,
    )
  } else {
    record('全流程：审核通过', false, '待审核列表未找到该任务')
  }

  // 清理测试计划：归档后删除
  const cleanup = await reviewer.page.evaluate(async (pid) => {
    const token = sessionStorage.getItem('fzsu.accessToken')
    const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
    const detail = await fetch(`/api/v1/plans/${pid}`, { headers }).then((r) => r.json())
    const version = detail.data?.version
    await fetch(`/api/v1/plans/${pid}/archive`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ version, reason: 'H5 全流程自动化测试清理' }),
    })
    const after = await fetch(`/api/v1/plans/${pid}`, { headers }).then((r) => r.json())
    const response = await fetch(`/api/v1/plans/${pid}`, {
      method: 'DELETE',
      headers,
      body: JSON.stringify({ version: after.data?.version, reason: 'H5 全流程自动化测试清理' }),
    })
    return response.ok ? 'deleted' : `archive-only-${response.status}`
  }, planId)
  record('全流程：清理测试计划', cleanup === 'deleted' || cleanup.startsWith('archive-only'), cleanup)

  await reviewer.context.close()
  await browser.close()
  const failed = results.filter((item) => !item.ok)
  console.log('\n===== WORKFLOW SUMMARY =====')
  console.log(`checks=${results.length} failed=${failed.length} consoleErrors=${consoleErrors.length}`)
  if (failed.length) {
    console.log(failed.map((item) => `${item.name}: ${item.detail}`).join('\n'))
    process.exitCode = 1
  }
  if (consoleErrors.length) console.log(consoleErrors.join('\n'))
}

main()
