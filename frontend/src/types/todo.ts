/** 待办分组（API 约定 15.9：待填报 / 待审核 / 待确认接收 / 待审批） */
export type TodoGroup = 'FILL' | 'REVIEW' | 'ACCEPT' | 'APPROVAL'

/**
 * GET /todos 待办条目。
 * bizId 语义：FILL / ACCEPT = B 任务 id；REVIEW = 填报 id；APPROVAL = 调整申请 id。
 */
export interface TodoItem {
  type: TodoGroup | string
  bizId: string
  bizCode: string | null
  detail: string | null
  todoSince: string
  /** 截止时间（YYYY-MM-DD），仅 FILL / REVIEW 有值 */
  deadline?: string | null
}

/** GET /todos 响应：items 按 todoSince 升序；summary 为各类型计数 */
export interface TodosResult {
  items: TodoItem[]
  summary: Partial<Record<TodoGroup, number>>
}
