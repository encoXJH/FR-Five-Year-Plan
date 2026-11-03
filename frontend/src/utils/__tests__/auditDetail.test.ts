import { describe, expect, it } from 'vitest'
import { buildAuditDetail, parseAuditSnapshot } from '../auditDetail'
import type { AuditLogItem } from '@/types/audit'

function row(overrides: Partial<AuditLogItem>): AuditLogItem {
  return {
    id: '1',
    occurredAt: '2026-11-03T09:00:00+08:00',
    operator: { id: '99', name: '管理员' },
    roles: 'ADMIN',
    unit: null,
    action: 'UPDATE',
    resourceType: 'indicator',
    resourceId: '301',
    resourceCode: 'A01',
    before: null,
    after: null,
    reason: null,
    clientType: 'WEB',
    ip: '127.0.0.1',
    requestId: 'r1',
    ...overrides,
  }
}

describe('parseAuditSnapshot', () => {
  it('解析对象与数组，非法 JSON 原样返回', () => {
    expect(parseAuditSnapshot('{"a":1}')).toEqual({ a: 1 })
    expect(parseAuditSnapshot('[{"id":"1"}]')).toEqual([{ id: '1' }])
    expect(parseAuditSnapshot('not-json')).toBe('not-json')
    expect(parseAuditSnapshot(null)).toBeNull()
  })
})

describe('buildAuditDetail 更新类', () => {
  it('用中文标签展示字段变化并忽略技术字段', () => {
    const detail = buildAuditDetail(row({
      before: JSON.stringify({
        id: '301',
        version: 3,
        name: '旧名称',
        totalTarget: 100,
        statCaliber: 'INCREMENT',
        isQuantifiable: true,
        owner: { id: '9', name: '张三' },
      }),
      after: JSON.stringify({
        id: '301',
        version: 4,
        name: '新名称',
        totalTarget: 120,
        statCaliber: 'STOCK',
        isQuantifiable: false,
        owner: { id: '9', name: '李四' },
      }),
    }))

    expect(detail.summary).toContain('修改指标「A01」')
    expect(detail.summary).toContain('共 5 项变更')
    expect(detail.changes.map((entry) => entry.label)).toEqual(['名称', '五年总量', '统计口径', '是否可量化', '负责人'])
    expect(detail.changes[0]).toMatchObject({ before: '旧名称', after: '新名称' })
    expect(detail.changes[1]).toMatchObject({ before: '100', after: '120' })
    expect(detail.changes[2]).toMatchObject({ before: '增量累计型', after: '存量时点型' })
    expect(detail.changes[3]).toMatchObject({ before: '是', after: '否' })
    expect(detail.changes[4]).toMatchObject({ before: '张三', after: '李四' })
  })

  it('快照一致时说明未发现字段变化', () => {
    const snapshot = JSON.stringify({ name: '同名', totalTarget: 10 })
    const detail = buildAuditDetail(row({ before: snapshot, after: snapshot }))

    expect(detail.summary).toContain('未发现字段变化')
    expect(detail.changes).toHaveLength(0)
  })
})

describe('buildAuditDetail 新增与删除', () => {
  it('新增展示创建内容', () => {
    const detail = buildAuditDetail(row({
      action: 'CREATE',
      after: JSON.stringify({ name: '新指标', statCaliber: 'BOOLEAN', yearTargets: [{ year: 2026, target: 1 }] }),
    }))

    expect(detail.summary).toContain('新增指标「A01」')
    expect(detail.summary).toContain('名称「新指标」')
    expect(detail.changes).toHaveLength(0)
    expect(detail.facts).toEqual([
      { label: '名称', value: '新指标' },
      { label: '统计口径', value: '是否型' },
      { label: '年度目标', value: '2026:1' },
    ])
  })

  it('删除展示删除前内容', () => {
    const detail = buildAuditDetail(row({
      action: 'DELETE',
      before: JSON.stringify({ name: '待删指标', code: 'A09' }),
    }))

    expect(detail.summary).toContain('删除指标「A01」')
    expect(detail.summary).toContain('删除前')
    expect(detail.facts.map((fact) => fact.label)).toEqual(['名称', '编号'])
  })
})

describe('buildAuditDetail 无快照与批量快照', () => {
  it('无快照时按动作给出可读说明', () => {
    const download = buildAuditDetail(row({
      action: 'FILE_DOWNLOAD',
      resourceType: 'file',
      resourceId: '12',
      resourceCode: null,
    }))
    expect(download.summary).toBe('下载文件「12」')
    expect(download.note).toBe('该操作未记录字段快照。')

    const login = buildAuditDetail(row({ action: 'LOGIN', resourceType: null, resourceCode: null }))
    expect(login.summary).toBe('登录系统')
  })

  it('数组快照按条数统计新增、移除与变更', () => {
    const detail = buildAuditDetail(row({
      action: 'DISTRIBUTE',
      resourceType: 'task',
      before: JSON.stringify([
        { id: '1', code: 'A01-C03', name: '任务一', target: 10 },
        { id: '2', code: 'A01-C04', name: '任务二', target: 10 },
      ]),
      after: JSON.stringify([
        { id: '1', code: 'A01-C03', name: '任务一', target: 20 },
        { id: '3', code: 'A01-C05', name: '任务三', target: 10 },
      ]),
    }))

    expect(detail.summary).toBe('下发：共 2 条，新增 1 条，移除 1 条，变更 1 条')
    expect(detail.facts).toEqual([
      { label: '新增', value: 'A01-C05 任务三' },
      { label: '移除', value: 'A01-C04 任务二' },
      { label: '变更', value: 'A01-C03 任务一：年度目标' },
    ])
  })
})
