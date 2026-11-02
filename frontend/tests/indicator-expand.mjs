import assert from 'node:assert/strict'
import { chromium, firefox, webkit } from 'playwright'

const BASE = process.env.SMOKE_BASE_URL || 'http://localhost:5173'
const DEFAULT_PLAN_ID = '2115975164633669633'
let PLAN_ID = process.env.SMOKE_PLAN_ID || DEFAULT_PLAN_ID
let OTHER_PLAN_ID = process.env.SMOKE_OTHER_PLAN_ID || ''
let PATH = `/plans/${PLAN_ID}/indicators`
let STORAGE_KEY = `fzsu.indicatorExpanded.${PLAN_ID}`
const FEISHU_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Lark/7.40.0'
const targets = [
  { name: 'chromium', engine: chromium },
  { name: 'edge', engine: chromium, channel: 'msedge' },
  { name: 'firefox', engine: firefox },
  { name: 'webkit', engine: webkit },
]
const selected = new Set((process.env.EXPAND_BROWSERS || 'chromium,edge,firefox,webkit').split(','))
const results = []

function expandButton(page, code, expanded = false) {
  return page.locator(`button[aria-label="${code}：${expanded ? '收起' : '展开'} 学院任务"]:visible`)
}

function taskRows(page) {
  return page.locator('.indicator-task-card:visible')
}

function directionButtons(page) {
  return page.locator('button[data-direction-id]:visible')
}

async function findTaskContinuously(page) {
  await taskRows(page).first().waitFor({ state: 'visible' })
}

async function login(page) {
  await page.goto(`${BASE}/login`)
  await page.getByRole('button', { name: /平台管理员\s*全部菜单/ }).click()
  await page.waitForURL('**/plans')
  await page.locator('.plan-grid').waitFor({ state: 'visible' })
  await page.waitForLoadState('networkidle')
}

async function resolvePlanContext(page) {
  const plans = await page.evaluate(async () => {
    const token = sessionStorage.getItem('fzsu.accessToken')
    const response = await fetch('/api/v1/plans', {
      headers: { Authorization: `Bearer ${token}`, 'X-Client-Type': 'WEB' },
    })
    const body = await response.json()
    if (!response.ok || body.code !== 0) throw new Error(`计划上下文读取失败：${body.message || response.status}`)
    return Array.isArray(body.data) ? body.data : (body.data?.items ?? [])
  })
  assert.ok(plans.length > 0, '至少需要一个有效计划执行指标专项')
  const selected = plans.find((item) => item.id === PLAN_ID) ?? plans[0]
  const other = plans.find((item) => item.id === OTHER_PLAN_ID && item.id !== selected.id)
    ?? plans.find((item) => item.id !== selected.id)
  PLAN_ID = selected.id
  OTHER_PLAN_ID = other?.id ?? ''
  PATH = `/plans/${PLAN_ID}/indicators`
  STORAGE_KEY = `fzsu.indicatorExpanded.${PLAN_ID}`
}

async function openIndicators(page) {
  await page.goto(`${BASE}${PATH}`)
  await page.locator('.indicator-records').waitFor({ state: 'visible' })
  await directionButtons(page).first().waitFor()
  await page.waitForLoadState('networkidle')
}

async function visibleIndicators(page) {
  return page.locator('button[data-indicator-id]:visible').evaluateAll((buttons) =>
    buttons.map((button) => ({
      id: button.getAttribute('data-indicator-id'),
      code: button.getAttribute('aria-label').split('：')[0],
    })),
  )
}

async function resetExpansion(page) {
  await page.evaluate((indicatorKey) => {
    localStorage.removeItem(indicatorKey)
  }, STORAGE_KEY)
  await page.reload()
  await directionButtons(page).first().waitFor()
  await page.locator('button[data-indicator-id]:visible').first().waitFor()
  await page.waitForLoadState('networkidle')
}

