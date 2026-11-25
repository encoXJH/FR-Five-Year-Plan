import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const response = (data) => ({ code: 0, message: 'OK', data, requestId: 'notification-badge-test' })
const notifications = [
  { id: '301', type: 'REVIEW_RESULT', title: '填报审核结果', content: '请查看审核结果', bizType: null, bizId: null, link: null, readAt: null, createdAt: '2026-11-25T09:00:00+08:00' },
  { id: '302', type: 'TASK', title: '任务截止提醒', content: '请及时完成填报', bizType: null, bizId: null, link: null, readAt: null, createdAt: '2026-11-25T10:00:00+08:00' },
]
let unreadCount = 2
const writes = []
const browser = await chromium.launch({ headless: process.env.SMOKE_HEADFUL !== '1' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
await page.addInitScript(() => {
  sessionStorage.setItem('fzsu.accessToken', 'notification-badge-token')
  sessionStorage.setItem('fzsu.refreshToken', 'notification-badge-refresh')
})
await page.route('**/api/v1/**', async (route) => {
  const request = route.request()
  const path = new URL(request.url()).pathname
  if (path.endsWith('/auth/me')) return route.fulfill({ json: response({
    user: { id: '1', username: 'admin', name: '管理员', roles: ['ADMIN'] },
    roles: ['ADMIN'], permissions: [],
    dataScope: { type: 'ALL', unitId: null, unitIds: [], indicatorIds: [] },
    passwordChangeRequired: false,
  }) })
  if (path.endsWith('/dicts/enums')) return route.fulfill({ json: response({}) })
  if (path === '/api/v1/todos') return route.fulfill({ json: response({ summary: {}, items: [] }) })
  if (path === '/api/v1/notifications' && request.method() === 'GET') {
    return route.fulfill({ json: response({ items: notifications, total: notifications.length, page: 1, pageSize: 20, unreadCount }) })
  }
  if (path === '/api/v1/notifications/301/read' && request.method() === 'POST') {
    writes.push(path)
    notifications[0].readAt = '2026-11-25T18:00:00+08:00'
    unreadCount = 1
    return route.fulfill({ json: response(notifications[0]) })
  }
  if (path === '/api/v1/notifications/read-all' && request.method() === 'POST') {
    writes.push(path)
    notifications.forEach((item) => { item.readAt ??= '2026-11-25T18:01:00+08:00' })
    unreadCount = 0
    return route.fulfill({ json: response({ readCount: 1 }) })
  }
  return route.fulfill({ json: response({}) })
})

try {
  await page.goto('http://localhost:5173/todos')
  const badge = page.locator('.todo-badge .el-badge__content')
  await badge.waitFor({ state: 'visible' })
  assert.equal((await badge.textContent())?.trim(), '2')

  await page.getByRole('button', { name: '待办与消息', exact: true }).click()
  await page.waitForURL((url) => url.pathname === '/todos' && url.searchParams.get('tab') === 'notifications')
  await page.getByRole('button', { name: '全部已读', exact: true }).waitFor()
  assert.ok(await page.locator('.el-tabs__item.is-active').textContent().then((text) => text.includes('消息')))
  console.log('PASS 铃铛点击进入消息页签，保留未读数量')

  await page.locator('.notif-item').filter({ hasText: '填报审核结果' }).click()
  await page.waitForFunction(() => document.querySelector('.todo-badge .el-badge__content')?.textContent?.trim() === '1')
  assert.deepEqual(writes, ['/api/v1/notifications/301/read'])
  assert.equal(await page.getByText('该通知暂无可跳转页面', { exact: true }).count(), 1)
  console.log('PASS 无跳转链接的通知也可单条已读，顶栏红点立即从 2 减为 1')

  await page.getByRole('button', { name: '全部已读', exact: true }).click()
  await badge.waitFor({ state: 'hidden' })
  assert.deepEqual(writes, ['/api/v1/notifications/301/read', '/api/v1/notifications/read-all'])
  assert.equal(await page.getByText('（0 未读）', { exact: true }).count(), 0)
  assert.deepEqual(errors, [])
  console.log('PASS 全部已读后顶栏红点立即消失，无需等待 60 秒轮询')
} finally {
  await browser.close()
}
