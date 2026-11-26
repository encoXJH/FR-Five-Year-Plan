import type {
  TrainingField,
  TrainingProjectData,
  TrainingProjectInput,
  TrainingCellValue,
} from '@/types/training'

export const TRAINING_FIELD_KEYS = Array.from(
  { length: 57 },
  (_, index) => `c${String(index + 1).padStart(2, '0')}`,
)

export const TRAINING_TOTAL_KEYS = ['c32', 'c44', 'c54'] as const

const TOTAL_RANGES = {
  c32: TRAINING_FIELD_KEYS.slice(17, 31),
  c44: TRAINING_FIELD_KEYS.slice(32, 43),
  c54: TRAINING_FIELD_KEYS.slice(44, 53),
} as const

export type TrainingValidationMode = 'draft' | 'submit'

export interface TrainingValidationIssue {
  rowNo: number
  fieldKey: string | null
  message: string
}

export interface TrainingFieldGroup {
  group: string
  groupLabel: string
  fields: TrainingField[]
}

export function groupTrainingFields(fields: TrainingField[]): TrainingFieldGroup[] {
  const groups = new Map<string, TrainingFieldGroup>()
  for (const field of [...fields].sort((a, b) => a.column - b.column)) {
    const existing = groups.get(field.group)
    if (existing) {
      existing.fields.push(field)
    } else {
      groups.set(field.group, {
        group: field.group,
        groupLabel: field.groupLabel || field.group || '其他信息',
        fields: [field],
      })
    }
  }
  return [...groups.values()]
}

export function isTrainingReadonlyField(key: string): boolean {
  return key === 'c57' || (TRAINING_TOTAL_KEYS as readonly string[]).includes(key)
}

export function emptyTrainingProjectData(): TrainingProjectData {
  return Object.fromEntries(TRAINING_FIELD_KEYS.map((key) => [key, null]))
}

export function normalizeTrainingData(data: TrainingProjectData = {}): TrainingProjectData {
  const normalized = emptyTrainingProjectData()
  for (const key of TRAINING_FIELD_KEYS) {
    normalized[key] = data[key] ?? null
  }
  return normalized
}

export function trainingNumber(value: TrainingCellValue | undefined): number | null {
  if (value === null || value === undefined) return null
  if (typeof value === 'string' && value.trim() === '') return null
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function rounded(value: number): number {
  return Number(value.toFixed(6))
}

function sumValues(values: TrainingCellValue[]): number | null {
  let hasNumber = false
  let sum = 0
  for (const value of values) {
    const parsed = trainingNumber(value)
    if (parsed === null) continue
    hasNumber = true
    sum += parsed
  }
  return hasNumber ? rounded(sum) : null
}

export function calculateTrainingFunding(data: TrainingProjectData): number | null {
  return sumValues([data.c10, data.c11])
}

export function calculateTrainingValues(data: TrainingProjectData): TrainingProjectData {
  const calculated = normalizeTrainingData(data)
  for (const totalKey of TRAINING_TOTAL_KEYS) {
    calculated[totalKey] = sumValues(TOTAL_RANGES[totalKey].map((key) => calculated[key]))
  }
  return calculated
}

export function isValidTrainingDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) return false
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const date = new Date(Date.UTC(year, month - 1, day))
  return date.getUTCFullYear() === year
    && date.getUTCMonth() === month - 1
    && date.getUTCDate() === day
}

function hasValue(value: TrainingCellValue | undefined): boolean {
  return value !== null && value !== undefined && !(typeof value === 'string' && value.trim() === '')
}

function numberText(value: number): string {
  return String(value)
}

export function validateTrainingProject(
  project: TrainingProjectInput,
  fields: TrainingField[],
  mode: TrainingValidationMode,
): TrainingValidationIssue[] {
  const issues: TrainingValidationIssue[] = []
  const data = calculateTrainingValues(project.data)
  const add = (fieldKey: string | null, message: string) => {
    issues.push({ rowNo: project.rowNo, fieldKey, message })
  }

  for (const field of fields) {
    const value = data[field.key]
    if (!hasValue(value)) {
      if (mode === 'submit' && field.required && field.key !== 'c57') {
        add(field.key, `请填写${field.label}`)
      }
      continue
    }
    if (field.type === 'DATE') {
      if (typeof value !== 'string' || !isValidTrainingDate(value)) {
        add(field.key, `${field.label}须为有效日期`)
      }
    }
    if (field.type === 'DECIMAL' || field.type === 'INTEGER') {
      const parsed = trainingNumber(value)
      if (parsed === null) {
        add(field.key, `${field.label}须为有效数字`)
      } else if (parsed < 0) {
        add(field.key, `${field.label}不能为负数`)
      } else if (field.type === 'INTEGER' && !Number.isInteger(parsed)) {
        add(field.key, `${field.label}须为整数`)
      }
    }
  }

  const startDate = data.c05
  const endDate = data.c06
  if (typeof startDate === 'string' && typeof endDate === 'string'
    && isValidTrainingDate(startDate) && isValidTrainingDate(endDate) && startDate > endDate) {
    add('c06', '项目结束日期不能早于开班日期')
  }

  const totalPeople = trainingNumber(data.c14)
  const audienceTotal = trainingNumber(data.c32)
  if (totalPeople !== null && audienceTotal !== null && totalPeople !== audienceTotal) {
    add('c14', `项目培训总人数须等于培训对象合计 ${numberText(audienceTotal)} 人`)
  }
  for (const [key, label] of [['c44', '重点行业'], ['c54', '区域战略']] as const) {
    const subtotal = trainingNumber(data[key])
    if (totalPeople !== null && subtotal !== null && subtotal > totalPeople) {
      add(key, `${label}人数合计不能超过项目培训总人数`)
    }
  }

  const contractAmount = trainingNumber(data.c09)
  const funding = calculateTrainingFunding(data)
  if (contractAmount !== null && funding !== null && funding > contractAmount) {
    add('c11', `到账经费 ${numberText(funding)} 万元不能超过合同金额`)
  }
  return issues
}

export function validateTrainingProjects(
  projects: TrainingProjectInput[],
  fields: TrainingField[],
  mode: TrainingValidationMode,
): TrainingValidationIssue[] {
  if (mode === 'submit' && projects.length === 0) {
    return [{ rowNo: 0, fieldKey: null, message: '请至少填写一个完整培训项目' }]
  }
  return projects.flatMap((project) => validateTrainingProject(project, fields, mode))
}

export function toTrainingProjectInputs(projects: TrainingProjectInput[]): TrainingProjectInput[] {
  return projects.map((project, index) => ({
    ...(project.id ? { id: project.id } : {}),
    rowNo: index + 1,
    data: calculateTrainingValues(project.data),
    fileIds: [...(project.fileIds ?? project.files?.map((file) => file.id) ?? [])],
  }))
}