function makeTask(indicatorId, code, index) {
  const yearTargets = [2026, 2027, 2028, 2029, 2030].map((year, yearIndex) => ({
    year,
    target: yearIndex + 1,
  }))
  return {
    id: String(9100000000000000000n + BigInt(index)),
    planId: PLAN_ID,
    code: `${code}-C${String(index + 1).padStart(3, '0')}`,
    indicator: { id: indicatorId, code, name: `专项测试指标 ${code}`, statCaliber: 'INCREMENT', unitLabel: '项' },
    topIndicator: null,
    unit: { id: String(9200000000000000000n + BigInt(index)), code: 'C01', name: '智能产业学院' },
    owner: { id: '9300000000000000001', name: '测试负责人' },
    status: 'IN_PROGRESS',
    isHistorical: false,
    totalTarget: 15,
    yearTarget: 1,
    yearTargets,
    actualValue: 3,
    completionRate: 0.6,
    cumulativeActual: 8,
    cumulativeRate: 0.4,
    yearStatus: 'ACHIEVED',
    version: 1,
  }
}

async function installTaskMocks(page) {
  const state = {
    controls: new Map(),
    mockAll: false,
    requests: 0,
    active: 0,
    maxActive: 0,
    activeRequests: new Map(),
    nextRequestId: 0,
    peakRequests: [],
    statsRequests: 0,
    mockProgress: false,
  }
  await page.route('**/api/v1/plans/*/stats/tree*', async (route) => {
    state.statsRequests += 1
    if (!state.mockProgress) return route.continue()
    const response = await route.fetch()
    const body = await response.json()
    const decorate = (node) => {
      if (node.level === 'A') {
        node.actualValue = 12
        node.completionRate = 0.5
        node.cumulativeActual = 24
        node.cumulativeRate = 0.25
        node.achieveStatus = 'IN_PROGRESS'
      } else if (node.level === 'B') {
        node.actualValue = 3
        node.completionRate = 0.6
        node.cumulativeActual = 8
        node.cumulativeRate = 0.4
        node.yearStatus = 'ACHIEVED'
      }
      for (const child of node.children ?? []) decorate(child)
    }
    if (body.code === 0 && Array.isArray(body.data)) body.data.forEach(decorate)
    await route.fulfill({ response, body: JSON.stringify(body) })
  })
  await page.route('**/api/v1/tasks?*', async (route) => {
    const url = new URL(route.request().url())
    const indicatorId = url.searchParams.get('indicatorId')
    const control = state.controls.get(indicatorId)
    if (!control && !state.mockAll) return route.continue()
    const config = control || { count: 1, delay: 40 }
    const pageNumber = Number(url.searchParams.get('page') || 1)
    const requestId = ++state.nextRequestId
    state.requests += 1
    state.active += 1
    state.activeRequests.set(requestId, { indicatorId, page: pageNumber, count: config.count })
    state.maxActive = Math.max(state.maxActive, state.active)
    if (state.active >= 6) state.peakRequests = [...state.activeRequests.values()]
    let tracking = true
    const finish = () => {
      if (!tracking) return
      tracking = false
      state.active -= 1
      state.activeRequests.delete(requestId)
    }
    try {
      if (config.delay) await new Promise((resolve) => setTimeout(resolve, config.delay))
      finish()
      if (config.fail) {
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ code: 10000, message: '专项模拟加载失败', data: null, requestId: 'expand-mock-error' }),
        })
      }
      const pageSize = Number(url.searchParams.get('pageSize') || 100)
      const start = (pageNumber - 1) * pageSize
      const items = Array.from(
        { length: Math.max(0, Math.min(pageSize, config.count - start)) },
        (_, index) => makeTask(indicatorId, config.code || 'MOCK', start + index),
      )
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          message: 'OK',
          data: { items, total: config.count, page: pageNumber, pageSize },
          requestId: 'expand-mock-success',
        }),
      })
    } finally {
      finish()
    }
  })
  return state
}

async function runDirectionDesktop(page) {
  const allDirection = page.locator('button[data-direction-id=""]:visible')
  assert.match(await allDirection.innerText(), /全部方向\s*\d+/, '左侧应显示全部方向及指标数量')
  const firstDirection = directionButtons(page).nth(1)
  const directionName = (await firstDirection.innerText()).trim()
  if (PLAN_ID === DEFAULT_PLAN_ID) {
    assert.ok(directionName.includes('落实立德树人根本任务'), `首个方向排序异常：${directionName}`)
  }
  const indicatorStateBefore = await page.evaluate((key) => localStorage.getItem(key), STORAGE_KEY)
  await firstDirection.click()
  assert.equal(await firstDirection.getAttribute('aria-pressed'), 'true', '方向导航未进入选中态')
  assert.equal(await page.evaluate((key) => localStorage.getItem(key), STORAGE_KEY), indicatorStateBefore,
    '方向筛选不得改写 A 展开状态')
  assert.ok((await visibleIndicators(page)).length > 0, '选中方向后应保留该方向 A')

  await page.locator('input[placeholder="指标编码或名称"]').fill('__NO_MATCH_DIRECTION__')
  await page.waitForTimeout(700)
  assert.equal((await visibleIndicators(page)).length, 0, '关键字无匹配时仍显示 A')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page.locator('.more-filter-button').click()
  await page.getByRole('button', { name: '重置全部', exact: true }).click()
  await directionButtons(page).first().waitFor()
  await page.locator('button[data-indicator-id]:visible').first().waitFor()
  await page.waitForLoadState('networkidle')
  return { naturalSort: true, sidebarNavigation: true, independentExpandState: true, emptyResult: true }
}

