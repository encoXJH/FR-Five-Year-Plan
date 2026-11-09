import { describe, expect, it } from 'vitest'
import { resolveNotificationLink, resolveTodoLink, sortTodoItems, todoGroupLabel } from '../todo'
import type { NotificationItem } from '@/types/notification'
import type { TodoItem } from '@/types/todo'

function todo(partial: Partial<TodoItem>): TodoItem {
  return {
    type: 'FILL',
    bizId: '1',
    planId: '11',
    year: 2026,
    bizCode: 'A01-1-C01',
    detail: 't',
    todoSince: '2026-01-01T00:00:00+08:00',
    ...partial,
  }
}

function notification(partial: Partial<NotificationItem>): NotificationItem {
  return {
    id: '1',
    type: 'REVIEW_RESULT',
    title: '通知',
    content: null,
    bizType: 'submission',
    bizId: '7',
    link: null,
    readAt: null,
    createdAt: '2026-01-01T00:00:00+08:00',
    ...partial,
  }
}

describe('todoGroupLabel', () => {
  it('返回中文名，未知分组原样返回', () => {
    expect(todoGroupLabel('FILL')).toBe('待填报')
    expect(todoGroupLabel('APPROVAL')).toBe('待审批')
    expect(todoGroupLabel('UNKNOWN')).toBe('UNKNOWN')
  })
})

describe('sortTodoItems', () => {
  it('按 待填报 → 待审核 → 待确认接收 → 待审批 排序，未知分组最后', () => {
    const items = [
      todo({ bizId: '1', type: 'APPROVAL' }),
      todo({ bizId: '2', type: 'FILL' }),
      todo({ bizId: '3', type: 'X' }),
      todo({ bizId: '4', type: 'ACCEPT' }),
    ]
    expect(sortTodoItems(items).map((i) => i.bizId)).toEqual(['2', '4', '1', '3'])
  })
})

describe('resolveTodoLink', () => {
  it('待填报带任务和年度跳填报页，否则回工作台', () => {
    expect(resolveTodoLink(todo({ type: 'FILL', bizId: '42', year: 2027 }))).toBe(
      '/tasks/42/submit?year=2027',
    )
    expect(resolveTodoLink(todo({ type: 'FILL', bizId: '' }))).toBe('/workbench')
  })

  it('待审核携带填报 id 跳审核中心定位', () => {
    expect(resolveTodoLink(todo({ type: 'REVIEW', bizId: '7' }))).toBe('/reviews?submissionId=7')
  })

  it('待确认接收和待审批携带业务 id；未知分组返回 null', () => {
    expect(resolveTodoLink(todo({ type: 'ACCEPT', bizId: '42', year: 2028 }))).toBe(
      '/workbench?taskId=42&year=2028',
    )
    expect(resolveTodoLink(todo({ type: 'APPROVAL', bizId: '9' }))).toBe(
      '/adjustments?adjustmentId=9',
    )
    expect(resolveTodoLink(todo({ type: 'X' }))).toBeNull()
  })
})

describe('resolveNotificationLink', () => {
  it('优先使用后端 link，包括覆盖同业务对象的默认回退', () => {
    expect(
      resolveNotificationLink(
        notification({ link: '/tasks/42/submit?year=2027', bizType: 'submission', bizId: '7' }),
      ),
    ).toBe('/tasks/42/submit?year=2027')
  })

  it('空 link 按 bizType / bizId 回退到对应业务页', () => {
    expect(resolveNotificationLink(notification({ bizType: 'submission', bizId: '7' }))).toBe(
      '/reviews?submissionId=7',
    )
    expect(resolveNotificationLink(notification({ bizType: 'task', bizId: '42' }))).toBe(
      '/workbench?taskId=42',
    )
    expect(resolveNotificationLink(notification({ bizType: 'adjustment', bizId: '9' }))).toBe(
      '/adjustments?adjustmentId=9',
    )
    expect(resolveNotificationLink(notification({ bizType: 'task_year', bizId: '10' }))).toBe(
      '/workbench',
    )
  })

  it('无法识别业务对象时返回 null，调用方不得只标记已读', () => {
    expect(resolveNotificationLink(notification({ bizType: 'unknown' }))).toBeNull()
    expect(resolveNotificationLink(notification({ bizId: null }))).toBeNull()
  })
})
