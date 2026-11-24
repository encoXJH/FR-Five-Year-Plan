import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const BASE = process.env.SMOKE_BASE_URL || 'http://localhost:5173'
const status = {
  configured: true,
  pushEnabled: false,
  botEnabled: false,
  timezone: 'Asia/Shanghai',
  progressCron: '0 45 8 * * ?',
  leaderDigestCron: '0 0 9 * * ?',
  rules: [
    { code: 'TASK_LAG', name: '任务滞后提醒', description: '提醒所属学院全部启用学院负责人' },
    { code: 'YEAR_GAP', name: '年度进度未达标', description: '提醒任务负责人及当前分配干事' },
    { code: 'REVIEW_RESULT', name: '审核结果通知', description: '干事终审通过追加院级通知' },
    { code: 'REVIEW_DAILY', name: '校级审核每日汇总', description: '无待办不发送' },
    { code: 'ADJUSTMENT_DAILY', name: '调整审批每日汇总', description: '按接收人汇总' },
  ],
  todayPush: { PENDING: 2, SENT: 6, FAILED: 1, SKIPPED: 0 },
}
const log = {
  id: '1', userId: '100', receiverName: '校级负责人', type: 'REVIEW_TODO',
  title: '校级审核待办：3 项', content: '今日有 3 项填报待审核', link: '/reviews',
  feishuStatus: 'FAILED', feishuRetryCount: 2, feishuLastError: '飞书网关超时',
  createdAt: '2026-11-24T09:00:00+08:00',
}
const response = (data) => ({ code: 0, message: 'OK', data, requestId: 'feishu-bot-browser-mock' })

const browser = await chromium.launch({ headless: process.env.SMOKE_HEADFUL !== '1' })
const page = await browser.newPage({ baseURL: BASE, viewport: { width: 1440, height: 1000 } })
const scriptErrors = []
const externalFeishuRequests = []
let denyStatus = false
let runRequest = null
let lastLogUrl = null
let pushVersion = 4
let botVersion = 2
let patchConflict = false
const settingsPatchRequests = []
page.on('pageerror', (error) => scriptErrors.push(error.stack || error.message))
page.on('request', (request) => {
  if (/open\.feishu\.cn|open\.larksuite\.com/.test(request.url())) externalFeishuRequests.push(request.url())
})
await page.addInitScript(() => {
  sessionStorage.setItem('fzsu.accessToken', 'browser-mock-access-token')
  sessionStorage.setItem('fzsu.refreshToken', 'browser-mock-refresh-token')
})
await page.route('**/api/v1/**', (route) => {
  const path = new URL(route.request().url()).pathname
  if (path.endsWith('/auth/me')) {
    const user = { id: '1', username: 'admin', name: '平台管理员', roles: ['ADMIN'] }
    return route.fulfill({ json: response({ user, roles: ['ADMIN'], permissions: ['system:manage'],
      dataScope: { type: 'ALL', unitId: null, unitIds: [], indicatorIds: [] }, passwordChangeRequired: false }) })
  }
  if (path.endsWith('/dicts/enums')) {
    return route.fulfill({ json: response({ NotificationType: {
      REVIEW_TODO: '审核待办汇总', REVIEW_RESULT: '审核结果', WARNING: '预警', ADJUSTMENT_TODO: '调整待办',
    } }) })
  }
  if (path.endsWith('/plans')) {
    return route.fulfill({ json: response({ items: [], total: 0, page: 1, pageSize: 100 }) })
  }
  if (path.endsWith('/org-units')) {
    return route.fulfill({ json: response([]) })
  }
  if (path.endsWith('/settings')) {
    if (route.request().method() === 'PATCH') {
      const payload = route.request().postDataJSON()
      settingsPatchRequests.push(payload)
      if (patchConflict) {
        patchConflict = false
        return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({
          code: 10005, message: '设置已被修改', data: null, requestId: 'bot-setting-conflict',
        }) })
      }
      if (payload.items[0].key === 'feishu.botEnabled') {
        status.botEnabled = payload.items[0].value === 'true'
        botVersion++
      } else {
        status.pushEnabled = payload.items[0].value === 'true'
        pushVersion++
      }
    }
    return route.fulfill({ json: response({ items: [{
      id: '421', key: 'feishu.pushEnabled', value: String(status.pushEnabled), valueType: 'BOOLEAN',
      description: '消息推送开关', version: pushVersion,
    }, {
      id: '422', key: 'feishu.botEnabled', value: String(status.botEnabled), valueType: 'BOOLEAN',
      description: '机器人调度开关', version: botVersion,
    }] }) })
  }
  if (path.endsWith('/notifications')) {
    return route.fulfill({ json: response({ items: [], total: 0, page: 1, pageSize: 20, unreadCount: 0 }) })
  }
  return route.fulfill({ json: response({}) })
})
await page.route('**/api/v1/integrations/feishu/bot/status', (route) => route.fulfill({
  status: denyStatus ? 403 : 200,
  contentType: 'application/json',
  body: JSON.stringify(denyStatus
    ? { code: 20003, message: '无权限', data: null, requestId: 'bot-mock-403' }
    : response(status)),
}))
await page.route('**/api/v1/integrations/feishu/bot/logs?*', (route) => {
  lastLogUrl = new URL(route.request().url())
  return route.fulfill({ json: response({ items: [log], total: 1, page: 1, pageSize: 20 }) })
})
await page.route('**/api/v1/integrations/feishu/bot/run', (route) => {
  runRequest = {
    body: route.request().postDataJSON(),
    idempotencyKey: route.request().headers()['idempotency-key'],
  }
  return route.fulfill({ json: response({ scanned: 8, created: 3, deduplicated: 5 }) })
})