async function runDesktop(page, state, errors) {
  state.mockProgress = true
  const statsRequestsBefore = state.statsRequests
  await resetExpansion(page)
  assert.equal(state.statsRequests - statsRequestsBefore, 1, '每次计划加载应恰好请求一次 stats/tree')
  const directions = await runDirectionDesktop(page)
  const indicators = await visibleIndicators(page)
  assert.ok(indicators.length >= 2, '至少两条量化 A 才能执行多节点测试')
  const first = indicators[0]
  const second = indicators[1]
  first.code = first.code || 'MOCK'
  second.code = second.code || 'MOCK'
  state.controls.set(first.id, { code: first.code, count: 0 })
  await resetExpansion(page)
  const emptySceneIndicators = await visibleIndicators(page)
  assert.ok(emptySceneIndicators.some((item) => item.id === first.id),
    `空 B 场景重置后目标 A 不可见：${JSON.stringify(emptySceneIndicators)}`)
  await expandButton(page, first.code).click()
  await expandButton(page, first.code).click()
  await expandButton(page, first.code).click()
  await page.waitForTimeout(1200)
  if (!await page.locator('.task-empty-state:visible').count()) {
    const button = expandButton(page, first.code)
    const expandedButton = expandButton(page, first.code, true)
    throw new Error(`空 B 快速点击状态异常：${JSON.stringify({
      collapsedButton: await button.count(),
      expandedButton: await expandedButton.count(),
      aria: await expandedButton.getAttribute('aria-expanded').catch(() => null),
      requests: state.requests,
      rowText: await expandedButton.locator('xpath=ancestor::tr').innerText().catch(() => ''),
    })}`)
  }
  assert.match(await page.locator('.task-empty-state:visible').innerText(), /暂无\s*学院任务/)
  assert.equal(await expandButton(page, first.code, true).getAttribute('aria-expanded'), 'true')
  const firstProgressText = await page.locator('.a-inline-expansion:visible .a-quant-summary').first().innerText()
  for (const expected of ['12', '50.0%', '25.0%']) {
    assert.ok(firstProgressText.includes(expected), `A 行内量化摘要缺少 ${expected}`)
  }

  await page.reload()
  await page.locator('.task-empty-state:visible').waitFor()
  assert.equal(await expandButton(page, first.code, true).getAttribute('aria-expanded'), 'true')
  assert.match((await page.evaluate((key) => localStorage.getItem(key), STORAGE_KEY)) || '', /\d+/)

  await page.locator('input[placeholder="指标编码或名称"]').fill(second.code)
  await page.waitForTimeout(500)
  await page.locator('.more-filter-button').click()
  await page.getByRole('button', { name: '重置全部', exact: true }).click()
  await expandButton(page, first.code, true).waitFor({ state: 'visible' })

  state.controls.set(first.id, { code: first.code, count: 2 })
  await resetExpansion(page)
  const requestsBeforeRepeat = state.requests
  await expandButton(page, first.code).click()
  await taskRows(page).first().waitFor({ state: 'visible' })
  const firstTaskText = await taskRows(page).first().innerText()
  for (const target of ['1', '2', '3', '4', '5']) assert.ok(firstTaskText.includes(target), `B 行缺少年度目标 ${target}`)
  for (const progress of ['60.0%', '40.0%', '已达标']) {
    assert.ok(firstTaskText.includes(progress), `B 行完成进度缺少 ${progress}`)
  }

  for (let index = 0; index < 5; index += 1) {
    await expandButton(page, first.code, true).click()
    await expandButton(page, first.code).waitFor({ state: 'visible' })
    await expandButton(page, first.code).click()
    await taskRows(page).first().waitFor({ state: 'visible' })
  }
  assert.equal(state.requests, requestsBeforeRepeat + 1, '首次展开加载一次，重复展开不得重复请求已缓存任务')

  state.controls.set(first.id, { code: first.code, count: 1, fail: true })
  await resetExpansion(page)
  await expandButton(page, first.code).click()
  await page.locator('.task-error-state:visible').first().waitFor()
  const errorDialog = page.locator('.el-overlay-message-box:visible')
  if (await errorDialog.count()) await errorDialog.locator('.el-message-box__btns button').first().click()
  state.controls.set(first.id, { code: first.code, count: 1 })
  await expandButton(page, first.code).click()
  await taskRows(page).first().waitFor({ state: 'visible' })
  assert.equal(await expandButton(page, first.code, true).getAttribute('aria-expanded'), 'true')
  const expectedErrorIndex = errors.findIndex((item) => item.includes('status of 500') && item.includes('/api/v1/tasks?indicatorId='))
  if (expectedErrorIndex >= 0) errors.splice(expectedErrorIndex, 1)

  state.controls.set(first.id, { code: first.code, count: 125, delay: 20 })
  const requestsBeforeLarge = state.requests
  await resetExpansion(page)
  await expandButton(page, first.code).click()
  await taskRows(page).first().waitFor({ state: 'visible' })
  assert.equal(state.requests - requestsBeforeLarge, 2, '125 条 B 应按 100 + 25 两页读取')
  assert.equal(await taskRows(page).count(), 125, '125 条 B 应在连续列表中完整展示')
  assert.equal(await page.locator('.table-footer .el-pagination').count(), 0, '指标总表不应再显示分页控件')
  await page.reload()
  await taskRows(page).first().waitFor({ state: 'visible' })
  assert.equal(await expandButton(page, first.code, true).getAttribute('aria-expanded'), 'true')

  state.mockAll = true
  await resetExpansion(page)
  state.active = 0
  state.maxActive = 0
  await page.getByRole('button', { name: '全部展开', exact: true }).click()
  await page.getByRole('button', { name: '全部收起', exact: true }).waitFor({ state: 'visible' })
  await page.waitForFunction(() => !document.querySelector('.expand-all-button.is-loading'))
  assert.ok(state.maxActive > 0 && state.maxActive <= 6,
    `全部展开并发应为 1–6，实际 ${state.maxActive}，峰值请求 ${JSON.stringify(state.peakRequests)}`)
  assert.ok(await page.locator('button[data-indicator-id]:visible').evaluateAll((buttons) =>
    buttons.filter((button) => !button.closest('tr')?.textContent?.includes('不可量化'))
      .every((button) => button.getAttribute('aria-expanded') === 'true')), '全部展开后仍有可量化 A 未展开')
  await page.getByRole('button', { name: '全部收起', exact: true }).click()
  await page.locator('button[data-indicator-id]:visible').first().waitFor()
  assert.ok(await page.locator('button[data-indicator-id]:visible').evaluateAll((buttons) =>
    buttons.every((button) => button.getAttribute('aria-expanded') === 'false')), '全部收起后仍有展开 A')
  assert.equal(await taskRows(page).count(), 0, '全部收起后仍显示 B')

  return {
    directions,
    statsTreeOnce: true,
    progress: true,
    empty: true,
    repeated: 5,
    persist: true,
    failureRetry: true,
    largeB: 125,
    maxConcurrent: state.maxActive,
    threeTierContinuous: true,
  }
}

