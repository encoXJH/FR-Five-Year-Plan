import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const BASE = process.env.SMOKE_BASE_URL || 'http://localhost:5173'
const PASSWORD = 'Test@123456'
const YEARS = [2026, 2027, 2028, 2029, 2030]
const FIRST_DIRECTION = '落实立德树人根本任务'
const SECOND_DIRECTION = '深化产教融合协同育人'
const runId = `${Date.now()}`
const PLAN_NAME = `建设方向与学院分配专项-${runId}`
const INDICATOR_NAME = `关键产出指标分配专项-${runId}`
const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ARTIFACTS = path.join(__dirname, 'artifacts')
fs.mkdirSync(ARTIFACTS, { recursive: true })

const state = {
  planId: '',
  indicatorId: '',
  indicatorCode: '',
  firstDirectionId: '',
  secondDirectionId: '',
  taskIds: [],
}
const pageErrors = []
const consoleErrors = []
const checks = []
let browser
let context
let page

function fi(scope, label) {
  return scope.locator(`.el-form-item:has(> .el-form-item__label:text-is("${label}"))`)
}

async function pickOption(text, exact = false) {
  const selector = exact
    ? `.el-select-dropdown__item:visible:has(span:text-is("${text}"))`
    : `.el-select-dropdown__item:visible:has-text("${text}")`
  const item = page.locator(selector).first()
  await item.waitFor({ state: 'visible' })
  await item.click()
}

async function selectInFormItem(scope, label, optionText, type) {
  const item = fi(scope, label)
  await item.locator('.el-select__wrapper').click()
  if (type) await page.keyboard.type(type)
  await pickOption(optionText)
}

async function fillNumber(scope, value) {
  const input = scope.locator('input').first()
  await input.click()
  await input.fill(String(value))
  await input.press('Enter')
}

async function login(username = 'admin') {
  await page.goto(`${BASE}/login`)
  await page.locator('input[placeholder="工号"]').fill(username)
  await page.locator('input[placeholder="密码"]').fill(PASSWORD)
  await page.getByRole('button', { name: /登\s*录/ }).click()
  await page.waitForURL(username === 'admin' ? '**/plans' : /\/workbench|\/plans/)
}

async function api(method, resource, data, headers = {}) {
  const token = await page.evaluate(() => sessionStorage.getItem('fzsu.accessToken'))
  const response = await context.request.fetch(`${BASE}/api/v1${resource}`, {
    method,
    data,
    headers: {
      Authorization: `Bearer ${token}`,
      'X-Client-Type': 'WEB',
      ...headers,
    },
  })
  const body = await response.json().catch(() => null)
  if (!response.ok() || !body || body.code !== 0) {
    throw new Error(`${method} ${resource} 失败：HTTP ${response.status()} ${JSON.stringify(body)}`)
  }
  return body.data
}

async function waitToast(text) {
  await page.locator('.el-message').filter({ hasText: text }).first().waitFor({ timeout: 20000 })
}

async function openIndicators() {
  await page.goto(`${BASE}/plans/${state.planId}/indicators`)
  await page.locator('.indicator-records').waitFor({ state: 'visible' })
  await page.waitForLoadState('networkidle')
}

function indicatorRow() {
  return page.locator('.el-table__row:visible', { hasText: INDICATOR_NAME })
    .filter({ hasText: state.indicatorCode })
    .first()
}

function indicatorCard() {
  return page.locator('.indicator-card:not(.is-task):visible', { hasText: INDICATOR_NAME })
    .filter({ hasText: state.indicatorCode })
    .first()
}

async function ensureIndicatorExpanded() {
  const button = indicatorRow().locator(`button[data-indicator-id="${state.indicatorId}"]`)
  await button.waitFor()
  if (await button.getAttribute('aria-expanded') !== 'true') await button.click()
}

