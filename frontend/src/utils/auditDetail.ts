/** 操作日志展开详情：把审计快照转成人能读懂的「做了什么」 */
import { enumLabel } from './enumLabels'
import type { AuditLogItem } from '@/types/audit'

export type AuditChangeType = 'added' | 'removed' | 'changed'

export interface AuditChange {
  field: string
  label: string
  before: string
  after: string
  changeType: AuditChangeType
}

export interface AuditFact {
  label: string
  value: string
}

export interface AuditDetail {
  /** 一句话说明本次操作 */
  summary: string
  /** 字段级变更（更新类） */
  changes: AuditChange[]
  /** 创建 / 删除时的内容明细 */
  facts: AuditFact[]
  /** 无快照时的补充说明 */
  note: string
}

const RESOURCE_LABELS: Record<string, string> = {
  plan: '计划',
  indicator: '指标',
  direction: '建设方向',
  task: '任务',
  task_dependency: '任务依赖',
  submission: '填报',
  adjustment: '调整申请',
  milestone: '里程碑',
  makeup: '补做记录',
  file: '文件',
  indicator_material: '指标材料',
  user: '用户',
  setting: '系统参数',
  template: '模板',
  feishu: '飞书同步',
  org_unit: '执行单元',
}

const FIELD_LABELS: Record<string, string> = {
  name: '名称',
  code: '编号',
  description: '说明',
  status: '状态',
  level: '层级',
  nature: '指标性质',
  measureType: '计量类型',
  statCaliber: '统计口径',
  unitLabel: '计量单位',
  targetDirection: '达成方向',
  rollupMode: '汇总方式',
  isQuantifiable: '是否可量化',
  evidenceRequirement: '佐证要求',
  minFileCount: '最少材料数',
  baselineValue: '基线值',
  totalTarget: '五年总量',
  sort: '排序',
  owner: '负责人',
  parent: '上级指标',
  yearTargets: '年度目标',
  children: '下级指标',
  bSummaries: '学院任务',
  directionName: '建设方向',
  planId: '所属计划',
  parentId: '上级指标',
  startYear: '起始年份',
  endYear: '结束年份',
  baselineYear: '基线年份',
  settleDeadline: '核算截止日',
  source: '计划来源',
  isReceiverConfirmRequired: '需要接收确认',
  years: '年度',
  roles: '角色',
  unitId: '执行单元',
  unitName: '执行单元',
  taskId: '任务',
  indicatorId: '指标',
  year: '年度',
  target: '年度目标',
  actualValue: '完成值',
  amount: '填报数量',
  total: '总量',
  numerator: '分子',
  denominator: '分母',
  percent: '百分比',
  achieved: '是否完成',
  approvedAmount: '认定数量',
  approvedTotal: '认定总量',
  approvedNumerator: '认定分子',
  approvedDenominator: '认定分母',
  approvedPercent: '认定百分比',
  approvedAchieved: '认定是否完成',
  type: '调整类型',
  reason: '原因',
  comment: '审批意见',
  applicant: '申请人',
  decision: '审批结论',
  node: '审批环节',
  quantity: '调整数量',
  targetYear: '目标年份',
  fromUnit: '来源单元',
  toUnit: '接收单元',
  fromTask: '来源任务',
  toTask: '接收任务',
  oldTarget: '调整前目标',
  newTarget: '调整后目标',
  taskCode: '任务编号',
  taskName: '任务名称',
  indicatorCode: '指标编号',
  indicatorName: '指标名称',
  unitCode: '单元编码',
  majorGroup: '专业群',
  statusName: '状态',
  path: '路径',
  content: '内容',
  version: '版本号',
}

const ENUM_FIELDS: Record<string, string> = {
  statCaliber: 'StatCaliber',
  targetDirection: 'TargetDirection',
  rollupMode: 'RollupMode',
  nature: 'IndicatorNature',
  measureType: 'MeasureType',
  level: 'IndicatorLevel',
  source: 'PlanSource',
  roles: 'Role',
  clientType: 'ClientType',
}

const IGNORED_FIELDS = new Set([
  'id',
  'version',
  'createdAt',
  'updatedAt',
  'createdBy',
  'updatedBy',
  'deleted',
  'calculatedAt',
  'username',
])

const IGNORED_SEGMENTS = new Set(['id', 'username', 'createdAt', 'updatedAt', 'createdBy', 'updatedBy', 'deleted'])

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 解析快照 JSON；数组原样返回，便于识别任务列表等批量快照 */
export function parseAuditSnapshot(raw: string | null | undefined): unknown {
  if (!raw) return null
  try {
    return JSON.parse(raw) as unknown
  } catch {
    return raw
  }
}

function flatten(value: Record<string, unknown>, prefix = ''): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [key, item] of Object.entries(value)) {
    const path = prefix ? `${prefix}.${key}` : key
    const isReference = isPlainObject(item) && (typeof item.name === 'string' || typeof item.code === 'string')
    if (isPlainObject(item) && !isReference && Object.keys(item).length > 0) {
      Object.assign(out, flatten(item, path))
    } else {
      out[path] = item
    }
  }
  return out
}