try {
  await page.goto('/system')
  await page.getByRole('tab', { name: '双高管理平台机器人', exact: true }).click()
  await page.locator('.bot-panel').waitFor()
  await page.getByText('校级审核待办：3 项', { exact: true }).first().waitFor()
  const panelText = await page.locator('.bot-panel').innerText()
  assert.match(panelText, /飞书连接/)
  assert.match(panelText, /飞书网关超时/)
  assert.match(panelText, /每天 08:45/)
  assert.match(panelText, /每天 09:00/)
  assert.doesNotMatch(panelText, /0 45 8 \* \* \?|0 0 9 \* \* \?/)
  assert.doesNotMatch(panelText, /App Secret|FEISHU_APP_SECRET/)

  await page.locator('.keyword-filter input').fill('网关')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page.waitForTimeout(200)
  assert.equal(lastLogUrl.searchParams.get('keyword'), '网关')

  await page.getByRole('button', { name: '开启推送', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '取消', exact: true }).click()
  assert.equal(settingsPatchRequests.length, 0)

  await page.getByRole('button', { name: '开启推送', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认开启', exact: true }).click()
  await page.getByRole('button', { name: '关闭推送', exact: true }).waitFor()
  assert.deepEqual(settingsPatchRequests.at(-1).items, [{ key: 'feishu.pushEnabled', value: 'true', version: 4 }])

  await page.getByRole('button', { name: '关闭推送', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认关闭', exact: true }).click()
  await page.getByRole('button', { name: '开启推送', exact: true }).waitFor()
  assert.equal(settingsPatchRequests.at(-1).items[0].value, 'false')

  await page.getByRole('button', { name: '启动机器人', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认启动', exact: true }).click()
  await page.getByRole('button', { name: '停用机器人', exact: true }).waitFor()
  assert.deepEqual(settingsPatchRequests.at(-1).items, [{ key: 'feishu.botEnabled', value: 'true', version: 2 }])
  assert.equal(status.pushEnabled, false)

  await page.getByRole('button', { name: '停用机器人', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认停用', exact: true }).click()
  await page.getByRole('button', { name: '启动机器人', exact: true }).waitFor()
  assert.equal(settingsPatchRequests.at(-1).items[0].value, 'false')
  assert.equal(status.pushEnabled, false)

  patchConflict = true
  await page.getByRole('button', { name: '开启推送', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认开启', exact: true }).click()
  await page.getByRole('button', { name: '刷新', exact: true }).waitFor()
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await page.waitForLoadState('load')
  await page.getByRole('tab', { name: '双高管理平台机器人', exact: true }).click()
  await page.getByRole('button', { name: '开启推送', exact: true }).waitFor()

  await page.getByRole('button', { name: '立即检查进度', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '立即检查进度', exact: true }).click()
  await page.locator('.result-alert').waitFor()
  assert.match(await page.locator('.result-alert').innerText(), /进度检查执行完成/)
  assert.match(await page.locator('.result-alert').innerText(), /扫描 8 项，新建 3 条，去重 5 条/)
  assert.deepEqual(runRequest.body, { job: 'PROGRESS' })
  assert.ok(runRequest.idempotencyKey)

  await page.getByRole('button', { name: '查看内容', exact: true }).click()
  await page.locator('.el-dialog').getByText('飞书网关超时', { exact: true }).waitFor()
  await page.locator('.el-dialog').getByRole('button', { name: '关闭', exact: true }).click()

  await page.setViewportSize({ width: 428, height: 926 })
  await page.waitForTimeout(300)
  const width = await page.evaluate(() => ({ viewport: document.documentElement.clientWidth,
    scroll: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) }))
  assert.ok(width.scroll <= width.viewport + 1, `窄屏页面横向溢出：${JSON.stringify(width)}`)

  denyStatus = true
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.reload()
  await page.getByRole('tab', { name: '双高管理平台机器人', exact: true }).click()
  await page.locator('.status-section .el-alert--error').waitFor()
  assert.deepEqual(scriptErrors, [])
  assert.deepEqual(externalFeishuRequests, [])
  console.log('PASS 飞书机器人页签、日志筛选、失败详情、手动执行、窄屏与403 Mock验收通过；未请求真实飞书接口')
} finally {
  await browser.close()
}