async function openIndicatorEditDrawer() {
  const row = indicatorRow()
  await row.waitFor()
  await row.getByRole('button', { name: /更多/ }).click()
  await page.locator('.el-dropdown-menu:visible').getByText('编辑', { exact: true }).click()
  const drawer = page.locator('.el-drawer:visible')
  await drawer.waitFor()
  await drawer.getByRole('button', { name: '保存修改', exact: true }).waitFor()
  return drawer
}

async function openEditDesktop() {
  await page.goto(`${BASE}/plans/${state.planId}/allocation?indicatorId=${state.indicatorId}`)
  await page.locator('.matrix-panel').waitFor()
  await page.locator('.allocation-matrix-editor').waitFor()
  return page
}

async function addAllocationRow(drawer, unit, targets) {
  const addBar = drawer.locator('.add-row-bar')
  await addBar.locator('.el-select__wrapper').click()
  await page.keyboard.type(unit.code)
  await pickOption(`${unit.code} ${unit.name}`)
  await addBar.getByRole('button', { name: '添加行', exact: true }).click()
  const row = drawer.locator('.matrix-card .el-table__row', { hasText: unit.code }).first()
  await row.waitFor()
  await row.locator('.el-select__wrapper').first().click()
  await page.keyboard.type(unit.owner)
  await pickOption(`（${unit.owner}）`)
  const inputs = row.locator('.cell-input input')
  for (let index = 0; index < YEARS.length; index += 1) {
    await inputs.nth(index).click()
    await inputs.nth(index).fill(String(targets[index]))
    await inputs.nth(index).press('Enter')
  }
}

async function setAllocationYear(drawer, unitCode, yearIndex, value) {
  const row = drawer.locator('.matrix-card .el-table__row', { hasText: unitCode }).first()
  await row.waitFor()
  const input = row.locator('.cell-input input').nth(yearIndex)
  await input.click()
  await input.fill(String(value))
  await input.press('Enter')
}

async function setIndicatorTargets(drawer, total, targets) {
  await fillNumber(fi(drawer, '五年总量'), total)
  const fields = fi(drawer, '各年度目标').locator('.year-target-field')
  for (let index = 0; index < YEARS.length; index += 1) {
    await fillNumber(fields.nth(index), targets[index])
  }
}

async function getIndicator() {
  const tree = await api('GET', `/plans/${state.planId}/indicators?view=tree`)
  const indicator = tree.find((item) => item.id === state.indicatorId)
  assert.ok(indicator, '总表指标树中应存在新建 A')
  return indicator
}

async function getAllocation() {
  return api('GET', `/indicators/${state.indicatorId}/allocation`)
}

async function getTasks() {
  const result = await api('GET', `/tasks?indicatorId=${state.indicatorId}&page=1&pageSize=100`)
  return result.items
}

async function checkNoOverflow(label) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  assert.ok(overflow <= 1, `${label} 横向溢出 ${overflow}px`)
}

async function run(name, fn) {
  await fn()
  checks.push(name)
  console.log(`PASS ${name}`)
}

async function setup() {
  browser = await chromium.launch({ headless: process.env.SMOKE_HEADFUL !== '1' })
  context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' })
  context.setDefaultTimeout(15000)
  context.setDefaultNavigationTimeout(25000)
  page = await context.newPage()
  page.on('pageerror', (error) => pageErrors.push(String(error)))
  page.on('console', (message) => {
    if (message.type() === 'error') {
      const location = message.location()
      consoleErrors.push(`${message.text()} @ ${location?.url ?? 'unknown'}`)
    }
  })
  page.on('response', (response) => {
    if (response.status() === 401) consoleErrors.push(`HTTP 401 @ ${response.url()}`)
  })
  await login()
}

