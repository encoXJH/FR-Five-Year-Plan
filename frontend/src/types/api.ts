/** 统一响应结构（API 约定 6.1） */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  requestId: string
  errors?: FieldError[]
}

/** 字段级错误（API 约定 6.3，仅出错时出现） */
export interface FieldError {
  field: string
  message: string
}

/** 分页响应 data（API 约定 6.2） */
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

/** 分页请求参数（API 约定 5.1） */
export interface PageQuery {
  page?: number
  pageSize?: number
  sort?: string
}

/** 用户引用对象（API 约定 3.4） */
export interface UserRef {
  id: string
  name: string
}

/** 执行单元引用对象（API 约定 3.4） */
export interface UnitRef {
  id: string
  code: string
  name: string
}
