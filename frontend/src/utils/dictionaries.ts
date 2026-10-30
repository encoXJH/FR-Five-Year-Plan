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
  PLAN: '建设计划', DIRECTORY: '建设目录', DIRECTION: '建设方向', INDICATOR: '关键产出指标', INDICATOR_TARGET: '指标年度目标',
  TASK: '学院任务', TASK_OBJECTION: '任务异议', MILESTONE: '年度里程碑', SUBMISSION: '填报版本', MATERIAL: '佐证材料',
  REVIEW: '审核记录', EXTERNAL_RECOGNITION: '外部认定', CARRYOVER: '年度顺延', COMPENSATION: '年度补位', TRANSFER: '跨院划转',
  IMPORT_FILE: '导入批次', IMPORT_ROW: '导入源行', USER: '用户', ORG: '组织', PROJECT_MEMBER: '项目成员', CONFIG: '运行配置',
  LOG_EXPORT: '日志导出', SYSTEM: '系统',
}

export const actionLabels: Record<string, string> = {
  CREATE: '新建', UPDATE: '修改', DELETE: '删除', DELETE_DRAFT: '删除草稿', COPY: '复制', PUBLISH: '发布', AUTO_PUBLISH: '自动发布',
  START: '开始执行', CLOSE: '结项', QUANTIFY: '指标量化', TARGET_UPDATE: '年度目标调整',
  ASSIGN: '下发任务', RECEIVE: '接收任务', REVOKE: '撤销下发', OBJECTION_CREATE: '提交异议', OBJECTION_REPLY: '答复异议',
  DRAFT_CREATE: '创建填报草稿', SUBMIT: '提交填报', WITHDRAW: '撤回', RETURN: '退回', REVIEW: '审核', REVIEW_CORRECTION: '审核更正',
  CORRECT: '审核更正', UPLOAD: '上传材料', EXTERNAL_RECOGNIZE: '外部认定',
  IMPORT_PREVIEW: '导入预览', IMPORT_CONFIRM: '导入写入', IMPORT_CONFIRM_REGENERATE: '导入重新生成', SOURCE_CORRECTION: '源表修正',
  CARRYOVER: '年度顺延', CARRYOVER_INITIATE: '发起顺延', CARRYOVER_APPROVE: '批准顺延', CARRYOVER_RETURN: '退回顺延', CARRYOVER_WITHDRAW: '撤回顺延',
  COMPENSATE: '年度补位', COMPENSATION_REVERSE: '补位冲销',
  TRANSFER: '跨院划转', TRANSFER_INITIATE: '发起划转', TRANSFER_WITHDRAW: '撤回划转',
  TRANSFER_OUT_COLLEGE_CONFIRM: '转出学院确认', TRANSFER_OUT_COLLEGE_RETURN: '转出学院退回',
  TRANSFER_IN_COLLEGE_CONFIRM: '转入学院确认', TRANSFER_IN_COLLEGE_RETURN: '转入学院退回',
  TRANSFER_SCHOOL_LEADER_APPROVE: '校领导批准', TRANSFER_SCHOOL_LEADER_RETURN: '校领导退回',
  TRANSFER_ATTACHMENT_UPLOAD: '上传划转材料',
  CONFIRM: '确认', APPROVE: '批准', EXPORT: '导出日志', LOGIN: '登录',
  ROLE_GRANT: '授予角色', ROLE_REVOKE: '撤销角色', DISABLE: '停用账号', HANDOVER: '成员交接', PERMISSION_CHANGE: '权限变更', OBJECT: '提交异议',
}
