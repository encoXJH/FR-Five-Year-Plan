import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const BASE = process.env.SMOKE_BASE_URL || 'http://localhost:5173'
const SHEET_ID = '2124807326637428737'
const response = (data) => ({ code: 0, message: 'OK', data, requestId: 'training-staff-files-e2e' })
const sheet = {
  id: SHEET_ID,
  planId: '2124807326574514178',
  planName: '2025—2026学年职业培训信息采集',
  startYear: 2025,
  endYear: 2026,
  baselineYear: 2024,
  unitId: 'unit-14',
  unitName: '产业AI人才服务中心',
  ownerId: 'owner-1',
  ownerName: '田明月',
  status: 'DRAFT',
  projectCount: 0,
  version: 0,
  updatedAt: '2026-11-26T09:00:00+08:00',
  submitterRole: null,
  currentReviewNode: null,
  revisionNo: 0,
  submittedBy: null,
  submittedByName: null,
  submittedAt: null,
  canEdit: true,
  canReview: false,
  canAssignStaff: false,
}
const groupDefinitions = [
  { group: 'BASIC', groupLabel: '项目基本', from: 1, to: 6 },
  { group: 'FUNDING', groupLabel: '合同经费', from: 7, to: 11 },
  { group: 'IMPLEMENTATION', groupLabel: '培训实施', from: 12, to: 17 },
  { group: 'AUDIENCE', groupLabel: '培训对象', from: 18, to: 32 },
  { group: 'INDUSTRY', groupLabel: '重点行业', from: 33, to: 44 },
  { group: 'REGION', groupLabel: '区域战略', from: 45, to: 54 },
  { group: 'TEACHER', groupLabel: '教师填报', from: 55, to: 57 },
]
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
      key,
      column,
      label: `${group.groupLabel}字段${key}`,
      group: group.group,
      groupLabel: group.groupLabel,
      type,
      required: column <= 6,
      options: key === 'c02' ? ['是', '否'] : [],
    }
  }),
)
const fileInfo = {
  id: 'file-1',
  fileName: '培训附件.pdf',
  size: 8,
  mimeType: 'application/pdf',
  category: 'PDF',
  bizType: 'TRAINING_ATTACHMENT',
  status: 'TEMP',
  transcodeStatus: null,
  uploadedAt: '2026-11-26T10:00:00+08:00',
  uploadedBy: { id: 'staff-a', name: '干事A' },
}
const browser = await chromium.launch({ headless: process.env.SMOKE_HEADFUL !== '1' })
const scriptErrors = []

async function mockPage(auth, routeHandler, viewport = { width: 1440, height: 1000 }) {
  const context = await browser.newContext({ baseURL: BASE, viewport })
  await context.addInitScript(() => {
    sessionStorage.setItem('fzsu.accessToken', 'mock-access-token')
    sessionStorage.setItem('fzsu.refreshToken', 'mock-refresh-token')
  })
  const page = await context.newPage()
  page.on('pageerror', (error) => scriptErrors.push(error.message))
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/auth/me')) return route.fulfill({ json: response(auth) })
    if (path.endsWith('/dicts/enums')) return route.fulfill({ json: response({}) })
    if (path === '/api/v1/notifications') {
      return route.fulfill({ json: response({ items: [], total: 0, page: 1, pageSize: 20, unreadCount: 0 }) })
    }
    return routeHandler(route, request, path)
  })
  return { context, page }
}

