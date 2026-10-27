import type { EnumDict } from '@/types/enums'

/** 枚举中文名兜底表（线上以 GET /dicts/enums 为准，仅覆盖表单与展示用到的组） */
export const FALLBACK_ENUM_LABELS: Record<string, Record<string, string>> = {
  PlanStatus: { DRAFT: '草稿', ACTIVE: '执行中', ARCHIVED: '已归档' },
  PlanSource: { BLANK: '空白新建', TEMPLATE: '从模板新建', COPY: '复制已有计划' },
  SubjectType: { SCHOOL: '校级', MAJOR_GROUP: '专业群' },
  IndicatorLevel: { A: 'A 类', A_PLUS: 'A+ 类', B: 'B 类' },
  IndicatorNature: { CONSTRAINT: '约束性', DEVELOPMENT: '发展性' },
  MeasureType: {
    NUMBER: '数值',
    BOOLEAN: '是否',
    PERCENT_QUANTIFIABLE: '百分比-可量化',
    PERCENT_DIRECT: '百分比-不可量化',
  },
  StatCaliber: {
    INCREMENT: '增量累计型',
    STOCK: '存量时点型',
    GROWTH: '增长率型',
    RATIO: '占比型',
    DIRECT_PERCENT: '直填百分比型',
    BOOLEAN: '是否型',
  },
  StatCaliberShort: {
    INCREMENT: '增量',
    STOCK: '存量',
    GROWTH: '增长率',
    RATIO: '占比',
    DIRECT_PERCENT: '直填%',
    BOOLEAN: '是否',
  },
  TargetDirection: { HIGHER_BETTER: '越大越好', LOWER_BETTER: '越小越好' },
  RollupMode: { AUTO_SUM: '自动汇总', MANUAL: '手工认定' },
  IndicatorStatus: { DRAFT: '草稿', FINALIZED: '已定稿', DISTRIBUTED: '已下发' },
  TemplateStatus: { ENABLED: '启用', DISABLED: '停用' },
  JobStatus: { PENDING: '排队中', RUNNING: '执行中', SUCCEEDED: '成功', FAILED: '失败' },
  AdjustmentType: {
    POSTPONE: '延期',
    TRANSFER: '转移',
    POSTPONE_TRANSFER: '延期 + 转移',
    TARGET_CHANGE: '目标调整',
  },
  ApprovalNode: {
    OWNER_REVIEW: '校级负责人初审',
    RECEIVER_CONFIRM: '接收方确认',
    LEADER_APPROVAL: '校领导审批',
  },
  Decision: { APPROVE: '同意', REJECT: '驳回' },
  MilestoneStatus: {
    NOT_STARTED: '未开始',
    IN_PROGRESS: '进行中',
    COMPLETED: '已完成',
    OVERDUE: '逾期',
    POSTPONED: '已延期',
  },
  Role: {
    ADMIN: '平台管理员',
    LEADER: '校领导',
    SCHOOL_OWNER: '校级项目负责人',
    UNIT_OWNER: '学院项目负责人',
  },
  OrgUnitType: {
    COLLEGE: '产业学院',
    INTERNATIONAL_COLLEGE: '国际学院',
    MARXISM_COLLEGE: '马克思主义学院',
    SCHOOL_OFFICE: '校办公室',
  },
  ClientType: {
    WEB: '网页端',
    FEISHU_PC: '飞书 PC',
    FEISHU_MOBILE: '飞书移动端',
    SCREEN: '大屏',
  },
  NotificationType: {
    TASK_DISTRIBUTED: '任务下发',
    DEADLINE_REMINDER: '截止提醒',
    REVIEW_RESULT: '审核结果',
    ADJUSTMENT_TODO: '调整待办',
    ADJUSTMENT_RESULT: '调整结果',
    WARNING: '预警',
  },
  AuditAction: {
    LOGIN: '登录',
    CREATE: '新增',
    UPDATE: '修改',
    DELETE: '删除',
    IMPORT: '导入',
    EXPORT: '导出',
    FINALIZE: '定稿',
    DISTRIBUTE: '下发',
    ACCEPT: '确认接收',
    SUBMIT: '提交',
    WITHDRAW: '撤回',
    REVIEW: '审核',
    ADJUST_APPLY: '发起调整',
    ADJUST_DECIDE: '调整审批',
    SETTLE: '年度核算',
    UNLOCK: '解锁',
    PERMISSION_CHANGE: '修改角色权限',
    FILE_DOWNLOAD: '文件下载',
    FILE_PREVIEW: '文件预览',
  },
}

/** 取枚举中文名：优先字典，其次兜底表，未知值返回原值 */
export function enumLabel(kind: string, value: string, dict?: EnumDict): string {
  return dict?.[kind]?.[value] ?? FALLBACK_ENUM_LABELS[kind]?.[value] ?? value
}

export interface EnumOption {
  value: string
  label: string
}

/** 生成下拉选项（字典中该组的全部值；字典缺失时用兜底表） */
export function enumOptions(kind: string, dict?: EnumDict): EnumOption[] {
  const source = dict?.[kind] ?? FALLBACK_ENUM_LABELS[kind] ?? {}
  return Object.keys(source).map((value) => ({ value, label: enumLabel(kind, value, dict) }))
}
