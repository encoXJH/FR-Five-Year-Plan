import { describe, expect, it } from 'vitest'
import { resolveTodoLink, sortTodoItems, todoGroupLabel } from '../todo'
import type { TodoItem } from '@/types/todo'

function todo(partial: Partial<TodoItem>): TodoItem {
  return { type: 'FILL', bizId: '1', bizCode: 'A01-1-C01', detail: 't', todoSince: '2026-01-01T00:00:00+08:00', ...partial }
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
  it('待填报带任务 id 跳填报页，否则回工作台', () => {
    expect(resolveTodoLink(todo({ type: 'FILL', bizId: '42' }))).toBe('/tasks/42/submit')
    expect(resolveTodoLink(todo({ type: 'FILL', bizId: '' }))).toBe('/workbench')
  })

  it('待审核跳审核中心', () => {
    expect(resolveTodoLink(todo({ type: 'REVIEW', bizId: '7' }))).toBe('/reviews')
  })

  it('待确认接收 / 待审批跳对应页面；未知分组返回 null', () => {
    expect(resolveTodoLink(todo({ type: 'ACCEPT' }))).toBe('/workbench')
    expect(resolveTodoLink(todo({ type: 'APPROVAL' }))).toBe('/adjustments')
    expect(resolveTodoLink(todo({ type: 'X' }))).toBeNull()
  })
})