async function createFixture() {
  const plan = await api('POST', '/plans', {
    name: PLAN_NAME,
    startYear: 2026,
    endYear: 2030,
    baselineYear: 2025,
    settleDeadline: '01-15',
    source: 'BLANK',
  })
  state.planId = plan.id
  const first = await api('POST', `/plans/${state.planId}/directions`, {
    name: FIRST_DIRECTION,
    sort: 1,
    leaderId: null,
    subjectType: 'SCHOOL',
  })
  const second = await api('POST', `/plans/${state.planId}/directions`, {
    name: SECOND_DIRECTION,
    sort: 2,
    leaderId: null,
    subjectType: 'SCHOOL',
  })
  state.firstDirectionId = first.id
  state.secondDirectionId = second.id
  console.log(`TEST_PLAN_ID=${state.planId}`)
}

async function createIndicatorWithAllocation() {
  await openIndicators()
  await page.getByRole('button', { name: '新增指标' }).first().click()
  const drawer = page.locator('.el-drawer:visible')
  await drawer.waitFor()
  assert.match(await fi(drawer, '主要建设方向').innerText(), new RegExp(FIRST_DIRECTION))
  await fi(drawer, '指标名称').locator('input').fill(INDICATOR_NAME)
  await fi(drawer, '计量单位').locator('input').fill('项')
  await selectInFormItem(drawer, '项目负责人', '（owner1）', 'owner1')
  await fi(drawer, '填写说明').locator('textarea').fill('专项测试：总表与学院子表 B 同步')
  await fi(drawer, '材料考核要求').locator('textarea').fill('成果文件与核验明细')
  await fillNumber(fi(drawer, '最少材料数'), 1)
  await setIndicatorTargets(drawer, 10, [2, 2, 2, 2, 2])

  assert.equal(await drawer.locator('.allocation-matrix-editor').count(), 0, '指标编辑抽屉不再包含学院分工')
  await drawer.getByRole('button', { name: '创建指标', exact: true }).click()
  await waitToast('指标与年度目标已保存')
  await drawer.waitFor({ state: 'hidden' })

  const tree = await api('GET', `/plans/${state.planId}/indicators?view=tree`)
  const created = tree.find((item) => item.name === INDICATOR_NAME)
  assert.ok(created, '创建后指标树应刷新')
  state.indicatorId = created.id
  state.indicatorCode = created.code
  assert.equal(created.directionId, state.firstDirectionId)
  assert.equal(created.totalTarget, 10)
  assert.deepEqual(created.yearTargets.map((item) => item.target), [2, 2, 2, 2, 2])

  const allocationPage = await openEditDesktop()
  await addAllocationRow(allocationPage, { code: 'C01', name: '智能产业学院', owner: 'c01' }, [1, 1, 1, 1, 1])
  await addAllocationRow(allocationPage, { code: 'C02', name: '数字金融产业学院', owner: 'c02' }, [1, 1, 1, 1, 1])
  await allocationPage.locator('.el-alert--success', { hasText: '各年度合计不低于指标目标' }).waitFor()
  await allocationPage.getByRole('button', { name: '存草稿', exact: true }).click()
  await waitToast('已保存')

  const detail = await api('GET', `/indicators/${state.indicatorId}`)
  assert.equal(detail.minFileCount, 1)
  assert.equal(detail.evidenceRequirement, '成果文件与核验明细')
  const allocation = await getAllocation()
  assert.equal(allocation.rows.length, 2)
  assert.equal(allocation.isBalanced, true)
  const tasks = await getTasks()
  assert.equal(tasks.length, 2)
  assert.deepEqual(tasks.map((task) => task.code).sort(), [`${state.indicatorCode}-C01`, `${state.indicatorCode}-C02`])
  assert.ok(tasks.every((task) => task.status === 'PENDING_DISTRIBUTION'))
  assert.ok(tasks.every((task) => task.yearTargets.length === 5))
  state.taskIds = tasks.map((task) => task.id)
}