try {
  const staffView = {
    version: 0,
    staff: [],
    assignableStaff: [{ id: 'staff-a', name: '干事A' }, { id: 'staff-b', name: '干事B' }],
    canAssignStaff: true,
  }
  let assignmentPayload = null
  const owner = await mockPage({
    user: { id: 'owner-1', username: '990502', name: '田明月', roles: ['UNIT_OWNER'] },
    roles: ['UNIT_OWNER'],
    permissions: ['training:fill'],
    dataScope: { type: 'UNIT', unitId: 'unit-14', unitIds: ['unit-14'], indicatorIds: [] },
    passwordChangeRequired: false,
  }, (route, request, path) => {
    if (path === '/api/v1/training-sheets') return route.fulfill({ json: response([{ ...sheet, canAssignStaff: true }]) })
    if (path === `/api/v1/training-sheets/${SHEET_ID}/staff` && request.method() === 'GET') {
      return route.fulfill({ json: response(staffView) })
    }
    if (path === `/api/v1/training-sheets/${SHEET_ID}/staff` && request.method() === 'PUT') {
      assignmentPayload = request.postDataJSON()
      staffView.version += 1
      staffView.staff = staffView.assignableStaff.filter((item) => assignmentPayload.staffIds.includes(item.id))
      return route.fulfill({ json: response(staffView) })
    }
    return route.fulfill({ json: response({}) })
  })
  await owner.page.goto('/training')
  await owner.page.getByRole('button', { name: '干事分配', exact: true }).click()
  await owner.page.locator('.staff-select').click()
  await owner.page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter({ hasText: '干事A' }).click()
  await owner.page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter({ hasText: '干事B' }).click()
  await owner.page.keyboard.press('Escape')
  await owner.page.getByRole('button', { name: '保存分配', exact: true }).click()
  await owner.page.locator('.el-dialog').waitFor({ state: 'hidden' })
  assert.deepEqual(assignmentPayload, { version: 0, staffIds: ['staff-a', 'staff-b'] })
  assert.deepEqual(staffView.staff.map((item) => item.id), ['staff-a', 'staff-b'])
  console.log('PASS 指定负责人可选择多名干事并整体保存分配')
  await owner.context.close()

  let savePayload = null
  const staff = await mockPage({
    user: { id: 'staff-a', username: 'staff-a', name: '干事A', roles: ['STAFF'] },
    roles: ['STAFF'],
    permissions: ['training:fill'],
    dataScope: { type: 'ASSIGNED', unitId: 'unit-14', unitIds: ['unit-14'], indicatorIds: [] },
    passwordChangeRequired: false,
  }, (route, request, path) => {
    if (path === `/api/v1/training-sheets/${SHEET_ID}` && request.method() === 'GET') {
      return route.fulfill({ json: response({ sheet, fields, projects: [], reviews: [] }) })
    }
    if (path === '/api/v1/files' && request.method() === 'POST') return route.fulfill({ json: response(fileInfo) })
    if (path === `/api/v1/training-sheets/${SHEET_ID}/projects` && request.method() === 'PUT') {
      savePayload = request.postDataJSON()
      const input = savePayload.projects[0]
      return route.fulfill({ json: response({
        sheet: { ...sheet, projectCount: 1, version: 1 },
        fields,
        projects: [{
          id: 'project-1',
          rowNo: input.rowNo,
          data: input.data,
          status: 'DRAFT',
          submitterRole: 'STAFF',
          currentReviewNode: null,
          revisionNo: 0,
          submittedBy: null,
          submittedAt: null,
          canEdit: true,
          canReview: false,
          reviewComment: null,
          fileIds: input.fileIds,
          files: [fileInfo],
        }],
        reviews: [],
      }) })
    }
    return route.fulfill({ json: response({}) })
  })
  await staff.page.goto(`/training/${SHEET_ID}?from=training`)
  await staff.page.getByRole('button', { name: '新增第一个项目', exact: true }).click()
  const attachmentSection = staff.page.locator('section[aria-label="项目附件"]')
  await attachmentSection.getByRole('button', { name: '上传附件', exact: true }).waitFor()
  await attachmentSection.locator('input[type="file"]').setInputFiles({ name: '培训附件.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4') })
  await attachmentSection.getByText('培训附件.pdf', { exact: true }).waitFor()
  await attachmentSection.getByRole('button', { name: '预览', exact: true }).waitFor()
  await staff.page.getByRole('button', { name: '确定', exact: true }).click()
  await staff.page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await staff.page.waitForFunction(() => document.querySelector('.el-message--success')?.textContent?.includes('培训项目草稿已保存'))
  assert.equal(savePayload.projects.length, 1)
  assert.deepEqual(savePayload.projects[0].fileIds, ['file-1'])
  assert.ok(savePayload.projects[0].id == null)
  console.log('PASS 被分配干事可新增项目、点击上传附件并随草稿提交 fileIds')
  await staff.context.close()

  const unassigned = await mockPage({
    user: { id: 'staff-b', username: 'staff-b', name: '干事B', roles: ['STAFF'] },
    roles: ['STAFF'],
    permissions: [],
    dataScope: { type: 'ASSIGNED', unitId: 'unit-14', unitIds: ['unit-14'], indicatorIds: [] },
    passwordChangeRequired: false,
  }, (route) => route.fulfill({ json: response({}) }))
  await unassigned.page.goto('/training')
  await unassigned.page.waitForURL((url) => url.pathname === '/403')
  assert.equal(await unassigned.page.getByText('职业培训填写', { exact: true }).count(), 0)
  console.log('PASS 未分配干事无培训菜单且直接访问培训列表被拦截')
  await unassigned.context.close()

  assert.deepEqual(scriptErrors, [])
  console.log('PASS 培训分配与附件浏览器验收无脚本错误')
} finally {
  await browser.close()
}
