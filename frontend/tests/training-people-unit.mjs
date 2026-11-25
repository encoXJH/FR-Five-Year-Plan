import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const BASE = process.env.SMOKE_BASE_URL || 'http://localhost:5173'
const SHEET_ID = '2124807326637428737'
const response = (data) => ({ code: 0, message: 'OK', data, requestId: 'training-people-unit-mock' })
const groupDefinitions = [
  { group: 'BASIC', groupLabel: '项目基本', from: 1, to: 6 },
  { group: 'FUNDING', groupLabel: '合同经费', from: 7, to: 11 },
  { group: 'IMPLEMENTATION', groupLabel: '培训实施', from: 12, to: 17 },
  { group: 'AUDIENCE', groupLabel: '培训对象', from: 18, to: 32 },
  { group: 'INDUSTRY', groupLabel: '重点行业', from: 33, to: 44 },
  { group: 'REGION', groupLabel: '区域战略', from: 45, to: 54 },
  { group: 'TEACHER', groupLabel: '教师填报', from: 55, to: 57 },
]
const labels = {
  c18: '党政领导干部', c19: '企业职工', c32: '合计', c33: '乡村振兴',
  c44: '合计', c45: '京津冀一体化', c54: '合计',
}
const fields = groupDefinitions.flatMap((group) =>
  Array.from({ length: group.to - group.from + 1 }, (_, offset) => {
    const column = group.from + offset
    const key = `c${String(column).padStart(2, '0')}`
    let type = 'TEXT'
    if (key === 'c02') type = 'SELECT'
    if (key === 'c05' || key === 'c06') type = 'DATE'
    if (['c09', 'c10', 'c11', 'c12'].includes(key)) type = 'DECIMAL'
    if (column >= 14 && column <= 54 && !['c15', 'c17'].includes(key)) type = 'INTEGER'
    return {
      key, column, label: labels[key] || `${group.groupLabel}字段${key}`,
      group: group.group, groupLabel: group.groupLabel, type,
      required: column <= 6, options: key === 'c02' ? ['是', '否'] : [],
    }
  }),
)
const sheet = {
  id: SHEET_ID, planId: '2124807326574514178', planName: '2025—2026学年职业培训信息采集',
  startYear: 2025, endYear: 2026, baselineYear: 2024,
  unitId: 'unit-1', unitName: '产业AI人才服务中心', ownerId: 'owner-1', ownerName: '田明月',
  status: 'DRAFT', projectCount: 0, version: 0, updatedAt: '2026-11-24T15:35:00+08:00',
  submitterRole: null, currentReviewNode: null, revisionNo: 0,
  submittedBy: null, submittedByName: null, submittedAt: null, canEdit: true, canReview: false,
}

const browser = await chromium.launch({ headless: process.env.SMOKE_HEADFUL !== '1' })
const page = await browser.newPage({ baseURL: BASE, viewport: { width: 1440, height: 1100 } })
const scriptErrors = []
const writes = []
page.on('pageerror', (error) => scriptErrors.push(error.message))
page.on('request', (request) => {
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method()) && !request.url().endsWith('/auth/logout')) {
    writes.push(`${request.method()} ${request.url()}`)
  }
})
await page.addInitScript(() => {
  sessionStorage.setItem('fzsu.accessToken', 'mock-access-token')
  sessionStorage.setItem('fzsu.refreshToken', 'mock-refresh-token')
})
await page.route('**/api/v1/**', (route) => {
  const url = new URL(route.request().url())
  const path = url.pathname
  if (path.endsWith('/auth/me')) {
    return route.fulfill({ json: response({
      user: { id: '1', username: 'admin', name: '平台管理员', roles: ['ADMIN'] },
      roles: ['ADMIN'], permissions: ['training:fill', 'submission:review'],
      dataScope: { type: 'ALL', unitId: null, unitIds: [], indicatorIds: [] }, passwordChangeRequired: false,
    }) })
  }
  if (path.endsWith('/dicts/enums')) return route.fulfill({ json: response({}) })
  if (path === `/api/v1/training-sheets/${SHEET_ID}`) {
    return route.fulfill({ json: response({ sheet, fields, projects: [], reviews: [] }) })
  }
  if (path === '/api/v1/training-sheets') return route.fulfill({ json: response([sheet]) })
  if (path === '/api/v1/plans') return route.fulfill({ json: response([]) })
  if (path === '/api/v1/notifications') {
    return route.fulfill({ json: response({ items: [], total: 0, page: 1, pageSize: 20, unreadCount: 0 }) })
  }
  return route.fulfill({ json: response({}) })
})

try {
  await page.goto(`/training/${SHEET_ID}?from=training`)
  await page.getByRole('button', { name: '新增第一个项目', exact: true }).click()
  for (const group of ['培训对象', '重点行业', '区域战略']) {
    const section = page.locator(`section[aria-label="${group}"]`)
    await section.getByText('人数单位：人', { exact: true }).waitFor()
  }
  await page.getByPlaceholder('请输入党政领导干部（人）').fill('2')
  await page.getByPlaceholder('请输入企业职工（人）').fill('3')
  await page.getByPlaceholder('请输入乡村振兴（人）').fill('4')
  await page.getByPlaceholder('请输入京津冀一体化（人）').fill('5')
  assert.equal(await page.locator('section[aria-label="培训对象"] input[readonly]').inputValue(), '5 人')
  assert.equal(await page.locator('section[aria-label="重点行业"] input[readonly]').inputValue(), '4 人')
  assert.equal(await page.locator('section[aria-label="区域战略"] input[readonly]').inputValue(), '5 人')
  assert.deepEqual(scriptErrors, [])
  assert.deepEqual(writes, [])
  console.log('PASS 培训对象、重点行业、区域战略均显示人数单位，输入提示及自动合计均带人；未写入业务数据')
} finally {
  await browser.close()
}