async function verifyDirectionAndTable() {
  await openIndicators()
  const firstDirection = page.locator(`button[data-direction-id="${state.firstDirectionId}"]:visible`)
  const secondDirection = page.locator(`button[data-direction-id="${state.secondDirectionId}"]:visible`)
  await firstDirection.waitFor()
  assert.match(await firstDirection.innerText(), new RegExp(FIRST_DIRECTION))
  await secondDirection.click()
  await indicatorRow().waitFor({ state: 'hidden' })
  await firstDirection.click()
  await indicatorRow().waitFor()
  assert.equal(await firstDirection.getAttribute('aria-pressed'), 'true')

  await ensureIndicatorExpanded()
  const tasks = page.locator('.a-inline-expansion:visible .indicator-task-card')
  await tasks.first().waitFor()
  assert.equal(await tasks.count(), 2, '两条学院 B 应嵌在 A 的行内展开区')
  const yearText = await page.locator('.a-inline-expansion:visible .a-summary-years').innerText()
  for (const year of YEARS) assert.ok(yearText.includes(String(year)), `A 折叠区缺少年度 ${year}`)
  const verifyTask = async (code) => {
    const row = page.locator('.indicator-task-card:visible', { hasText: code }).first()
    await row.waitFor()
    const text = await row.innerText()
    assert.match(text, /当前完成/)
    assert.match(text, /当年完成率/)
    assert.match(text, /五年累计完成率/)
    assert.match(text, /增量/)
  }
  await verifyTask('C01')
  await verifyTask('C02')
}

async function verifyMobileEditor() {
  await page.setViewportSize({ width: 428, height: 926 })
  await openIndicators()
  const direction = page.locator(`button[data-direction-id="${state.firstDirectionId}"]:visible`)
  await direction.waitFor()
  assert.match(await direction.innerText(), new RegExp(FIRST_DIRECTION))
  await indicatorCard().getByRole('button', { name: '编辑指标', exact: true }).click()
  const drawer = page.locator('.el-drawer:visible')
  assert.equal(await drawer.locator('.allocation-matrix-editor').count(), 0)
  assert.equal(await drawer.getByRole('button', { name: '存草稿', exact: true }).count(), 0)
  await checkNoOverflow('428px 编辑抽屉')
  await page.setViewportSize({ width: 320, height: 700 })
  await checkNoOverflow('320px 编辑抽屉')
  await drawer.getByRole('button', { name: '取消', exact: true }).click()
  await drawer.waitFor({ state: 'hidden' })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await openIndicators()
}

async function editUnbalancedWithConflict() {
  const drawer = await openEditDesktop()
  await setAllocationYear(drawer, 'C01', 0, 0)
  await drawer.locator('.el-alert--warning', { hasText: '合计低于指标目标' }).waitFor()
  assert.equal(await drawer.locator('.cell-unbalanced, .col-unbalanced, .is-unbalanced').count(), 0, '差值提醒不应标红或锁定表格输入')
  assert.equal(await drawer.locator('.matrix-card .el-table__row .cell-input input').first().isEditable(), true)

  let conflict = true
  await page.route(`**/api/v1/indicators/${state.indicatorId}/allocation`, async (route) => {
    if (conflict && route.request().method() === 'PUT') {
      conflict = false
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({ code: 10005, message: '专项模拟版本冲突', data: null, requestId: 'allocation-e2e-10005' }),
      })
      return
    }
    await route.continue()
  })
  await drawer.getByRole('button', { name: '存草稿', exact: true }).click()
  const box = page.locator('.el-message-box:visible')
  await box.waitFor()
  assert.match(await box.innerText(), /10005/)
  await box.getByRole('button', { name: '保留我的修改并覆盖', exact: true }).click()
  await waitToast('已保存')
  await page.unroute(`**/api/v1/indicators/${state.indicatorId}/allocation`)

  const allocation = await getAllocation()
  assert.equal(allocation.isBalanced, false, '不平衡分工应允许保存草稿')
  const c01 = allocation.rows.find((row) => row.unit.code === 'C01')
  assert.equal(c01.yearTargets.find((item) => item.year === 2026).target, 0)
}

