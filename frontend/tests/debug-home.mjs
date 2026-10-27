// 临时调试：登录后抓取包含 {planId} 字面量的请求
import { chromium } from 'playwright'

const browser = await chromium.launch({ headless: true })
const page = await (await browser.newContext()).newPage()
page.on('response', (r) => {
  if (r.url().includes('%7BplanId%7D') || r.url().includes('{planId}')) {
    console.log('BAD URL:', r.url(), 'status:', r.status())
  }
  if (r.status() >= 500) console.log('5xx:', r.url(), r.status())
})
await page.goto('http://localhost:5173/login')
await page.locator('input[placeholder="工号"]').fill('admin')
await page.locator('input[placeholder="密码"]').fill('Test@123456')
await page.getByRole('button', { name: /登\s*录/ }).click()
await page.waitForURL('**/plans')
await page.waitForTimeout(4000)
console.log('landed:', page.url())
await page.screenshot({ path: 'tests/artifacts/debug-home.png' })
await browser.close()
