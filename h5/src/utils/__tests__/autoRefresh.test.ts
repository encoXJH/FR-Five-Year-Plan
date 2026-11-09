import { describe, expect, it } from 'vitest'
import { hasOpenOverlay, shouldApplyRefreshResponse, shouldAutoRefresh } from '../autoRefresh'

describe('shouldAutoRefresh', () => {
  it('页面可见且无编辑内容时允许刷新', () => {
    expect(shouldAutoRefresh({ visible: true })).toBe(true)
  })

  it('页面不可见时跳过', () => {
    expect(shouldAutoRefresh({ visible: false })).toBe(false)
  })

  it('页面停用自动刷新时跳过', () => {
    expect(shouldAutoRefresh({ visible: true, enabled: false })).toBe(false)
  })

  it('存在编辑中内容时跳过', () => {
    expect(shouldAutoRefresh({ visible: true, editing: true })).toBe(false)
  })

  it('页面声明未保存编辑时跳过', () => {
    expect(shouldAutoRefresh({ visible: true, skipping: true })).toBe(false)
  })
})

describe('shouldApplyRefreshResponse', () => {
  it('请求等待期间开始编辑后丢弃自动刷新响应', async () => {
    let resolveRequest!: (value: string) => void
    const request = new Promise<string>((resolve) => { resolveRequest = resolve })
    const edits: Record<string, string> = {}
    let applied = false
    const response = request.then((value) => {
      if (!shouldApplyRefreshResponse({
        current: true,
        silent: true,
        skipping: Object.keys(edits).length > 0,
      })) return
      edits.value = value
      applied = true
    })

    edits['plan.fillDeadline'] = '11-30'
    resolveRequest('server-value')
    await response

    expect(applied).toBe(false)
    expect(edits['plan.fillDeadline']).toBe('11-30')
  })

  it('请求仍有效且没有编辑时允许响应落地', () => {
    expect(shouldApplyRefreshResponse({ current: true, silent: true })).toBe(true)
  })

  it('过期请求即使没有编辑也不能落地', () => {
    expect(shouldApplyRefreshResponse({ current: false, silent: false })).toBe(false)
  })

  it('用户主动重置时允许覆盖编辑', () => {
    expect(shouldApplyRefreshResponse({ current: true, silent: false, skipping: true })).toBe(true)
  })
})

describe('hasOpenOverlay', () => {
  function fakeRoot(styles: { display?: string; visibility?: string }[]): ParentNode {
    return {
      querySelectorAll: () => styles.map((style) => ({ style })),
    } as unknown as ParentNode
  }

  it('存在可见遮罩时判定为编辑中', () => {
    expect(hasOpenOverlay(fakeRoot([{ display: '' }]))).toBe(true)
  })

  it('遮罩已隐藏时不阻塞刷新', () => {
    expect(hasOpenOverlay(fakeRoot([{ display: 'none' }]))).toBe(false)
    expect(hasOpenOverlay(fakeRoot([{ display: '', visibility: 'hidden' }]))).toBe(false)
  })

  it('没有遮罩时不阻塞刷新', () => {
    expect(hasOpenOverlay(fakeRoot([]))).toBe(false)
  })
})
