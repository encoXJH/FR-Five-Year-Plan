import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { loadFeishuSdk, requestFeishuAccessCode } from '../feishuSdk'

describe('飞书 JS SDK 免登', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('SDK 加载始终无回调时在 8 秒后失败', async () => {
    const script = { addEventListener: vi.fn() }
    vi.stubGlobal('window', {})
    vi.stubGlobal('document', {
      querySelector: vi.fn(() => null),
      createElement: vi.fn(() => script),
      head: { appendChild: vi.fn() },
    })

    const promise = loadFeishuSdk()
    const rejection = expect(promise).rejects.toThrow('飞书 SDK 加载超时')
    await vi.advanceTimersByTimeAsync(8000)
    await rejection
  })

  it('h5sdk.ready 不回调时在 8 秒后失败', async () => {
    vi.stubGlobal('window', {
      h5sdk: { ready: vi.fn() },
      tt: { requestAccess: vi.fn() },
    })

    const promise = requestFeishuAccessCode('cli_test')
    const rejection = expect(promise).rejects.toThrow('飞书免登授权超时')
    await vi.advanceTimersByTimeAsync(8000)
    await rejection
  })

  it('requestAccess 正常返回授权码', async () => {
    const requestAccess = vi.fn((options: { success: (result: { code: string }) => void }) => {
      options.success({ code: 'code-1' })
    })
    vi.stubGlobal('window', {
      h5sdk: { ready: (callback: () => void) => callback() },
      tt: { requestAccess },
    })

    await expect(requestFeishuAccessCode('cli_test')).resolves.toBe('code-1')
    expect(requestAccess).toHaveBeenCalledWith(expect.objectContaining({
      appID: 'cli_test',
      scopeList: [],
    }))
  })
})