async function restoreBalanceAndDeleteAllocation() {
  let drawer = await openEditDesktop()
  await setAllocationYear(drawer, 'C02', 0, 3)
  await drawer.locator('.el-alert--success', { hasText: '各年度合计不低于指标目标' }).waitFor()
  await drawer.getByRole('button', { name: '存草稿', exact: true }).click()
  await waitToast('已保存')
  const overAllocated = await getAllocation()
  assert.equal(overAllocated.isBalanced, true)
  assert.equal(overAllocated.summary.find((item) => item.year === 2026).diff, 1, '超额分配应保留正差值且允许下发')

  await openIndicators()
  const editDrawer = await openIndicatorEditDrawer()
  await setIndicatorTargets(editDrawer, 6, [2, 1, 1, 1, 1])
  await editDrawer.getByRole('button', { name: '保存修改', exact: true }).click()
  await waitToast('指标与年度目标已保存')
  await editDrawer.waitFor({ state: 'hidden' })

  drawer = await openEditDesktop()
  const c02Row = drawer.locator('.matrix-card .el-table__row', { hasText: 'C02' }).first()
  await c02Row.getByRole('button', { name: '更多', exact: true }).click()
  await page.locator('.el-dropdown-menu:visible').getByText('删除行', { exact: true }).click()
  await setAllocationYear(drawer, 'C01', 0, 2)
  await drawer.locator('.el-alert--success', { hasText: '各年度合计不低于指标目标' }).waitFor()
  await drawer.getByRole('button', { name: '存草稿', exact: true }).click()
  await waitToast('已保存')

  const indicator = await getIndicator()
  assert.equal(indicator.totalTarget, 6)
  assert.deepEqual(indicator.yearTargets.map((item) => item.target), [2, 1, 1, 1, 1])
  const allocation = await getAllocation()
  assert.equal(allocation.rows.length, 1)
  assert.equal(allocation.rows[0].unit.code, 'C01')
  assert.equal(allocation.isBalanced, true)
  const tasks = await getTasks()
  assert.equal(tasks.length, 1)
  assert.equal(tasks[0].code, `${state.indicatorCode}-C01`)
  assert.deepEqual(tasks[0].yearTargets.map((item) => item.target), [2, 1, 1, 1, 1])

  await openIndicators()
  await ensureIndicatorExpanded()
  await page.locator('.indicator-task-card:visible', { hasText: 'C01' }).waitFor()
  assert.equal(await page.locator('.indicator-task-card:visible', { hasText: 'C02' }).count(), 0)
}

