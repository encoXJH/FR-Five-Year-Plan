/**
 * 端到端冒烟脚本（Playwright 库 API，headless）
 *
 * 主流程：登录 admin → 新建五年计划 → 配置 S / 总负责人 → 录指标（A/A+，逐年目标与 S 归属）→ 定稿
 *        → 分解下发到 C01 → 启动计划 + 激活 2026 → c01 接收/填报（含 PDF 材料）
 *        → owner1 审核通过 → c01 发起延期 → owner1 初审 → leader 终审
 *        → c01 确认「已生效」→ admin 大屏断言
 *
 * 运行：npm run smoke（需后端 :8080 与前端 :5173 已启动）
 * 环境变量：SMOKE_BASE_URL（默认 http://localhost:5173）、SMOKE_HEADFUL=1 可视化调试
 *           SMOKE_PATCH_KNOWN_BUGS=1 已确认缺陷补丁模式（见下）
 *
 * 已确认产品缺陷（默认模式下脚本会如实 FAIL 暴露，不绕过）：
 *  Bug#1 POST /auth/login 响应缺少 data.user（API 约定 16.1 要求返回
 *        { accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt, user }，
 *        实际返回 { accessToken, refreshToken, expiresIn, tokenType }），
 *        前端 stores/user.ts login() 访问 data.user.roles 抛 TypeError 被静默吞掉，
 *        表现为：点击登录后停留登录页、无任何提示。
 *  Bug#2 前端新建建设方向（指标抽屉 allow-create）未传 subjectType，
 *        后端 DirectionDtos.CreateRequest 要求 @NotBlank subjectType → 400「主体必填」（10001），
 *        UI 上没有任何入口可填主体，新增方向必然失败，进而无法创建 A 指标。
 *  Bug#3 前端 finalizeIndicators()（api/indicator.ts）POST 定稿不带 body，
 *        后端 FinalizeRequest 要求 version → 400「缺少 version（乐观锁版本号）」，定稿必然失败。
 *  Bug#4 前端 activatePlan()（api/plan.ts）POST 启动计划不带 version → 同上 400，启动必然失败。
 *  Bug#5 前端 acceptTask()（api/task.ts）POST 确认接收不带 version → 同上 400，接收必然失败。
 *  Bug#6 GET /tasks/{id}/submissions 返回分页结构 { items, total, ... }，
 *        前端 listSubmissions()（api/submission.ts）按 SubmissionListItem[] 使用，
 *        填报页 populate() 触发 submissions.filter 抛 TypeError → 页面显示「任务加载失败」。
 *  Bug#7 前端调整试算 buildRequest()（adjustment/apply.vue）不带 reason，
 *        后端 POST /adjustments/preview 要求 reason 必填 → 400（10001），试算必然失败，
 *        且失败仅闪现 toast、页面无错误 alert 残留，用户难以理解。
 *
 * SMOKE_PATCH_KNOWN_BUGS=1 时通过 Playwright page.route 在网络层修补上述
 * 已确认缺陷的载荷（login 响应按约定补 user；directions 请求补 subjectType=SCHOOL；
 * finalize/activate/accept 请求补当前资源 version；submissions 响应分页对象降级为 items 数组；
 * adjustments/preview 请求补 reason），
 * 用于在缺陷修复前验证其余全链路。全程仍为真实 UI 操作，输出中打印醒目警告。
 */
import { chromium } from 'playwright'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.SMOKE_BASE_URL || 'http://localhost:5173'
const PASSWORD = 'Test@123456'
/** 已确认缺陷补丁模式：默认关闭，严格走 UI 并如实 FAIL；开启后用 page.route 修补载荷 */
const PATCH_KNOWN_BUGS = process.env.SMOKE_PATCH_KNOWN_BUGS === '1'
const patchedBugs = []
const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ARTIFACTS = path.join(__dirname, 'artifacts')
fs.mkdirSync(ARTIFACTS, { recursive: true })

const now = new Date()
const p2 = (n) => String(n).padStart(2, '0')
const ts = `${now.getFullYear()}${p2(now.getMonth() + 1)}${p2(now.getDate())}-${p2(now.getHours())}${p2(now.getMinutes())}${p2(now.getSeconds())}`

const PLAN_NAME = `冒烟规划-${ts}`
const DIRECTION_NAME = `冒烟S级-${ts}`
const A_NAME = `冒烟A指标-${ts}`
const AP_NAME = `冒烟A+指标-${ts}`
const YEARS = [2026, 2027, 2028, 2029, 2030]

