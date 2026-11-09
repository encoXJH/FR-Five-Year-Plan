import { chromium } from 'playwright'

const BASE_URL = process.env.H5_BASE_URL || 'http://localhost:5174'
const results = []

function record(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` — ${detail}` : ''}`)
}

async function main() {
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))

  await page.goto(`${BASE_URL}/login`, { waitUntil: 'domcontentloaded' })
  await page.locator('input[placeholder="工号"]').fill('admin')
  await page.locator('input[placeholder="密码"]').fill('Test@123456')
  await page.locator('button:has-text("登 录")').click()
  await page.waitForURL(/\/plans/, { timeout: 20000 })
  await page.waitForTimeout(600)

  await page.goto(`${BASE_URL}/materials`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  await page.locator('button:has-text("上传材料")').click()
  await page.waitForTimeout(500)
  await page.locator('.upload-body .van-field').click()
  await page.waitForTimeout(400)
  await page.locator('.picker-item').first().click()
  await page.waitForTimeout(400)
  await page.locator('.upload-trigger input[type="file"]').setInputFiles({
    name: 'h5-e2e-upload.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('H5 file upload e2e'),
  })
  await page.waitForTimeout(2500)
  const uploaded = await page.locator('.upload-item .text-btn:has-text("预览")').count()
  record('材料上传', uploaded > 0)

  await page.locator('.van-button:has-text("确认关联")').click()
  await page.waitForTimeout(2000)
  const body = await page.locator('body').innerText()
  record('材料关联到指标', body.includes('已上传并关联') || body.includes('h5-e2e-upload.txt'))

  const materialItem = page.locator('.material-card .material-main').first()
  if (await materialItem.count()) {
    await materialItem.click()
    await page.waitForTimeout(1200)
    record('材料在线预览打开', await page.locator('.preview-popup').isVisible())
    await page.locator('.preview-header .close-btn').click()
  } else {
    record('材料在线预览打开', false, '材料列表中未找到上传文件')
  }

  await browser.close()
  const failed = results.filter((item) => !item.ok)
  console.log('\n===== UPLOAD SUMMARY =====')
  console.log(`checks=${results.length} failed=${failed.length} pageErrors=${errors.length}`)
  if (failed.length) {
    console.log(failed.map((item) => `${item.name}: ${item.detail}`).join('\n'))
    process.exitCode = 1
  }
}

main()
