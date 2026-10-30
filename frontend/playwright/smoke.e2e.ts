import { expect, test, type Page } from '@playwright/test'

async function login(page: Page, account: string) {
  await page.goto('/login')
  await page.getByLabel('登录账号').fill(account)
  await page.getByLabel('登录密码').fill('Dev@2026')
  await page.getByRole('button', { name: '登 录' }).click()
  await expect(page).toHaveURL(/\/dashboard/)
}

test('校级负责人可核验两个独立项目、指标和甘特入口', async ({ page }) => {
  await login(page, 'school')
  await page.goto('/planning/plans')
  await expect(page.getByRole('button', { name: '智能生产装备与技术专业群学校建设项目', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '智能生产装备与技术专业群任务分配项目', exact: true })).toBeVisible()
  await expect(page.getByText('10 方向 / 38 指标 / 0 任务')).toBeVisible()
  await expect(page.getByText('15 方向 / 90 指标 / 0 任务')).toBeVisible()
  const planResponse = await page.request.get('/api/v1/plans', { params: { keyword: 'PLAN-SMART-TASK-2026-2030' } })
  const plan = (await planResponse.json()).items[0]
  expect(plan).toBeTruthy()
  await page.goto(`/planning/indicators?plan_id=${plan.id}`)
  await expect(page.getByText('15 方向 · 90 指标')).toBeVisible()
  await page.goto(`/gantt?plan_id=${plan.id}`)
  await expect(page.getByRole('heading', { name: '分层甘特图' })).toBeVisible()
  await expect(page.getByText('E2E全流程验证计划')).toHaveCount(0)
})

test('平台管理员可进入组织与用户管理且学院不可越权', async ({ page }) => {
  await login(page, 'admin')
  await page.goto('/admin/users')
  await expect(page.getByRole('heading', { name: '用户与角色' })).toBeVisible()
  await expect(page.getByText('平台管理员', { exact: true }).first()).toBeVisible()
  await login(page, 'college')
  await page.goto('/admin/users')
  await expect(page).toHaveURL(/\/403/)
  await expect(page.getByText('当前账号无权访问')).toBeVisible()
})

test('全局返回可用且新建草稿可确认删除', async ({ page }) => {
  await login(page, 'school')
  const suffix = Date.now()
  const planName = `E2E待删除草稿${suffix}`
  const headers = { 'X-Requested-With': 'XMLHttpRequest' }
  const created = await page.request.post('/api/v1/plans', {
    headers,
    data: {
      plan_code: `E2E-DEL-${suffix}`,
      directory_id: 1,
      plan_name: planName,
      construction_scope: '浏览器删除回归验证',
      start_year: 2026,
      end_year: 2030,
    },
  })
  expect(created.status()).toBe(201)
  const plan = await created.json()
  try {
    await page.goto('/planning/plans')
    const globalBack = page.getByRole('button', { name: '返回上一页', exact: true })
    await expect(globalBack).toBeVisible()
    await globalBack.click()
    await expect(page).toHaveURL(/\/dashboard/)

    await page.getByRole('link', { name: '建设计划', exact: true }).click()
    await expect(page).toHaveURL(/\/planning\/plans/)
    await page.getByRole('button', { name: planName, exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/planning/indicators\\?plan_id=${plan.id}`))
    await expect(globalBack).toBeVisible()
    await expect(page.getByRole('button', { name: '返回', exact: true })).toHaveCount(0)
    await globalBack.click()
    await expect(page).toHaveURL(/\/planning\/plans/)

    const row = page.getByRole('row').filter({ hasText: planName })
    await row.getByRole('button', { name: '删除', exact: true }).click()
    await page.getByRole('button', { name: '确认删除', exact: true }).click()
    await expect(page.getByText('计划草稿已删除，操作日志已保留')).toBeVisible()
    await expect(row).toHaveCount(0)
    expect((await page.request.get(`/api/v1/plans/${plan.id}`)).status()).toBe(404)
  } finally {
    await page.request.delete(`/api/v1/plans/${plan.id}`, {
      headers,
      data: { expected_version: 1, confirm: true, reason: 'E2E 测试清理' },
    })
  }
})

test('指标量化按三个标签分区且切换不丢失未保存内容', async ({ page }) => {
  await login(page, 'school')
  const planResponse = await page.request.get('/api/v1/plans', { params: { keyword: 'PLAN-SMART-TASK-2026-2030' } })
  const plan = (await planResponse.json()).items[0]
  expect(plan).toBeTruthy()
  const response = await page.request.get('/api/v1/indicators', { params: { plan_id: plan.id, page_size: 100 } })
  expect(response.ok()).toBeTruthy()
  const indicators = await response.json()
  const units = await (await page.request.get('/api/v1/dictionaries/indicator-units')).json()
  const decimalUnits = new Set(units.items.filter((item: { value_kind: string }) => item.value_kind === 'DECIMAL').map((item: { unit_code: string }) => item.unit_code))
  const indicator = indicators.items.find((item: { unit_code: string }) => decimalUnits.has(item.unit_code))
  if (!indicator) throw new Error('缺少可用于量化页回归的数值型指标')

  await page.goto(`/planning/indicators/${indicator.id}`)
  const definitionTab = page.getByRole('tab', { name: '01 指标定义', exact: true })
  const targetsTab = page.getByRole('tab', { name: '02 目标量化', exact: true })
  const assignmentTab = page.getByRole('tab', { name: '03 接收与下发', exact: true })
  await expect(definitionTab).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByText('指标定义与核算口径', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeVisible()
  await expect(page.locator('.target-grid')).not.toBeVisible()

  await targetsTab.click()
  await expect(page.getByText('五年总目标', { exact: true })).toBeVisible()
  await expect(page.locator('.target-grid')).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '2026 年', exact: true })).toBeVisible()
  await expect(page.getByText('专业群五年目标原文', { exact: true })).not.toBeVisible()
  const professionalTarget = page.locator('.five-year-grid > div').filter({ hasText: '专业群五年目标' }).locator('input').first()
  await professionalTarget.fill('987.65')

  await definitionTab.click()
  await expect(professionalTarget).not.toBeVisible()
  await targetsTab.click()
  await expect(professionalTarget).toHaveValue('987.65')
  await page.getByText('查看源表原文（专业群 / 学校）', { exact: true }).click()
  await expect(page.getByText('专业群五年目标原文', { exact: true })).toBeVisible()
  await expect(page.getByText('学校五年目标原文', { exact: true })).toBeVisible()

  await assignmentTab.click()
  await expect(page.getByText('计划发布后才能选择接收学院并下发任务', { exact: true })).toBeVisible()
  await page.getByText('校级责任（非任务接收人）', { exact: true }).click()
  await expect(page.getByText('教务处、双高办等仅作为指标牵头／协同单位，不是学院任务接收人，不会自动生成学院任务。', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeVisible()
})