async function distributeAndVerifyReadOnly() {
  await page.goto(`${BASE}/plans/${state.planId}/allocation?indicatorId=${state.indicatorId}`)
  await page.locator('.matrix-panel').waitFor()
  await page.locator('.el-alert--success', { hasText: '各年度合计不低于指标目标' }).waitFor()
  const distribute = page.getByRole('button', { name: '正式交给学院', exact: true }).first()
  await distribute.waitFor()
  assert.equal(await distribute.isEnabled(), true)
  await distribute.click()
  let dialog = page.locator('.el-dialog:visible')
  await dialog.getByText(`${state.indicatorCode}-C01`, { exact: false }).first().waitFor()
  await dialog.getByRole('button', { name: '确认交给学院', exact: true }).click()
  await waitToast('已正式交给学院')
  await page.locator('.el-alert--info', { hasText: '可新增学院' }).waitFor()

  const indicator = await getIndicator()
  assert.equal(indicator.status, 'DISTRIBUTED')
  let tasks = await getTasks()
  assert.equal(tasks.length, 1)
  assert.equal(tasks[0].status, 'DISTRIBUTED')
  assert.equal(tasks[0].version > 0, true)
  const originalTask = tasks[0]

  const oldRow = page.locator('.matrix-card .el-table__row', { hasText: 'C01' }).first()
  assert.equal(await oldRow.locator('.cell-input input').count(), 0, '已下发旧学院目标必须只读')
  await addAllocationRow(page, { code: 'C03', name: '数字影视传媒产业学院', owner: 'c03' }, [1, 1, 1, 1, 1])
  await page.getByRole('button', { name: '存草稿', exact: true }).click()
  await waitToast('已保存')
  tasks = await getTasks()
  assert.equal(tasks.length, 2)
  const c01AfterAppend = tasks.find((task) => task.code.endsWith('-C01'))
  const c03Pending = tasks.find((task) => task.code.endsWith('-C03'))
  assert.deepEqual(c01AfterAppend.yearTargets.map((item) => item.target), originalTask.yearTargets.map((item) => item.target))
  assert.equal(c03Pending.status, 'PENDING_DISTRIBUTION')

  await distribute.click()
  dialog = page.locator('.el-dialog:visible')
  await dialog.getByText(`${state.indicatorCode}-C03`, { exact: false }).first().waitFor()
  assert.equal(await dialog.getByText(`${state.indicatorCode}-C01`, { exact: false }).count(), 0, '旧学院不得重复进入下发预览')
  await dialog.getByRole('button', { name: '确认交给学院', exact: true }).click()
  await waitToast('已正式交给学院')
  await page.waitForFunction(() => Array.from(document.querySelectorAll('button'))
    .find((button) => button.textContent?.trim() === '正式交给学院')?.disabled === true)
  tasks = await getTasks()
  assert.equal(tasks.length, 2)
  assert.ok(tasks.every((task) => task.status === 'DISTRIBUTED'))
  assert.equal(await distribute.isEnabled(), false, '无待下发新学院时不能重复下发')

  await page.setViewportSize({ width: 428, height: 926 })
  await page.locator('.allocation-mobile-card', { hasText: 'C01' }).waitFor()
  await page.locator('.allocation-mobile-card', { hasText: 'C03' }).waitFor()
  await checkNoOverflow('428px 已下发分工')
  await page.setViewportSize({ width: 320, height: 700 })
  await checkNoOverflow('320px 已下发分工')
  assert.equal(await page.getByRole('button', { name: '添加行', exact: true }).isEnabled(), false, '无可用学院时添加按钮禁用')
}

async function main() {
  await setup()
  try {
    await run('独立临时计划与两个建设方向', createFixture)
    await run('编辑抽屉仅创建 A，独立分解页维护多学院 B', createIndicatorWithAllocation)
    await run('方向侧导航、A 行内 B 折叠及完整量化展示', verifyDirectionAndTable)
    await run('428/320px 移动编辑与方向导航', verifyMobileEditor)
    await run('目标不足草稿与 10005 冲突覆盖恢复', editUnbalancedWithConflict)
    await run('分工回显、补足目标、删除 B 与总表同步', restoreBalanceAndDeleteAllocation)
    await run('首次下发、旧学院只读、追加学院并再次下发', distributeAndVerifyReadOnly)
    assert.deepEqual(pageErrors, [], `浏览器脚本错误：${pageErrors.join('\n')}`)
    const unexpectedConsole = consoleErrors.filter((item) =>
      !item.includes('allocation-e2e-10005')
      && !item.includes('409 (Conflict)')
      && !item.includes('/api/v1/auth/feishu/authorize-url'))
    assert.deepEqual(unexpectedConsole, [], `浏览器控制台错误：${unexpectedConsole.join('\n')}`)
    console.log(`RESULT PASS=${checks.length} FAIL=0`)
    console.log(`EVIDENCE ${JSON.stringify(state)}`)
  } catch (error) {
    const shot = path.join(ARTIFACTS, `direction-allocation-fail-${runId}.png`)
    await page.screenshot({ path: shot, fullPage: true }).catch(() => {})
    console.error(`FAIL ${error?.stack ?? error}`)
    console.error(`PAGE_URL=${page.url()}`)
    console.error(`SCREENSHOT=${shot}`)
    console.error(`EVIDENCE ${JSON.stringify(state)}`)
    process.exitCode = 1
  } finally {
    await browser.close()
  }
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
