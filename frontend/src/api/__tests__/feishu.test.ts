import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchFeishuBotStatus, listFeishuBotLogs, runFeishuBot } from '../feishu'
import { http } from '@/utils/request'
import type { FeishuBotJob } from '@/types/feishu'

vi.mock('@/utils/request', () => ({
  http: { get: vi.fn(), post: vi.fn() },
}))

const getMock = http.get as unknown as ReturnType<typeof vi.fn>
const postMock = http.post as unknown as ReturnType<typeof vi.fn>

describe('飞书机器人 API 契约', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
  })

  it('从 Bot status 路径获取状态', async () => {
    const result = {
      configured: true,
      pushEnabled: true,
      botEnabled: true,
      timezone: 'Asia/Shanghai',
      progressCron: '0 45 8 * * *',
      leaderDigestCron: '0 0 9 * * *',
      rules: [],
      todayPush: { PENDING: 0, SENT: 0, FAILED: 0, SKIPPED: 0 },
    }
    getMock.mockResolvedValue(result)

    await expect(fetchFeishuBotStatus()).resolves.toBe(result)
    expect(getMock).toHaveBeenCalledWith('/integrations/feishu/bot/status')
  })

  it('完整传递日志分页、状态、类型、日期和关键字参数', async () => {
    const result = { items: [], total: 0, page: 2, pageSize: 20 }
    getMock.mockResolvedValue(result)
    const query = {
      page: 2,
      pageSize: 20,
      status: 'FAILED' as const,
      type: 'REVIEW_TODO' as const,
      createdDate: '2026-11-24',
      keyword: '待审核',
    }

    await expect(listFeishuBotLogs(query)).resolves.toBe(result)
    expect(getMock).toHaveBeenCalledWith('/integrations/feishu/bot/logs', query)
  })

  it.each<FeishuBotJob>(['PROGRESS', 'LEADER_DIGEST'])('执行 %s 时提交 job 和幂等头', async (job) => {
    const result = { scanned: 8, created: 3, deduplicated: 5 }
    postMock.mockResolvedValue(result)

    await expect(runFeishuBot(job, 'bot-run-key')).resolves.toBe(result)
    expect(postMock).toHaveBeenCalledWith(
      '/integrations/feishu/bot/run',
      { job },
      { headers: { 'Idempotency-Key': 'bot-run-key' } },
    )
  })
})
