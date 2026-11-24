import { createSSRApp, defineComponent, getCurrentInstance, h, unref } from 'vue'
import type { Ref } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage, ElMessageBox } from 'element-plus'
import FeishuBotPanel from './FeishuBotPanel.vue'
import { fetchFeishuBotStatus, listFeishuBotLogs, runFeishuBot } from '@/api/feishu'
import { getSettings, updateSettings } from '@/api/system'
import type { FeishuBotJob, FeishuBotLog, FeishuBotRunResult, FeishuBotStatus } from '@/types/feishu'

vi.mock('@/api/feishu', () => ({
  fetchFeishuBotStatus: vi.fn(),
  listFeishuBotLogs: vi.fn(),
  runFeishuBot: vi.fn(),
}))

vi.mock('@/api/system', () => ({
  getSettings: vi.fn(),
  updateSettings: vi.fn(),
}))

vi.mock('@/stores/user', () => ({
  useUserStore: () => ({ enums: {} }),
}))

vi.mock('@/utils/autoRefresh', () => ({
  useAutoRefresh: vi.fn(),
}))

vi.mock('@/utils/idempotency', () => ({
  newIdempotencyKey: () => 'bot-test-key',
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

const fetchStatusMock = fetchFeishuBotStatus as unknown as ReturnType<typeof vi.fn>
const listLogsMock = listFeishuBotLogs as unknown as ReturnType<typeof vi.fn>
const runMock = runFeishuBot as unknown as ReturnType<typeof vi.fn>
const getSettingsMock = getSettings as unknown as ReturnType<typeof vi.fn>
const updateSettingsMock = updateSettings as unknown as ReturnType<typeof vi.fn>
const confirmMock = ElMessageBox.confirm as unknown as ReturnType<typeof vi.fn>

const failedLog: FeishuBotLog = {
  id: '1',
  userId: '100',
  receiverName: '校级负责人',
  type: 'REVIEW_TODO',
  title: '审核待办汇总',
  content: '今日有 3 项填报待审核',
  link: null,
  feishuStatus: 'FAILED',
  feishuRetryCount: 2,
  feishuLastError: '飞书网关超时',
  createdAt: '2026-11-24T09:00:00+08:00',
}

const baseStatus: FeishuBotStatus = {
  configured: true,
  pushEnabled: false,
  botEnabled: true,
  timezone: 'Asia/Shanghai',
  progressCron: '0 45 8 * * ?',
  leaderDigestCron: '0 0 9 * * ?',
  rules: [],
  todayPush: { PENDING: 0, SENT: 0, FAILED: 0, SKIPPED: 0 },
}

const elementTags = [
  'el-alert',
  'el-empty',
  'el-tag',
  'el-button',
  'el-form',
  'el-form-item',
  'el-select',
  'el-option',
  'el-date-picker',
  'el-input',
  'el-table',
  'el-pagination',
  'el-dialog',
  'el-descriptions',
  'el-descriptions-item',
  'el-link',
]

interface PanelState {
  handleRun: (job: FeishuBotJob) => Promise<void>
  handlePushToggle: () => Promise<void>
  handleBotToggle: () => Promise<void>
  status: FeishuBotStatus | null
  runResult: Ref<(FeishuBotRunResult & { job: FeishuBotJob }) | null>
}

async function renderPanel(row?: FeishuBotLog): Promise<{ html: string; state: PanelState }> {
  const app = createSSRApp(FeishuBotPanel)
  let capturedState: PanelState | undefined
  for (const tag of elementTags.filter((tag) => tag !== 'el-empty')) {
    app.component(tag, defineComponent({
      inheritAttrs: false,
      setup(_, { attrs, slots }) {
        return () => h(tag, attrs, Object.values(slots).map((slot) => slot?.()))
      },
    }))
  }
  app.component('el-empty', defineComponent({
    inheritAttrs: false,
    setup(_, { attrs, slots }) {
      const parent = getCurrentInstance()?.parent as unknown as { setupState?: Partial<PanelState> } | undefined
      const parentState = parent?.setupState
      if (typeof parentState?.handleRun === 'function') capturedState = parentState as PanelState
      return () => h('el-empty', attrs, Object.values(slots).map((slot) => slot?.()))
    },
  }))
  app.component('el-table-column', defineComponent({
    inheritAttrs: false,
    setup(_, { attrs, slots }) {
      return () => {
        if (!row) return h('el-table-column', attrs)
        const prop = attrs.prop as keyof FeishuBotLog | undefined
        const propValue = prop ? String(row[prop] ?? '') : ''
        return h('el-table-column', attrs, [slots.default?.({ row }), propValue])
      }
    },
  }))
  app.directive('loading', { getSSRProps: () => ({}) })
  const html = await renderToString(app)
  if (!capturedState) throw new Error('未捕获面板状态')
  return { html, state: capturedState }
}

describe('FeishuBotPanel 状态展示', () => {
  beforeEach(() => {
    fetchStatusMock.mockReset()
    listLogsMock.mockReset()
    runMock.mockReset()
    getSettingsMock.mockReset()
    updateSettingsMock.mockReset()
    confirmMock.mockReset()
    vi.mocked(ElMessage.success).mockReset()
    fetchStatusMock.mockResolvedValue({ ...baseStatus, pushEnabled: true })
    getSettingsMock.mockResolvedValue({ items: [{
      id: '421', key: 'feishu.pushEnabled', value: 'false', valueType: 'BOOLEAN',
      description: '消息推送开关', version: 4,
    }, {
      id: '422', key: 'feishu.botEnabled', value: 'false', valueType: 'BOOLEAN',
      description: '机器人调度开关', version: 2,
    }] })
    updateSettingsMock.mockResolvedValue({ items: [] })
    listLogsMock.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 })
  })

  it('初始无数据时展示状态与日志空状态', async () => {
    const { html } = await renderPanel()

    expect(html).toContain('暂无机器人状态')
    expect(html).toContain('暂无机器人推送日志')
  })

  it('日志行展示完整内容字段与失败原因', async () => {
    const { html } = await renderPanel(failedLog)

    expect(html).toContain('今日有 3 项填报待审核')
    expect(html).toContain('飞书网关超时')
    expect(html).toContain('发送失败')
    expect(html).toContain('审核待办汇总')
  })

  it('取消开启时不读取或修改设置', async () => {
    confirmMock.mockRejectedValue(new Error('cancel'))
    const { state } = await renderPanel()
    state.status = { ...baseStatus, pushEnabled: false }

    await state.handlePushToggle()

    expect(getSettingsMock).not.toHaveBeenCalled()
    expect(updateSettingsMock).not.toHaveBeenCalled()
    expect(state.status?.pushEnabled).toBe(false)
  })

  it('确认开启时提交当前版本并刷新有效状态', async () => {
    confirmMock.mockResolvedValue(undefined)
    const { state } = await renderPanel()
    state.status = { ...baseStatus, pushEnabled: false }

    await state.handlePushToggle()

    expect(updateSettingsMock).toHaveBeenCalledWith([
      { key: 'feishu.pushEnabled', value: 'true', version: 4 },
    ])
    expect(state.status?.pushEnabled).toBe(true)
    expect(ElMessage.success).toHaveBeenCalledWith('消息推送已开启')
  })

  it('设置保存冲突时重新读取状态且不显示成功', async () => {
    confirmMock.mockResolvedValue(undefined)
    updateSettingsMock.mockRejectedValue(new Error('10005'))
    fetchStatusMock.mockResolvedValue({ ...baseStatus, pushEnabled: false })
    const { state } = await renderPanel()
    state.status = { ...baseStatus, pushEnabled: false }

    await state.handlePushToggle()

    expect(updateSettingsMock).toHaveBeenCalledOnce()
    expect(state.status?.pushEnabled).toBe(false)
    expect(ElMessage.success).not.toHaveBeenCalledWith('消息推送已开启')
  })

  it('取消启动机器人时不读取或修改设置', async () => {
    confirmMock.mockRejectedValue(new Error('cancel'))
    const { state } = await renderPanel()
    state.status = { ...baseStatus, botEnabled: false }

    await state.handleBotToggle()

    expect(getSettingsMock).not.toHaveBeenCalled()
    expect(updateSettingsMock).not.toHaveBeenCalled()
    expect(state.status?.botEnabled).toBe(false)
  })

  it('确认启动时提交机器人开关当前版本并刷新状态', async () => {
    confirmMock.mockResolvedValue(undefined)
    const { state } = await renderPanel()
    state.status = { ...baseStatus, botEnabled: false }

    await state.handleBotToggle()

    expect(updateSettingsMock).toHaveBeenCalledWith([
      { key: 'feishu.botEnabled', value: 'true', version: 2 },
    ])
    expect(state.status?.botEnabled).toBe(true)
    expect(state.status?.pushEnabled).toBe(true)
    expect(ElMessage.success).toHaveBeenCalledWith('机器人已启动')
  })

  it('机器人开关保存冲突时回读停用状态且不显示成功', async () => {
    confirmMock.mockResolvedValue(undefined)
    updateSettingsMock.mockRejectedValue(new Error('10005'))
    fetchStatusMock.mockResolvedValue({ ...baseStatus, botEnabled: false })
    const { state } = await renderPanel()
    state.status = { ...baseStatus, botEnabled: false }

    await state.handleBotToggle()

    expect(updateSettingsMock).toHaveBeenCalledOnce()
    expect(state.status?.botEnabled).toBe(false)
    expect(ElMessage.success).not.toHaveBeenCalledWith('机器人已启动')
  })

  it('确认手动执行后保存扫描、新建和去重结果', async () => {
    confirmMock.mockResolvedValue(undefined)
    runMock.mockResolvedValue({ scanned: 8, created: 3, deduplicated: 5 })
    const { state } = await renderPanel()

    await state.handleRun('PROGRESS')

    expect(confirmMock).toHaveBeenCalledOnce()
    expect(runMock).toHaveBeenCalledWith('PROGRESS', 'bot-test-key')
    expect(unref(state.runResult)).toEqual({
      job: 'PROGRESS',
      scanned: 8,
      created: 3,
      deduplicated: 5,
    })
    expect(ElMessage.success).toHaveBeenCalledWith('立即检查进度已执行')
  })
})
