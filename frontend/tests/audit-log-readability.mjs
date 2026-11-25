import assert from 'node:assert/strict'
import path from 'node:path'
import { chromium } from 'playwright'

const response = (data) => ({ code: 0, message: 'OK', data, requestId: 'audit-log-readability' })
const logs = [
  {
    id: '1', occurredAt: '2026-11-25T09:00:00+08:00', operator: { id: '202', name: '系统管理员' },
    roles: '["ADMIN"]', unit: null, action: 'LOGIN', resourceType: 'auth', resourceId: '202', resourceCode: null,
    before: null, after: null, reason: null, clientType: 'WEB', ip: '0:0:0:0:0:0:0:1',
    requestId: '6f93e7fd-2e1b-4fb6-bf47-4c0aada3f03e',
  },
  {
    id: '2', occurredAt: '2026-11-25T10:00:00+08:00', operator: { id: '211', name: '张负责人' },
    roles: '["UNIT_OWNER","STAFF"]', unit: null, action: 'UPDATE', resourceType: 'indicator', resourceId: '2118569405779599618', resourceCode: 'A01',
    before: '{"name":"旧名称","version":1}', after: '{"name":"新名称","version":2}', reason: null,
    clientType: 'FEISHU_PC', ip: '192.168.1.10', requestId: '8a93ba9f-9864-4611-aa92-33c5f91ad2a6',
  },
  {
    id: '3', occurredAt: '2026-11-25T11:00:00+08:00', operator: { id: '203', name: '陈明' },
    roles: '["LEADER"]', unit: null, action: 'FILE_DOWNLOAD', resourceType: 'file', resourceId: '2118569405779599001', resourceCode: null,
    before: null, after: '{"fileName":"项目佐证材料.pdf"}', reason: null, clientType: 'FEISHU_MOBILE', ip: '10.0.0.2',
    requestId: '44638f07-538c-404c-b360-7c53d42a6c2d',
  },
]
const browser = await chromium.launch({ headless: process.env.SMOKE_HEADFUL !== '1' })
const page = await browser.newPage({ viewport: { width: 1600, height: 900 } })
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
await page.addInitScript(() => {
  sessionStorage.setItem('fzsu.accessToken', 'audit-log-token')
  sessionStorage.setItem('fzsu.refreshToken', 'audit-log-refresh')
})
await page.route('**/api/v1/**', (route) => {
  const url = new URL(route.request().url())
  const fulfill = (data) => route.fulfill({ json: response(data) })
  if (url.pathname.endsWith('/auth/me')) return fulfill({
    user: { id: '202', username: 'admin', name: '系统管理员', roles: ['ADMIN'] },
    roles: ['ADMIN'], permissions: ['audit-log:view'],
    dataScope: { type: 'ALL', unitId: null, unitIds: [], indicatorIds: [] }, passwordChangeRequired: false,
  })
  if (url.pathname.endsWith('/dicts/enums')) return fulfill({})
  if (url.pathname === '/api/v1/audit-logs') return fulfill({ items: logs, total: logs.length, page: 1, pageSize: 20 })
  if (url.pathname === '/api/v1/org-units' || url.pathname === '/api/v1/users/options') return fulfill([])
  if (url.pathname === '/api/v1/notifications') return fulfill({ items: [], total: 0, page: 1, pageSize: 1, unreadCount: 0 })
  return fulfill({})
})

try {
  await page.goto('http://localhost:5173/logs')
  await page.locator('.el-table__row').first().waitFor()
  const table = page.locator('.el-table')
  const text = await table.textContent()
  for (const expected of ['平台管理员', '学院项目负责人、干事', '校级负责人', '系统管理员', '登录系统', '修改指标「A01」', '网页端 / 本机', '飞书移动端 / 10.0.0.2', '6f93e7fd…f03e', '项目佐证材料.pdf']) {
    assert.ok(text.includes(expected), `缺少：${expected}`)
  }
  assert.ok(!text.includes('["ADMIN"]'))
  assert.ok(!text.includes('0:0:0:0:0:0:0:1'))
  await page.getByRole('columnheader', { name: '请求编号', exact: true }).waitFor()
  assert.deepEqual(errors, [])
  const image = path.join(process.env.TEMP || '.', 'audit-log-readability.png')
  await page.screenshot({ path: image, fullPage: true })
  console.log('PASS 角色中文、对象名称、操作摘要、终端/IP 与请求编号均可读')
  console.log(`screenshot=${image}`)
} finally {
  await browser.close()
}