const state = { planId: '', taskCode: '' }

let browser
let context
let page

// ---------- 通用小助手 ----------
/** 按 label 精确文本定位 el-form-item */
const fi = (scope, label) =>
  scope.locator(`.el-form-item:has(> .el-form-item__label:text-is("${label}"))`)

async function waitToast(text, timeout = 15000) {
  await page.locator('.el-message').filter({ hasText: text }).first().waitFor({ timeout })
}

/** ElMessageBox 确认框：点击指定按钮并等待关闭 */
async function confirmBox(buttonName) {
  const box = page.locator('.el-message-box')
  await box.waitFor({ state: 'visible' })
  await box.getByRole('button', { name: buttonName, exact: true }).click()
  await box.waitFor({ state: 'hidden' })
}

/** 在当前打开的 el-select 下拉中选择一项（选项自身 :visible；exact 走 span:text-is，因为文本在 li>span 里，li:text-is 不匹配） */
async function pickOption(text, exact = false) {
  const sel = exact
    ? `.el-select-dropdown__item:visible:has(span:text-is("${text}"))`
    : `.el-select-dropdown__item:visible:has-text("${text}")`
  const item = page.locator(sel).first()
  await item.waitFor({ state: 'visible' })
  try {
    await item.click({ timeout: 3000 })
  } catch {
    // EP popper 打开动画期间 playwright 的 stable 判定可能持续失败，降级 force click
    await item.click({ force: true })
  }
}

/** 打开某个表单项里的 el-select 并选项（filterable 可先输入过滤） */
async function selectInFormItem(scope, label, optionText, { type, exact = false } = {}) {
  const item = fi(scope, label)
  await item.locator('.el-select__wrapper').click()
  if (type) await page.keyboard.type(type)
  await pickOption(optionText, exact)
}

/** 填 el-input-number（Enter 提交值） */
async function fillNumber(scope, value) {
  const input = scope.locator('input').first()
  await input.click()
  await input.fill(String(value))
  await input.press('Enter')
}

async function login(username) {
  await page.goto(`${BASE}/login`)
  const userInput = page.locator('input[placeholder="工号"]')
  await userInput.waitFor()
  await userInput.fill(username)
  await page.locator('input[placeholder="密码"]').fill(PASSWORD)
  await page.getByRole('button', { name: /登\s*录/ }).click()
  await page.waitForURL('**/plans')
  await page.locator('.page-title', { hasText: '我的计划' }).waitFor()
}

