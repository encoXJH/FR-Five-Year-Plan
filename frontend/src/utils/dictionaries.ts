import type { CalcMethod, RoleCode } from '@/types/api'

export const roleLabels: Record<string, string> = {
  ADMIN: '平台管理员',
  PLATFORM_ADMIN: '平台管理员',
  SCHOOL_LEADER: '校领导',
  SCHOOL_OWNER: '校级项目干系人',
  COLLEGE_OWNER: '学院项目干系人',
}

export const calcMethodOptions: Array<{ value: CalcMethod; label: string; hint: string }> = [
  { value: 'CUMULATIVE', label: '累计型', hint: '年度增量累计，可按规则顺延与补位' },
  { value: 'ANNUAL_QUOTA', label: '年度定额型', hint: '每年独立达标，不默认跨年度抵扣' },
  { value: 'FINAL', label: '期末型', hint: '按指定期末目标结算' },
  { value: 'RATIO', label: '比率型', hint: '需明确分子、分母和统计时点' },
  { value: 'GROWTH', label: '增长率型', hint: '需填写基期绝对值和报告期绝对值' },
  { value: 'BOOLEAN', label: '是否型', hint: '审核认定“是”方可完成' },
  { value: 'EXTERNAL', label: '外部数据型', hint: '不生成学院填报，由校级录入外部认定' },
]

const statusText: Record<string, string> = {
  DRAFT: '草稿', PUBLISHED: '已发布', IN_PROGRESS: '执行中', CLOSED: '已结项', ENABLED: '启用', DISABLED: '停用', ARCHIVED: '已归档',
  PENDING_RECEIVE: '待接收', COMPLETED: '已完成', REVOKED: '已撤销', TERMINATED: '已终止',
  NOT_STARTED: '未开始', PENDING_REPORT: '待填报', UNDER_REVIEW: '审核中', RECOGNIZED_UNMET: '已认定未达标', RETURNED: '已退回', APPROVED: '已通过', WITHDRAWN: '已撤回',
  PENDING: '待处理', READ: '已读', CANCELLED: '已取消', PROCESSING: '处理中', READY: '可预览', FAILED: '失败',
  PREVIEW: '待预览', PARTIAL_CONFIRMED: '部分确认', PARSED: '已解析', NEED_CONFIRM: '待确认', CONFIRMED: '已确认', INVALID: '不可导入',
  PENDING_APPROVAL: '待审批', EFFECTIVE: '已生效', REVERSED: '已冲销',
  PENDING_OUT_CONFIRM: '待转出确认', PENDING_IN_CONFIRM: '待转入确认', PENDING_LEADER_APPROVAL: '待校领导审批',
  PENDING_REPORT_REVIEW: '待审核', RECOGNIZED: '已认定', ADJUSTED: '已调整', EXTERNAL: '外部数据',
  LEAD: '牵头', COLLABORATOR: '协同', SCHOOL_OWNER: '校级负责人', COLLEGE_OWNER: '学院负责人',
  GE: '不少于／不低于', LE: '不超过／不高于', EQ: '精确等于',
  CONSTRAINED: '约束性', DEVELOPMENT: '发展性', FEATURED: '特色',
}

const statusType: Record<string, string> = {
  DRAFT: 'info', PENDING: 'warning', PENDING_RECEIVE: 'warning', PENDING_REPORT: 'warning', UNDER_REVIEW: 'primary', PROCESSING: 'primary',
  COMPLETED: 'success', APPROVED: 'success', EFFECTIVE: 'success', ENABLED: 'success', READY: 'success', CONFIRMED: 'success',
  RETURNED: 'danger', FAILED: 'danger', INVALID: 'danger', RECOGNIZED_UNMET: 'danger', DISABLED: 'info', WITHDRAWN: 'info', REVOKED: 'info', TERMINATED: 'info',
  PENDING_OUT_CONFIRM: 'warning', PENDING_IN_CONFIRM: 'warning', PENDING_LEADER_APPROVAL: 'warning', PENDING_APPROVAL: 'warning',
  IN_PROGRESS: 'primary', PUBLISHED: 'primary', NEED_CONFIRM: 'warning', PARTIAL_CONFIRMED: 'warning',
}

export function statusLabel(status?: string | null): string {
  if (!status) return '未提供'
  return statusText[status] || calcMethodOptions.find((item) => item.value === status)?.label || roleLabels[status as RoleCode] || status
}

export function statusTagType(status?: string | null): 'success' | 'info' | 'warning' | 'danger' | 'primary' {
  return (statusType[status || ''] || 'info') as 'success' | 'info' | 'warning' | 'danger' | 'primary'
}

export const objectTypeLabels: Record<string, string> = {
  PLAN: '建设计划', DIRECTION: '建设方向', INDICATOR: '关键产出指标', TASK: '学院任务', MILESTONE: '年度里程碑', SUBMISSION: '填报版本', MATERIAL: '佐证材料', REVIEW: '审核记录', CARRYOVER: '年度顺延', TRANSFER: '跨院划转', USER: '用户', SYSTEM: '系统',
}

export const actionLabels: Record<string, string> = {
  CREATE: '新建', UPDATE: '修改', PUBLISH: '发布', COPY: '复制', ASSIGN: '下发', RECEIVE: '接收', REVOKE: '撤销', SUBMIT: '提交', WITHDRAW: '撤回', RETURN: '退回', REVIEW: '审核', CORRECT: '审核更正', UPLOAD: '上传材料', CARRYOVER: '年度顺延', COMPENSATE: '年度补位', TRANSFER: '跨院划转', CONFIRM: '确认', APPROVE: '批准', EXPORT: '导出', LOGIN: '登录', PERMISSION_CHANGE: '权限变更', IMPORT: 'Excel 导入', OBJECT: '提交异议',
}