function labelOfPath(path: string): string {
  const parts = path.split('.')
  if (parts.length === 1) return FIELD_LABELS[path] ?? path
  return parts
    .map((part) => FIELD_LABELS[part] ?? part)
    .filter((part) => !/^\d{4}$/.test(part))
    .join('')
}

function formatYearItems(items: unknown[]): string {
  return items
    .map((item) => {
      if (!isPlainObject(item)) return formatValue(item)
      const year = item.year
      const value = item.target ?? item.value ?? item.status ?? item.actualValue
      return year === undefined || value === undefined
        ? formatValue(item)
        : `${year}:${formatValue(value)}`
    })
    .join('、')
}

function formatArray(items: unknown[]): string {
  if (items.length === 0) return '（空）'
  if (items.every((item) => isPlainObject(item) && 'year' in item)) return formatYearItems(items)
  if (items.every((item) => typeof item === 'string' || typeof item === 'number')) {
    return items.map((item) => formatValue(item)).join('、')
  }
  const names = items
    .map((item) => (isPlainObject(item) ? item.name ?? item.code ?? item.id : item))
    .filter((item) => item !== undefined && item !== null)
  return names.length > 0 ? names.map((item) => formatValue(item)).join('、') : `共 ${items.length} 项`
}

function formatObject(value: Record<string, unknown>): string {
  if (typeof value.name === 'string') return value.name
  if (typeof value.code === 'string') return value.code
  const text = JSON.stringify(value)
  return text.length > 80 ? `${text.slice(0, 80)}…` : text
}

function formatValue(value: unknown): string {
  if (value === undefined || value === null) return '（空）'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (Array.isArray(value)) return formatArray(value)
  if (isPlainObject(value)) return formatObject(value)
  return String(value)
}

function formatFieldValue(field: string, value: unknown, row: AuditLogItem): string {
  if (value === undefined || value === null) return '（空）'
  const enumKind = ENUM_FIELDS[field]
  if (enumKind && typeof value === 'string') return enumLabel(enumKind, value)
  if (field === 'status' && typeof value === 'string') {
    const candidate = row.resourceType === 'indicator'
      ? 'IndicatorStatus'
      : row.resourceType === 'plan'
        ? 'PlanStatus'
        : row.resourceType === 'milestone'
          ? 'MilestoneStatus'
          : row.resourceType === 'template'
            ? 'TemplateStatus'
            : ''
    if (candidate) {
      const label = enumLabel(candidate, value)
      if (label !== value) return label
    }
  }
  if (field === 'type' && row.resourceType === 'adjustment' && typeof value === 'string') {
    return enumLabel('AdjustmentType', value)
  }
  if (field === 'decision' && typeof value === 'string') return enumLabel('Decision', value)
  if (field === 'node' && typeof value === 'string') return enumLabel('ApprovalNode', value)
  return formatValue(value)
}

function isNoisePath(path: string): boolean {
  const parts = path.split('.')
  if (ignored(parts[parts.length - 1])) return true
  return parts.slice(1).some((part) => IGNORED_SEGMENTS.has(part))
}

function ignored(key: string): boolean {
  return IGNORED_FIELDS.has(key)
}

function objectDiff(
  before: Record<string, unknown>,
  after: Record<string, unknown>,
  row: AuditLogItem,
): AuditChange[] {
  const flatBefore = flatten(before)
  const flatAfter = flatten(after)
  const changes: AuditChange[] = []
  for (const field of Object.keys(flatBefore)) {
    if (isNoisePath(field) || !(field in flatAfter)) continue
    const oldValue = flatBefore[field]
    const newValue = flatAfter[field]
    if (JSON.stringify(oldValue) === JSON.stringify(newValue)) continue
    changes.push({
      field,
      label: labelOfPath(field),
      before: formatFieldValue(field, oldValue, row),
      after: formatFieldValue(field, newValue, row),
      changeType: 'changed',
    })
  }
  return changes
}

function factsOf(snapshot: Record<string, unknown>, row: AuditLogItem): AuditFact[] {
  const flat = flatten(snapshot)
  return Object.entries(flat)
    .filter(([path]) => !isNoisePath(path))
    .map(([path, value]) => ({
      label: labelOfPath(path),
      value: formatFieldValue(path, value, row),
    }))
    .filter((fact) => fact.value !== '（空）')
    .slice(0, 20)
}

function itemLabel(item: Record<string, unknown>): string {
  const code = item.code ?? item.taskCode ?? item.indicatorCode ?? item.unitCode
  const name = item.name ?? item.taskName ?? item.indicatorName ?? item.unitName
  if (code && name) return `${code} ${name}`
  return formatValue(code ?? name ?? item.id ?? item)
}