/** 安装已确认缺陷的网络补丁（仅 SMOKE_PATCH_KNOWN_BUGS=1；命中时记录 patchedBugs） */
async function installKnownBugPatches() {
  // Bug#1：POST /auth/login 响应按 API 约定 16.1 补 data.user（取自 /auth/me）
  await page.route('**/api/v1/auth/login', async (route) => {
    const response = await route.fetch()
    const body = await response.json().catch(() => null)
    if (body && body.code === 0 && body.data && !body.data.user) {
      const me = await page.context().request.get(`${BASE}/api/v1/auth/me`, {
        headers: { Authorization: `Bearer ${body.data.accessToken}`, 'X-Client-Type': 'WEB' },
      })
      const meBody = await me.json().catch(() => null)
      const u = meBody?.data?.user
      if (u) {
        body.data.user = {
          id: u.id,
          name: u.name,
          unit: u.unitId ? { id: u.unitId, code: null, name: u.unitName ?? null } : null,
          roles: meBody?.data?.roles ?? [],
        }
        if (!patchedBugs.includes('Bug#1 login.user')) patchedBugs.push('Bug#1 login.user')
      }
      await route.fulfill({ response, json: body })
      return
    }
    await route.fulfill({ response })
  })
  // Bug#2：新建方向请求补 subjectType=SCHOOL（前端漏传，后端必填）
  await page.route('**/api/v1/plans/*/directions', async (route) => {
    const request = route.request()
    if (request.method() === 'POST') {
      const data = request.postDataJSON()
      if (data && !data.subjectType) {
        data.subjectType = 'SCHOOL'
        if (!patchedBugs.includes('Bug#2 direction.subjectType')) {
          patchedBugs.push('Bug#2 direction.subjectType')
        }
        await route.continue({ postData: JSON.stringify(data) })
        return
      }
    }
    await route.continue()
  })
  // Bug#3/4/5：finalize / activate / accept 请求补当前资源的 version（前端漏传）
  const versionPatch = (pattern, resourceOf, label) =>
    page.route(pattern, async (route) => {
      const request = route.request()
      if (request.method() !== 'POST') {
        await route.continue()
        return
      }
      let data = null
      try {
        data = request.postDataJSON()
      } catch {
        data = null
      }
      if (!data || data.version === undefined || data.version === null) {
        const auth = request.headers()['authorization'] ?? ''
        const resp = await page
          .context()
          .request.get(`${BASE}${resourceOf(request.url())}`, {
            headers: { Authorization: auth, 'X-Client-Type': 'WEB' },
          })
          .catch(() => null)
        const body = resp ? await resp.json().catch(() => null) : null
        const version = body?.data?.version
        if (version !== undefined && version !== null) {
          const next = { ...(data ?? {}), version }
          if (!patchedBugs.includes(label)) patchedBugs.push(label)
          // 原请求 body 为空时没有 Content-Type，补上以便后端解析
          await route.continue({
            postData: JSON.stringify(next),
            headers: { ...request.headers(), 'content-type': 'application/json' },
          })
          return
        }
      }
      await route.continue()
    })
  await versionPatch(
    '**/api/v1/plans/*/indicators/finalize',
    (url) => `/api/v1/plans/${url.match(/\/plans\/(\d+)\/indicators\/finalize/)?.[1]}`,
    'Bug#3 finalize.version',
  )
  await versionPatch(
    '**/api/v1/plans/*/activate',
    (url) => `/api/v1/plans/${url.match(/\/plans\/(\d+)\/activate/)?.[1]}`,
    'Bug#4 activate.version',
  )
  await versionPatch(
    '**/api/v1/tasks/*/accept',
    (url) => `/api/v1/tasks/${url.match(/\/tasks\/(\d+)\/accept/)?.[1]}`,
    'Bug#5 accept.version',
  )
  // Bug#6：GET /tasks/{id}/submissions 分页结构降级为 items 数组（前端按数组使用）
  await page.route('**/api/v1/tasks/*/submissions', async (route) => {
    const request = route.request()
    if (request.method() !== 'GET') {
      await route.continue()
      return
    }
    const response = await route.fetch()
    const body = await response.json().catch(() => null)
    if (body && body.code === 0 && body.data && !Array.isArray(body.data) && Array.isArray(body.data.items)) {
      body.data = body.data.items
      if (!patchedBugs.includes('Bug#6 submissions.array')) patchedBugs.push('Bug#6 submissions.array')
      await route.fulfill({ response, json: body })
      return
    }
    await route.fulfill({ response })
  })
  // Bug#7：调整试算请求补 reason（前端未携带，后端 preview 必填）
  await page.route('**/api/v1/adjustments/preview', async (route) => {
    const request = route.request()
    if (request.method() === 'POST') {
      const data = request.postDataJSON()
      if (data && !data.reason) {
        data.reason = '冒烟试算（缺陷补丁：前端试算未携带申请原因）'
        if (!patchedBugs.includes('Bug#7 preview.reason')) patchedBugs.push('Bug#7 preview.reason')
        await route.continue({
          postData: JSON.stringify(data),
          headers: { ...request.headers(), 'content-type': 'application/json' },
        })
        return
      }
    }
    await route.continue()
  })
}

async function logout() {
  await page.locator('.user-entry').click()
  await page
    .locator('.el-dropdown-menu__item:visible')
    .filter({ hasText: '退出登录' })
    .click()
  await page.locator('input[placeholder="工号"]').waitFor()
}

/** 指标详情页 → 年度目标 tab：逐年填 2 并保存 */
async function fillYearTargetsAndSave() {
  await page.locator('.el-tabs__item', { hasText: '年度目标' }).click()
  await page.locator('.year-grid').waitFor()
  for (const year of YEARS) {
    const cell = page.locator('.year-cell', { hasText: String(year) })
    await fillNumber(cell, 2)
  }
  // 守恒校验：各年度之和 10 = 五年总量 10
  await page.locator('.sum-check.sum-ok').waitFor()
  await page.getByRole('button', { name: '保存年度目标' }).click()
  await waitToast('年度目标已保存')
}

