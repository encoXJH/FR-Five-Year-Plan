import { describe, expect, it } from 'vitest'
import { commandConfig, normalizeList, resolveApiErrorMessage, withLock } from '@/api/client'
import { transferAttachmentUrl } from '@/api/adjustments'

describe('API 响应与命令基础设施', () => {
  it('列表响应兼容数组和 items/total', () => {
    expect(normalizeList([{ id: 1 }, { id: 2 }])).toEqual({ items: [{ id: 1 }, { id: 2 }], total: 2 })
    expect(normalizeList({ items: [{ id: 1 }], total: 99 })).toEqual({ items: [{ id: 1 }], total: 99 })
    expect(normalizeList({ data: { items: [{ id: 3 }], total: 1 } })).toEqual({ items: [{ id: 3 }], total: 1 })
  })

  it('友好解析嵌套后端错误和 Pydantic details', () => {
    const message = resolveApiErrorMessage({ detail: { code: 'INVALID', message: '指标保存失败', details: [{ loc: ['body', 'indicator_code'], msg: 'Extra inputs are not permitted' }] } }, 422)
    expect(message).toBe('指标保存失败：indicator_code：Extra inputs are not permitted')
    expect(message).not.toContain('[object Object]')
  })

  it('展开发布计划 details 对象且不丢失字段语境', () => {
    const message = resolveApiErrorMessage({ detail: { message: '计划仍有指标未达到发布条件', details: { 12: ['年度目标未确认', '指标口径待补'] } } }, 422)
    expect(message).toBe('计划仍有指标未达到发布条件：12：年度目标未确认；12：指标口径待补')
    expect(message).not.toContain('[object Object]')
    expect(resolveApiErrorMessage({ detail: [{ loc: ['body', 'name'], msg: '不能为空' }] }, 422)).toBe('name：不能为空')
    expect(resolveApiErrorMessage({}, 409)).toBe('数据已被更新或业务状态冲突，请刷新后重试')
  })

  it('划转附件仅打开受支持的预览地址', () => {
    expect(transferAttachmentUrl({ object_key: 'a', preview_url: '/api/v1/transfers/attachments/a' })).toBe('/api/v1/transfers/attachments/a')
    expect(transferAttachmentUrl({ object_key: 'b', url: 'https://example.com/preview.pdf' })).toBe('https://example.com/preview.pdf')
    expect(transferAttachmentUrl({ object_key: 'c', preview_url: 'javascript:alert(1)' })).toBe('')
    expect(transferAttachmentUrl({ object_key: 'd' })).toBe('')
  })

  it('命令自动携带幂等键和可选版本锁', () => {
    const config = commandConfig('test-command-key')
    expect(config.headers).toEqual({ 'Idempotency-Key': 'test-command-key' })
    expect(withLock({ reason: '修订' }, 7)).toEqual({ reason: '修订', expected_version: 7 })
    expect(withLock({ reason: '修订' })).toEqual({ reason: '修订' })
  })
})