function arraySummary(
  before: unknown[],
  after: unknown[],
  row: AuditLogItem,
): AuditDetail {
  const beforeItems = before.filter(isPlainObject)
  const afterItems = after.filter(isPlainObject)
  const beforeById = new Map(beforeItems.map((item) => [String(item.id ?? item.code ?? ''), item]))
  const afterById = new Map(afterItems.map((item) => [String(item.id ?? item.code ?? ''), item]))
  const added = afterItems.filter((item) => !beforeById.has(String(item.id ?? item.code ?? '')))
  const removed = beforeItems.filter((item) => !afterById.has(String(item.id ?? item.code ?? '')))
  const changed = afterItems.filter((item) => {
    const old = beforeById.get(String(item.id ?? item.code ?? ''))
    return old !== undefined && JSON.stringify(old) !== JSON.stringify(item)
  })

  const parts: string[] = [`共 ${afterItems.length} 条`]
  if (added.length > 0) parts.push(`新增 ${added.length} 条`)
  if (removed.length > 0) parts.push(`移除 ${removed.length} 条`)
  if (changed.length > 0) parts.push(`变更 ${changed.length} 条`)

  const facts: AuditFact[] = []
  added.slice(0, 5).forEach((item) => facts.push({ label: '新增', value: itemLabel(item) }))
  removed.slice(0, 5).forEach((item) => facts.push({ label: '移除', value: itemLabel(item) }))
  changed.slice(0, 5).forEach((item) => {
    const old = beforeById.get(String(item.id ?? item.code ?? ''))
    const diff = old ? objectDiff(old, item, row) : []
    facts.push({
      label: '变更',
      value: diff.length > 0 ? `${itemLabel(item)}：${diff.map((entry) => entry.label).join('、')}` : itemLabel(item),
    })
  })

  return {
    summary: `${actionText(row)}：${parts.join('，')}`,
    changes: [],
    facts,
    note: '',
  }
}

export function resourceLabel(resourceType: string | null): string {
  return resourceType ? RESOURCE_LABELS[resourceType] ?? resourceType : '对象'
}

export function actionText(row: AuditLogItem): string {
  return enumLabel('AuditAction', row.action)
}

function targetText(row: AuditLogItem): string {
  const code = row.resourceCode ?? row.resourceId
  const label = resourceLabel(row.resourceType)
  return code ? `${label}「${code}」` : label
}

/** 无快照操作的说明，避免「文件下载文件」这类动作与对象重复 */
function plainActionText(row: AuditLogItem): string {
  const code = row.resourceCode ?? row.resourceId
  const file = code ? `文件「${code}」` : '文件'
  switch (row.action) {
    case 'LOGIN':
      return '登录系统'
    case 'EXPORT':
      return `导出${row.resourceType ? `${resourceLabel(row.resourceType)}数据` : '数据'}`
    case 'FILE_DOWNLOAD':
      return `下载${file}`
    case 'FILE_PREVIEW':
      return `预览${file}`
    default:
      return `${actionText(row)}${targetText(row)}`
  }
}

/** 生成展开详情：摘要 + 变更明细 / 创建删除内容，替代原始 JSON */
export function buildAuditDetail(row: AuditLogItem): AuditDetail {
  const before = parseAuditSnapshot(row.before)
  const after = parseAuditSnapshot(row.after)
  const verb = actionText(row)
  const target = targetText(row)

  if (Array.isArray(before) || Array.isArray(after)) {
    return arraySummary(
      Array.isArray(before) ? before : [],
      Array.isArray(after) ? after : [],
      row,
    )
  }

  const beforeObj = isPlainObject(before) ? before : null
  const afterObj = isPlainObject(after) ? after : null

  if (beforeObj && afterObj) {
    const changes = objectDiff(beforeObj, afterObj, row)
    const summary = changes.length > 0
      ? `${verb}${target}，共 ${changes.length} 项变更：${changes.slice(0, 6).map((entry) => entry.label).join('、')}${changes.length > 6 ? ' 等' : ''}`
      : `${verb}${target}，未发现字段变化`
    return { summary, changes, facts: [], note: '' }
  }

  if (afterObj && !beforeObj) {
    const facts = factsOf(afterObj, row)
    const summary = facts.length > 0
      ? `${verb}${target}，内容：${facts.slice(0, 5).map((fact) => `${fact.label}「${fact.value}」`).join('、')}${facts.length > 5 ? ' 等' : ''}`
      : `${verb}${target}`
    return { summary, changes: [], facts, note: '' }
  }

  if (beforeObj && !afterObj) {
    const facts = factsOf(beforeObj, row)
    const summary = `${verb}${target}${facts.length > 0 ? `，删除前：${facts.slice(0, 5).map((fact) => `${fact.label}「${fact.value}」`).join('、')}${facts.length > 5 ? ' 等' : ''}` : ''}`
    return { summary, changes: [], facts, note: '' }
  }

  return {
    summary: plainActionText(row),
    changes: [],
    facts: [],
    note: '该操作未记录字段快照。',
  }
}