async function runResponsive(page, state) {
  await page.setViewportSize({ width: 428, height: 926 })
  await resetExpansion(page)
  const [first] = await visibleIndicators(page)
  state.mockAll = false
  state.controls.set(first.id, { code: first.code, count: 3 })
  await resetExpansion(page)
  const directionSidebar = page.locator('.direction-sidebar:visible')
  await directionSidebar.waitFor()
  assert.ok(await directionSidebar.locator('button[data-direction-id]').count() > 0, '移动端应保留方向导航')
  await expandButton(page, first.code).click()
  await findTaskContinuously(page)
  const card = page.locator('.indicator-task-card:visible').first()
  await card.waitFor()
  assert.equal(await card.locator('xpath=ancestor::article[contains(@class,"indicator-card")]').count(), 1,
    '移动端 B 应嵌套在所属 A 卡片内')
  assert.equal(await card.locator('.indicator-year-target').count(), 5, '移动端 B 卡片应展示五年目标')
  const cardText = await card.innerText()
  assert.ok(cardText.includes('5'), '移动端 B 卡片缺末年目标')
  for (const progress of ['当前完成值', '当年完成率', '五年累计完成率', '60.0%', '40.0%', '已达标']) {
    assert.ok(cardText.includes(progress), `移动端 B 卡片缺少 ${progress}`)
  }
  let overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  assert.ok(overflow <= 1, `428px 视口横向溢出 ${overflow}px`)

  await page.setViewportSize({ width: 320, height: 700 })
  await findTaskContinuously(page)
  overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  assert.ok(overflow <= 1, `320px 视口横向溢出 ${overflow}px`)

  await page.setViewportSize({ width: 1024, height: 768 })
  await page.locator('.indicator-records').waitFor({ state: 'visible' })
  overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  assert.ok(overflow <= 1, `1024px 视口横向溢出 ${overflow}px`)
  return { mobile428: true, mobile320: true, tablet1024: true, fiveYearTargets: true, directionSidebar: true, nestedB: true, progress: true }
}