/** 在指标总表中点击某行的「详情」进入指标详情页 */
async function openIndicatorDetail(name) {
  const row = page.locator('.el-table__row', { hasText: name }).first()
  await row.waitFor()
  await row.getByRole('button', { name: '详情' }).click()
  await page.waitForURL(/\/indicators\/\d+/)
}

/** 审批调整申请（当前登录用户）：/adjustments 待我处理 → 详情 → 同意 */
async function approveAdjustment() {
  await page.goto(`${BASE}/adjustments`)
  const row = page.locator('.el-table__row', { hasText: AP_NAME }).first()
  await row.waitFor()
  await row.click()
  const drawer = page.locator('.el-drawer:visible')
  await drawer.locator('text=审批时间线').waitFor()
  await drawer.getByRole('button', { name: '同意' }).click()
  await waitToast('已同意')
  await drawer.locator('.el-drawer__close-btn').click()
  await drawer.waitFor({ state: 'hidden' })
}

// ---------- 冒烟步骤 ----------
const steps = [
  {
    name: '01 登录 admin 进入首页',
    fn: async () => {
      await page.goto(`${BASE}/`)
      await page.waitForURL('**/login**')
      await login('admin')
    },
  },
  {
    name: '02 新建五年计划（2026-2030，基线 2025）',
    fn: async () => {
      await page.locator('.plan-card--new').click()
      const dialog = page.locator('.el-dialog:visible')
      await dialog.locator('.source-card', { hasText: '空白新建' }).click()
      await dialog.getByRole('button', { name: '下一步' }).click()
      await fi(dialog, '计划名称').locator('input').fill(PLAN_NAME)
      await dialog.getByRole('button', { name: '创建计划' }).click()
      await waitToast('计划创建成功')
      await page.waitForURL(/\/plans\/\d+/)
      state.planId = page.url().match(/\/plans\/(\d+)/)[1]
      await page.locator('.page-title', { hasText: PLAN_NAME }).waitFor()
    },
  },
  {
    name: '03 配置 S / 总负责人，新增 A 并设置 S 归属',
    fn: async () => {
      await page.goto(`${BASE}/system`)
      await page.locator('.el-tabs__item', { hasText: 'S 级任务' }).click()
      await page.locator('.panel-toolbar .el-select__wrapper').click()
      await pickOption(PLAN_NAME, true)
      await page.getByRole('button', { name: '新增 S 级任务' }).click()
      const directionDialog = page.locator('.el-dialog:visible')
      await fi(directionDialog, '名称').locator('input').fill(DIRECTION_NAME)
      await selectInFormItem(directionDialog, '总负责人', '（leader）', { type: 'leader' })
      await directionDialog.getByRole('button', { name: '保存', exact: true }).click()
      await waitToast('S 级任务已新增')
      await directionDialog.waitFor({ state: 'hidden' })

      await page.goto(`${BASE}/plans/${state.planId}/indicators`)
      await page.getByRole('button', { name: '新增 A 类指标' }).first().click()
      const drawer = page.locator('.el-drawer:visible')
      await drawer.waitFor()
      await fi(drawer, '指标名称').locator('input').fill(A_NAME)
      await fi(drawer, '计量单位').locator('input').fill('项')
      await selectInFormItem(drawer, '项目负责人', '（owner1）', { type: 'owner1' })
      await fillNumber(fi(drawer, '五年总量'), 10)
      await drawer.getByRole('button', { name: '创建指标' }).click()
      await waitToast('指标已创建')
      await drawer.waitFor({ state: 'hidden' })

      await page.goto(`${BASE}/system`)
      await page.locator('.el-tabs__item', { hasText: 'S 级任务' }).click()
      await page.locator('.panel-toolbar .el-select__wrapper').click()
      await pickOption(PLAN_NAME, true)
      const aRow = page.locator('.el-table__row', { hasText: A_NAME }).first()
      await aRow.waitFor()
      await aRow.locator('.el-select__wrapper').click()
      await page.keyboard.type(DIRECTION_NAME)
      await pickOption(DIRECTION_NAME, true)
      await aRow.getByRole('button', { name: '保存', exact: true }).click()
      await waitToast('的 S 归属')
      await page.goto(`${BASE}/plans/${state.planId}/indicators`)
      await page.locator('.el-table__row', { hasText: A_NAME }).first().waitFor()
    },
  },
  {
    name: '04 设置 A 指标逐年目标（2/2/2/2/2 守恒）',
    fn: async () => {
      await openIndicatorDetail(A_NAME)
      await fillYearTargetsAndSave()
    },
  },
  {
    name: '05 拆出 A+ 并设置逐年目标',
    fn: async () => {
      await page.goto(`${BASE}/plans/${state.planId}/indicators`)
      const row = page.locator('.el-table__row', { hasText: A_NAME }).first()
      await row.waitFor()
      await row.getByRole('button', { name: /更多/ }).click()
      await page
        .locator('.el-dropdown-menu:visible .el-dropdown-menu__item', { hasText: '拆出 A+' })
        .click()
      const drawer = page.locator('.el-drawer:visible')
      await drawer.waitFor()
      await fi(drawer, '指标名称').locator('input').fill(AP_NAME)
      await fi(drawer, '计量单位').locator('input').fill('项')
      await fillNumber(fi(drawer, '五年总量'), 10)
      await drawer.getByRole('button', { name: '创建指标' }).click()
      await waitToast('指标已创建')
      await drawer.waitFor({ state: 'hidden' })
      await openIndicatorDetail(AP_NAME)
      await fillYearTargetsAndSave()
    },
  },
  {
    name: '06 指标体系定稿',
    fn: async () => {
      await page.goto(`${BASE}/plans/${state.planId}/indicators`)
      await page.getByRole('button', { name: '全部展开', exact: true }).click()
      await page.locator('.el-table__row', { hasText: AP_NAME }).first().waitFor()
      await page.getByRole('button', { name: '定稿', exact: true }).click()
      await confirmBox('确认定稿')
      await waitToast('指标体系已定稿', 30000)
    },
  },
  {
    name: '07 分解矩阵：C01 承接全部目标并校验平衡',
    fn: async () => {
      await page.goto(`${BASE}/plans/${state.planId}/allocation`)
      const item = page.locator('.indicator-item', { hasText: AP_NAME }).first()
      await item.waitFor()
      await item.click()
      await page.locator('.matrix-panel').waitFor()
      // 新增 C01 行
      const addBar = page.locator('.add-row-bar')
      await addBar.locator('.el-select__wrapper').click()
      await page.keyboard.type('C01')
      await pickOption('C01 智能产业学院')
      await addBar.getByRole('button', { name: '添加行' }).click()
      const row = page.locator('.matrix-card .el-table__row', { hasText: 'C01' }).first()
      await row.waitFor()
      // 学院负责人 = c01
      await row.locator('.el-select__wrapper').click()
      await page.keyboard.type('c01')
      await pickOption('（c01）')
      // 逐年目标 2×5
      const cells = row.locator('.cell-input input')
      for (let i = 0; i < YEARS.length; i += 1) {
        await cells.nth(i).click()
        await cells.nth(i).fill('2')
        await cells.nth(i).press('Enter')
      }
      await page
        .locator('.el-alert--success', { hasText: '各年度合计与指标目标一致' })
        .waitFor()
    },
  },
  {
    name: '08 存草稿并下发（生成 B 任务）',
    fn: async () => {
      await page.locator('.sticky-bar').getByRole('button', { name: '存草稿' }).click()
      await waitToast('已保存')
      await page.locator('.sticky-bar').getByRole('button', { name: '下发', exact: true }).click()
      const dialog = page.locator('.el-dialog:visible')
      await dialog.locator('text=将下发指标').waitFor()
      await dialog.getByRole('button', { name: '确认下发' }).click()
      await waitToast('下发成功', 30000)
    },
  },
  {
    name: '09 启动计划并激活 2026 年度',
    fn: async () => {
      await page.goto(`${BASE}/plans/${state.planId}`)
      await page.getByRole('button', { name: '启动执行' }).click()
      await confirmBox('确认启动')
      await waitToast('计划已启动')
      const step2026 = page.locator('.el-step', { hasText: '2026 年' })
      await step2026.getByRole('button', { name: '激活' }).click()
      await confirmBox('确认激活')
      await waitToast('2026 年度已激活')
    },
  },
  {
    name: '10 c01 确认接收任务并填报 2026（附 PDF 材料）',
    fn: async () => {
      await logout()
      await login('c01')
      await page.goto(`${BASE}/workbench`)
      // 便携库有历史冒烟数据，先切「待确认接收」Tab 缩小范围（历史任务均已接收）
      await page.getByRole('tab', { name: '待确认接收' }).click()
      let row = page.locator('.task-table .el-table__row', { hasText: AP_NAME }).first()
      await row.waitFor()
      state.taskCode = (await row.locator('td').first().innerText()).trim()
      if (!state.taskCode) throw new Error('未取到任务编号')
      await row.getByRole('button', { name: '确认接收' }).click()
      await confirmBox('确认接收')
      await waitToast('已接收')
      // 接收后切「待填报」Tab 找本 run 任务去填报
      await page.getByRole('tab', { name: '待填报' }).click()
      row = page.locator('.task-table .el-table__row', { hasText: AP_NAME }).first()
      await row.waitFor()
      await row.getByRole('button', { name: '填报' }).click()
      await page.waitForURL(/\/tasks\/\d+\/submit/)
      // 本次新增数量 = 1
      const form = page.locator('.metric-form')
      await fillNumber(fi(form, '本次新增数量'), 1)
      // 佐证材料：上传临时生成的 PDF
      const pdfPath = path.join(ARTIFACTS, `evidence-${ts}.pdf`)
      fs.writeFileSync(
        pdfPath,
        '%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n' +
          '2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n' +
          '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n' +
          'trailer<</Root 1 0 R>>\n%%EOF\n',
      )
      await page.locator('.material-uploader input[type="file"]').setInputFiles(pdfPath)
      await page
        .locator('.material-uploader .file-row button', { hasText: '预览' })
        .first()
        .waitFor({ timeout: 30000 })
      // 提交审核
      await page.locator('.sticky-bar').getByRole('button', { name: '提交审核' }).click()
      await confirmBox('确认提交')
      await waitToast('已提交审核')
    },
  },
  {
    name: '11 owner1 审核中心通过该填报',
    fn: async () => {
      await logout()
      await login('owner1')
      await page.goto(`${BASE}/reviews`)
      // 历史冒烟数据的任务编号与本 run 相同（A01-1-C01），先选本 run 的计划、再选唯一的 A+ 指标过滤
      const filterCard = page.locator('.filter-card')
      await selectInFormItem(filterCard, '计划', PLAN_NAME)
      await selectInFormItem(filterCard, '指标', AP_NAME, { type: AP_NAME })
      const item = page.locator('.pending-item', { hasText: state.taskCode }).first()
      await item.waitFor()
      await item.click()
      const detail = page.locator('.detail-card')
      await detail.locator('text=审核操作').waitFor()
      await detail.getByRole('button', { name: '通过', exact: true }).click()
      // 审核「通过」的二次确认（ElMessageBox，确认按钮「确认通过」）
      await confirmBox('确认通过')
      await waitToast('已通过')
    },
  },
  {
    name: '12 c01 发起延期申请（2026 → 2027，数量 1）并提交',
    fn: async () => {
      await logout()
      await login('c01')
      await page.goto(`${BASE}/adjustments/apply`)
      const formCard = page.locator('.form-card')
      // 任务选项 label = `${code} ${指标名}`，用带时间戳的指标名避免选中历史同名编号任务
      await selectInFormItem(formCard, 'B 任务', AP_NAME, { type: AP_NAME })
      // 等任务详情加载：「延期」单选由禁用变为可用（label 文本精确等于"延期"，排除"延期 + 转移"）
      await page.waitForFunction(() => {
        const radios = Array.from(document.querySelectorAll('.el-radio'))
        const target = radios.find((r) => r.textContent?.trim() === '延期')
        return !!target && !target.classList.contains('is-disabled')
      })
      await formCard
        .locator('.el-radio', { hasText: '延期' })
        .filter({ hasNotText: '转移' })
        .locator('.el-radio__label')
        .click()
      await selectInFormItem(formCard, '原年度', '2026 年', { exact: true })
      await fillNumber(fi(formCard, '调整数量'), 1)
      await selectInFormItem(formCard, '目标年份', '2027 年', { exact: true })
      await fi(formCard, '申请原因')
        .locator('textarea')
        .fill('冒烟测试：2026 年部分目标延期至 2027 年')
      await page.getByRole('button', { name: '试算对比' }).click()
      await page.locator('.preview-card .el-alert--success', { hasText: '试算通过' }).waitFor()
      await page.getByRole('button', { name: '确认提交申请' }).click()
      await waitToast('调整申请已提交')
      await page.waitForURL((url) => url.pathname === '/adjustments')
    },
  },
  {
    name: '13 owner1 初审同意',
    fn: async () => {
      await logout()
      await login('owner1')
      await approveAdjustment()
    },
  },
  {
    name: '14 leader 终审同意',
    fn: async () => {
      await logout()
      await login('leader')
      await approveAdjustment()
    },
  },
  {
    name: '15 c01 调整列表可见「已生效」',
    fn: async () => {
      await logout()
      await login('c01')
      await page.goto(`${BASE}/adjustments`)
      await page.locator('.el-tabs__item', { hasText: '我发起的' }).click()
      await page
        .locator('.el-table__row', { hasText: AP_NAME })
        .filter({ hasText: '已生效' })
        .first()
        .waitFor()
    },
  },
  {
    name: '16 admin 大屏渲染总完成率等核心元素',
    fn: async () => {
      await logout()
      await login('admin')
      await page.goto(`${BASE}/screen/${state.planId}`)
      await page.locator('.screen-main').waitFor({ timeout: 30000 })
      await page.locator('.panel-overview .panel-title', { hasText: '五年总完成率' }).waitFor()
      await page.locator('.gauge-box canvas').waitFor({ state: 'attached' })
      // 核心数字有 800ms 滚动动画（规范 9.2），等动画结束再断言
      await page.waitForFunction(() => {
        const item = [...document.querySelectorAll('.panel-core .core-item')].find((i) =>
          i.textContent?.includes('B 任务'),
        )
        return item?.querySelector('.core-num')?.textContent?.trim() === '1'
      }, null, { timeout: 10000 })
      const taskNum = '1'
      if (taskNum !== '1') throw new Error(`大屏 B 任务数=${taskNum}，期望 1`)
      await page.waitForFunction(() => {
        const item = [...document.querySelectorAll('.panel-adjust .adjust-item')].find((i) =>
          i.textContent?.includes('已生效'),
        )
        return item?.querySelector('.adjust-num')?.textContent?.trim() === '1'
      }, null, { timeout: 10000 })
    },
  },
]