async function runPlanSwitchRace(page) {
  if (!OTHER_PLAN_ID) return { staleResponseBlocked: 'skipped', reason: '当前仅有一个有效计划' }
  await page.route(`**/api/v1/plans/${PLAN_ID}/indicators?*`, async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 700))
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'OK',
        data: [{
          id: '9999999999999999999', code: 'STALE-A', name: '旧计划慢响应', level: 'A',
          subjectType: 'SCHOOL', nature: 'DEVELOPMENT', status: 'DRAFT', isQuantifiable: true,
          children: [], yearTargets: [],
        }],
        requestId: 'expand-stale-response',
      }),
    })
  })
  await page.goto(`${BASE}${PATH}`)
  await page.waitForTimeout(50)
  await page.goto(`${BASE}/plans/${OTHER_PLAN_ID}/indicators`)
  await page.locator('.indicator-records').waitFor({ state: 'visible' })
  await page.locator('button[data-indicator-id]:visible').first().waitFor()
  await page.waitForTimeout(900)
  assert.equal(await page.getByText('STALE-A', { exact: true }).count(), 0, '旧计划慢响应覆盖新计划')
  return { staleResponseBlocked: true }
}

for (const target of targets.filter((item) => selected.has(item.name))) {
  const startedAt = Date.now()
  let browser
  try {
    browser = await target.engine.launch({ headless: true, channel: target.channel })
    const context = await browser.newContext({
      baseURL: BASE,
      viewport: { width: 1440, height: 900 },
      userAgent: target.name === 'webkit' ? FEISHU_UA : undefined,
    })
    const page = await context.newPage()
    const errors = []
    page.on('pageerror', (error) => errors.push(`${error.message} @ ${page.url()}`))
    page.on('console', (message) => {
      if (message.type() === 'error') {
        const location = message.location()
        errors.push(`${message.text()} @ ${location.url}:${location.lineNumber}`)
      }
    })
    page.on('requestfailed', (request) => {
      const failure = request.failure()?.errorText || 'Request failed'
      if (!failure.includes('ERR_ABORTED')) errors.push(`${failure} ${request.method()} ${request.url()}`)
    })
    await login(page)
    await resolvePlanContext(page)
    errors.length = 0
    await openIndicators(page)
    const state = await installTaskMocks(page)
    const desktop = await runDesktop(page, state, errors)
    const responsive = await runResponsive(page, state)
    await page.setViewportSize({ width: 1440, height: 900 })
    const race = await runPlanSwitchRace(page)
    assert.deepEqual(errors, [])
    results.push({ browser: target.name, status: 'passed', planId: PLAN_ID, otherPlanId: OTHER_PLAN_ID || null, desktop, responsive, race, errors, durationMs: Date.now() - startedAt })
    await context.close()
  } catch (error) {
    results.push({ browser: target.name, status: 'failed', message: error instanceof Error ? (error.stack || error.message) : String(error), durationMs: Date.now() - startedAt })
  } finally {
    await browser?.close()
  }
}

console.log(JSON.stringify(results, null, 2))
if (results.length === 0 || results.some((item) => item.status !== 'passed')) process.exitCode = 1