// ---------- 执行器 ----------
async function main() {
  console.log(`冒烟开始：${BASE}  计划「${PLAN_NAME}」`)
  if (PATCH_KNOWN_BUGS) {
    console.log('!! SMOKE_PATCH_KNOWN_BUGS=1：启用已确认缺陷补丁模式（page.route 修补载荷，步骤仍全部经 UI 操作）')
  }
  browser = await chromium.launch({ headless: process.env.SMOKE_HEADFUL !== '1' })
  context = await browser.newContext({
    viewport: { width: 1600, height: 900 },
    locale: 'zh-CN',
  })
  context.setDefaultTimeout(15000)
  context.setDefaultNavigationTimeout(20000)
  page = await context.newPage()
  if (PATCH_KNOWN_BUGS) {
    await installKnownBugPatches()
  }

  let passed = 0
  for (const { name, fn } of steps) {
    try {
      await fn()
      passed += 1
      console.log(`PASS ${name}`)
    } catch (error) {
      console.log(`FAIL ${name}`)
      const shot = path.join(ARTIFACTS, `fail-${name.slice(0, 2)}-${Date.now()}.png`)
      await page.screenshot({ path: shot, fullPage: true }).catch(() => {})
      let bodyText = ''
      try {
        bodyText = (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 400)
      } catch {
        bodyText = '(无法读取页面文本)'
      }
      console.log(`  页面 URL: ${page.url()}`)
      console.log(`  页面文本: ${bodyText}`)
      console.log(`  截图: ${shot}`)
      console.log(`  错误: ${String(error?.message ?? error).split('\n')[0]}`)
      await browser.close()
      console.log(`结果: PASS=${passed} FAIL=1（共 ${steps.length} 步）`)
      process.exit(1)
    }
  }
  await browser.close()
  if (PATCH_KNOWN_BUGS) {
    console.log(`!! 补丁模式运行结束，实际生效补丁：${patchedBugs.length > 0 ? patchedBugs.join('；') : '（未命中，缺陷可能已修复）'}`)
    console.log('!! 上述缺陷修复后，请用默认模式（去掉 SMOKE_PATCH_KNOWN_BUGS）回归确认')
  }
  console.log(`结果: PASS=${passed} FAIL=0（共 ${steps.length} 步）`)
  process.exit(0)
}

main().catch((error) => {
  console.error(`FAIL 冒烟脚本异常终止: ${error?.message ?? error}`)
  process.exit(1)
})
