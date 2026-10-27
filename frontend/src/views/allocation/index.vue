<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import type { VNode } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CircleCheck, Warning } from '@element-plus/icons-vue'
import StatusTag from '@/components/StatusTag.vue'
import DistributePreviewDialog from './components/DistributePreviewDialog.vue'
import type { PreviewRow } from './components/DistributePreviewDialog.vue'
import { getPlan, listPlans } from '@/api/plan'
import { listIndicators } from '@/api/indicator'
import { getUserOptions } from '@/api/user'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { distributePlan, getAllocation, saveAllocation } from '@/api/allocation'
import { ApiError } from '@/utils/request'
import { newIdempotencyKey } from '@/utils/idempotency'
import { enumLabel } from '@/utils/enumLabels'
import { formatDiff, formatNumber } from '@/utils/format'
import { formatTargetValue, sumYearTargets } from '@/utils/indicator'
import {
  canAssignNewB,
  computeYearSummary,
  isSummableCaliber,
  isSummaryBalanced,
  percentToDisplay,
  percentToStore,
  rowBooleanPlanYear,
  rowHasTarget,
  rowsMissingOwner,
  rowTotalTarget,
} from '@/utils/allocation'
import type { MatrixRow, MatrixTargets } from '@/utils/allocation'
import type { Allocation, SaveAllocationRequest } from '@/types/allocation'
import type { PlanDetail } from '@/types/plan'
import type { IndicatorTreeNode, YearTarget } from '@/types/indicator'
import type { IndicatorLevel, IndicatorStatus, StatCaliber } from '@/types/enums'
import type { UserOption } from '@/types/user'

/** 待分解指标（仅 A+） */
interface AllocatableItem {
  id: string
  code: string
  name: string
  level: IndicatorLevel
  parentCode: string | null
  status: IndicatorStatus
  statCaliber: StatCaliber
  unitLabel: string | null
  totalTarget: number | null
  yearTargets: YearTarget[]
}

const route = useRoute()

const planId = computed(() => (route.params.planId as string) || (route.query.planId as string) || '')

const loading = ref(false)
const matrixLoading = ref(false)
const plan = ref<PlanDetail | null>(null)
const tree = ref<IndicatorTreeNode[]>([])
const userOptions = ref<UserOption[]>([])
const unitOptions = ref<OrgUnit[]>([])
const unitFallback = ref(false)

const keyword = ref('')
const selectedId = ref('')

const years = computed<number[]>(() => {
  if (!plan.value) return []
  const list: number[] = []
  for (let year = plan.value.startYear; year <= plan.value.endYear; year += 1) {
    list.push(year)
  }
  return list
})

const allocatable = computed<AllocatableItem[]>(() => {
  const list: AllocatableItem[] = []
  for (const node of tree.value) {
    if (node.level !== 'A' || !node.isQuantifiable) continue
    for (const child of node.children ?? []) {
      const item = pickAllocatable(child, node.code)
      if (item) list.push(item)
    }
  }
  return list
})

function pickAllocatable(node: IndicatorTreeNode, parentCode: string | null): AllocatableItem | null {
  if (node.level !== 'A_PLUS' || !node.statCaliber) return null
  return {
    id: node.id,
    code: node.code,
    name: node.name,
    level: node.level,
    parentCode,
    status: node.status,
    statCaliber: node.statCaliber,
    unitLabel: node.unitLabel,
    totalTarget: node.totalTarget,
    yearTargets: node.yearTargets ?? [],
  }
}

const filteredAllocatable = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return allocatable.value
  return allocatable.value.filter(
    (item) => item.code.toLowerCase().includes(kw) || item.name.toLowerCase().includes(kw),
  )
})

const current = computed<AllocatableItem | null>(
  () => allocatable.value.find((item) => item.id === selectedId.value) ?? null,
)

const summable = computed(() => (current.value ? isSummableCaliber(current.value.statCaliber) : true))
const isBoolean = computed(() => current.value?.statCaliber === 'BOOLEAN')
const isPercent = computed(
  () => current.value?.statCaliber === 'RATIO' || current.value?.statCaliber === 'DIRECT_PERCENT',
)

/** A+ 各年度目标 */
const indicatorTargets = computed<MatrixTargets>(() => {
  const map: MatrixTargets = {}
  for (const item of current.value?.yearTargets ?? []) {
    map[item.year] = typeof item.target === 'number' ? item.target : null
  }
  return map
})

// ---------- 矩阵编辑态 ----------
const rows = ref<MatrixRow[]>([])
const allocationVersion = ref(0)
const serverBalanced = ref<boolean | null>(null)
const dirty = ref(false)
const lastSavedAt = ref<string | null>(null)
const editedCells = ref<Set<string>>(new Set())
/** 最近一次加载 / 保存的服务端数据，重置用 */
const lastAllocation = ref<Allocation | null>(null)

const readOnly = computed(
  () => current.value?.status === 'DISTRIBUTED' || plan.value?.status === 'ARCHIVED',
)

function rowReadOnly(row: MatrixRow): boolean {
  return readOnly.value || row.isHistorical
}

const liveSummary = computed(() =>
  computeYearSummary(rows.value, years.value, indicatorTargets.value),
)
const balanced = computed(() =>
  summable.value ? isSummaryBalanced(liveSummary.value) : true,
)
const missingOwners = computed(() => rowsMissingOwner(rows.value))
const hasAnyTarget = computed(() =>
  rows.value.some((row) => !row.isHistorical && rowHasTarget(row.targets)),
)
const assignableUnitIds = computed(
  () => new Set(unitOptions.value.filter((unit) => canAssignNewB(unit)).map((unit) => unit.id)),
)
const ineligibleTargetRows = computed(() =>
  rows.value.filter(
    (row) =>
      !row.isHistorical && rowHasTarget(row.targets) && !assignableUnitIds.value.has(row.unitId),
  ),
)

const distributeBlockReasons = computed<string[]>(() => {
  const reasons: string[] = []
  if (!hasAnyTarget.value) reasons.push('尚未为任何执行单元分解目标')
  if (ineligibleTargetRows.value.length > 0) {
    reasons.push(
      `以下单元已停用或不允许承接新 B：${ineligibleTargetRows.value.map((row) => row.unitCode).join('、')}`,
    )
  }
  if (summable.value && !balanced.value) {
    const badYears = liveSummary.value
      .filter((item) => item.diff !== null && item.diff !== 0)
      .map((item) => `${item.year} 年（差值 ${formatDiff(item.diff)}）`)
    reasons.push(`合计与指标目标不一致：${badYears.join('、')}`)
  }
  if (missingOwners.value.length > 0) {
    reasons.push(`缺少学院负责人：${missingOwners.value.map((row) => row.unitCode).join('、')}`)
  }
  return reasons
})

const canDistribute = computed(
  () => !readOnly.value && distributeBlockReasons.value.length === 0,
)

/** 下发按钮最终可用性与提示（有未保存修改时须先存草稿） */
const distributeEnabled = computed(() => canDistribute.value && !dirty.value)
const distributeTooltip = computed(() =>
  dirty.value ? '有未保存的修改，请先存草稿' : distributeBlockReasons.value.join('；'),
)

const unbalancedYears = computed(() => {
  if (!summable.value) return new Set<number>()
  return new Set(
    liveSummary.value
      .filter((item) => item.diff !== null && item.diff !== 0)
      .map((item) => item.year),
  )
})

/** 差值 ≠ 0 的年度明细（顶部 alert 可点击定位用） */
const unbalancedItems = computed(() =>
  liveSummary.value.filter((item) => item.diff !== null && item.diff !== 0),
)

/** 左侧列表的平衡状态：选择时按需检查；全量状态仅在主动批量下发时扫描 */
const balanceMap = ref<Record<string, boolean>>({})
const balanceLoaded = ref(false)
const balanceScanning = ref(false)
const allocationCache = new Map<string, Allocation>()

async function loadBalanceStates(): Promise<void> {
  balanceScanning.value = true
  try {
    const pending = allocatable.value.filter((item) => item.status !== 'DISTRIBUTED')
    const results = await Promise.all(
      pending.map((item) =>
        getAllocation(item.id)
          .then((allocation) => {
            allocationCache.set(item.id, allocation)
            return [item.id, allocation.isBalanced] as const
          })
          .catch(() => null),
      ),
    )
    const map: Record<string, boolean> = { ...balanceMap.value }
    for (const entry of results) {
      if (entry) map[entry[0]] = entry[1]
    }
    balanceMap.value = map
    balanceLoaded.value = true
  } finally {
    balanceScanning.value = false
  }
}

function normalizeTarget(
  value: number | boolean | null,
  booleanCaliber: boolean,
): number | boolean | null {
  if (booleanCaliber) return value === true || value === 1 ? true : null
  return typeof value === 'number' ? value : null
}

function rebuildRows(allocation: Allocation): void {
  const booleanCaliber = current.value?.statCaliber === 'BOOLEAN'
  rows.value = (allocation.rows ?? []).map((row) => {
    const targets: MatrixTargets = {}
    for (const year of years.value) {
      const found = row.yearTargets?.find((item) => item.year === year)
      targets[year] = found ? normalizeTarget(found.target, booleanCaliber) : null
    }
    return {
      unitId: row.unit.id,
      unitCode: row.unit.code,
      unitName: row.unit.name,
      ownerId: row.owner?.id ?? null,
      isHistorical: row.isHistorical,
      targets,
    }
  })
  allocationVersion.value = allocation.version
  serverBalanced.value = allocation.isBalanced
  lastAllocation.value = allocation
  allocationCache.set(allocation.indicatorId, allocation)
  balanceMap.value = { ...balanceMap.value, [allocation.indicatorId]: allocation.isBalanced }
  dirty.value = false
  editedCells.value = new Set()
}

async function selectIndicator(id: string, force = false): Promise<void> {
  if (!force && dirty.value) {
    try {
      await ElMessageBox.confirm('当前矩阵有未保存的修改，切换后将丢失。确认切换？', '未保存的修改', {
        confirmButtonText: '放弃修改并切换',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  selectedId.value = id
  matrixLoading.value = true
  try {
    const cached = force ? null : allocationCache.get(id)
    const allocation = cached ?? await getAllocation(id)
    rebuildRows(allocation)
  } finally {
    matrixLoading.value = false
  }
}

function markDirty(row: MatrixRow, year: number): void {
  if (rowReadOnly(row)) return
  dirty.value = true
  const next = new Set(editedCells.value)
  next.add(`${row.unitId}-${year}`)
  editedCells.value = next
}

function setNumberCell(row: MatrixRow, year: number, value: number | undefined): void {
  if (rowReadOnly(row)) return
  row.targets[year] = value ?? null
  markDirty(row, year)
}

function setPercentCell(row: MatrixRow, year: number, value: number | undefined): void {
  if (rowReadOnly(row)) return
  row.targets[year] = percentToStore(value)
  markDirty(row, year)
}

function toggleBooleanCell(row: MatrixRow, year: number, checked: boolean): void {
  if (rowReadOnly(row)) return
  for (const y of years.value) {
    row.targets[y] = null
  }
  row.targets[year] = checked ? true : null
  markDirty(row, year)
}

function numberCellValue(row: MatrixRow, year: number): number | undefined {
  const value = row.targets[year]
  return typeof value === 'number' ? value : undefined
}

function copyFromPrevYear(row: MatrixRow): void {
  if (rowReadOnly(row)) return
  const list = years.value
  for (let i = list.length - 1; i > 0; i -= 1) {
    row.targets[list[i]] = row.targets[list[i - 1]]
  }
  markAllRowDirty(row)
}

function clearRow(row: MatrixRow): void {
  if (rowReadOnly(row)) return
  for (const year of years.value) {
    row.targets[year] = null
  }
  markAllRowDirty(row)
}

function markAllRowDirty(row: MatrixRow): void {
  if (rowReadOnly(row)) return
  dirty.value = true
  const next = new Set(editedCells.value)
  for (const year of years.value) {
    next.add(`${row.unitId}-${year}`)
  }
  editedCells.value = next
}

const addRowUnitId = ref('')

const addableUnits = computed(() => {
  const used = new Set(rows.value.map((row) => row.unitId))
  return unitOptions.value.filter((unit) => canAssignNewB(unit) && !used.has(unit.id))
})

function addRow(): void {
  if (readOnly.value) return
  const unit = addableUnits.value.find((item) => item.id === addRowUnitId.value)
  if (!unit || !canAssignNewB(unit)) return
  const targets: MatrixTargets = {}
  for (const year of years.value) {
    targets[year] = null
  }
  rows.value.push({
    unitId: unit.id,
    unitCode: unit.code,
    unitName: unit.name,
    ownerId: null,
    isHistorical: false,
    targets,
  })
  dirty.value = true
  addRowUnitId.value = ''
}

function removeRow(index: number): void {
  const row = rows.value[index]
  if (!row || rowReadOnly(row)) return
  rows.value.splice(index, 1)
  dirty.value = true
}

// ---------- 保存 / 重置 / 下发 ----------
const saving = ref(false)

function buildSaveRequest(): SaveAllocationRequest {
  return {
    version: allocationVersion.value,
    rows: rows.value.map((row) => ({
      unitId: row.unitId,
      ownerId: row.ownerId,
      yearTargets: years.value.map((year) => ({ year, target: row.targets[year] ?? null })),
    })),
  }
}

function applySavedAllocation(allocation: Allocation): void {
  rebuildRows(allocation)
  lastSavedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  ElMessage.success(
    allocation.isBalanced ? '已保存，各年度合计与指标目标一致' : '已保存草稿（合计与指标目标不一致）',
  )
}

/** 保存矩阵（silent：10005 并发冲突由本页自行处理，见 UI 规范 10.1-7） */
async function handleSave(): Promise<void> {
  if (!current.value || readOnly.value) return
  saving.value = true
  try {
    const allocation = await saveAllocation(current.value.id, buildSaveRequest(), true)
    applySavedAllocation(allocation)
  } catch (error) {
    if (error instanceof ApiError && error.code === 10005) {
      handleSaveConflict()
    } else if (error instanceof ApiError) {
      ElMessage.error(error.message || '保存失败')
    } else {
      ElMessage.error('网络异常，请稍后重试')
    }
  } finally {
    saving.value = false
  }
}

/** 10005：提供【查看最新】与【保留我的修改并覆盖】（重新拉取 version 后再提交） */
function handleSaveConflict(): void {
  ElMessageBox.confirm('该矩阵已被他人修改。可以查看最新数据，或保留我的修改并覆盖。', '保存冲突（10005）', {
    confirmButtonText: '保留我的修改并覆盖',
    cancelButtonText: '查看最新',
    type: 'warning',
    distinguishCancelAndClose: true,
  })
    .then(async () => {
      if (!current.value) return
      saving.value = true
      try {
        const latest = await getAllocation(current.value.id)
        allocationVersion.value = latest.version
        const allocation = await saveAllocation(current.value.id, buildSaveRequest(), true)
        applySavedAllocation(allocation)
      } catch (error) {
        if (error instanceof ApiError && error.code === 10005) {
          ElMessage.error('矩阵仍被他人修改，请稍后再试')
        } else if (error instanceof ApiError) {
          ElMessage.error(error.message || '覆盖保存失败')
        }
      } finally {
        saving.value = false
      }
    })
    .catch((action: string) => {
      if (action === 'cancel') {
        selectIndicator(selectedId.value, true)
      }
    })
}

function handleReset(): void {
  if (lastAllocation.value) {
    rebuildRows(lastAllocation.value)
  }
}

// ---------- 下发预览 ----------
const previewVisible = ref(false)
const distributing = ref(false)

/** 幂等 Key：同一次操作成功后重新生成，失败重试沿用旧 Key（UI 规范 10.4） */
const distributeKey = ref(newIdempotencyKey())
const batchDistributeKey = ref(newIdempotencyKey())

const previewRows = computed<PreviewRow[]>(() => {
  if (!current.value) return []
  const ownerNameOf = (id: string | null) =>
    id ? (userOptions.value.find((user) => user.id === id)?.name ?? null) : null
  return rows.value
    .filter(
      (row) =>
        !row.isHistorical &&
        assignableUnitIds.value.has(row.unitId) &&
        rowHasTarget(row.targets),
    )
    .map((row) => ({
      code: `${current.value!.code}-${row.unitCode}`,
      unitName: row.unitName,
      ownerName: ownerNameOf(row.ownerId),
      fiveYearTotal: rowTotalTarget(row.targets),
      booleanPlanYear: rowBooleanPlanYear(row.targets),
      targets: { ...row.targets },
    }))
})

function openPreview(): void {
  if (!canDistribute.value) return
  previewVisible.value = true
}

/** 下发失败的业务错误弹窗（50001 / 50002 / 50005） */
function showDistributeError(error: unknown): void {
  if (error instanceof ApiError && (error.code === 50001 || error.code === 50002)) {
    const lines = error.fieldErrors.map((item) => item.message)
    ElMessageBox.alert(
      lines.length > 0 ? lines.join('\n') : error.message,
      `${error.message}（${error.code}）`,
      { confirmButtonText: '返回修改', type: 'error' },
    )
  } else if (error instanceof ApiError && error.code === 50005) {
    ElMessageBox.alert('指标体系尚未定稿，请先在指标总表完成定稿（50005）', '不能下发', {
      confirmButtonText: '我知道了',
      type: 'error',
    })
  }
}

async function handleDistribute(): Promise<void> {
  if (!current.value || !plan.value || !canDistribute.value) return
  distributing.value = true
  try {
    await distributePlan(
      planId.value,
      { indicatorIds: [current.value.id], version: plan.value.version },
      distributeKey.value,
    )
    distributeKey.value = newIdempotencyKey()
    ElMessage.success(`「${current.value.code}」下发成功，已生成 B 任务`)
    previewVisible.value = false
    await loadTree()
    await selectIndicator(selectedId.value, true)
  } catch (error) {
    showDistributeError(error)
  } finally {
    distributing.value = false
  }
}

// ---------- 批量下发 ----------
const batchDistributing = ref(false)

/** 已平衡且未下发的指标（页头批量下发的候选清单） */
const balancedPending = computed(() =>
  allocatable.value.filter(
    (item) => item.status !== 'DISTRIBUTED' && balanceMap.value[item.id] === true,
  ),
)

async function handleBatchDistribute(): Promise<void> {
  if (!plan.value || plan.value.status === 'ARCHIVED') return
  if (!balanceLoaded.value) await loadBalanceStates()
  const ready = balancedPending.value
  if (ready.length === 0) {
    ElMessage.info('当前没有「已平衡且未下发」的指标')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将批量下发以下 ${ready.length} 个已平衡的指标：${ready
        .map((item) => item.code)
        .join('、')}。下发后生成 B 任务，任务编号与负责人不可修改。确认下发？`,
      '批量下发已平衡的指标',
      { confirmButtonText: '确认下发', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  batchDistributing.value = true
  try {
    await distributePlan(
      planId.value,
      { indicatorIds: ready.map((item) => item.id), version: plan.value.version },
      batchDistributeKey.value,
    )
    batchDistributeKey.value = newIdempotencyKey()
    ElMessage.success(`已批量下发 ${ready.length} 个指标`)
    await loadTree()
    if (selectedId.value) {
      await selectIndicator(selectedId.value, true)
    }
  } catch (error) {
    // 失败重试沿用旧 batchDistributeKey
    showDistributeError(error)
  } finally {
    batchDistributing.value = false
  }
}

// ---------- 表格展示 ----------
function cellClassName({ row, columnIndex }: { row: MatrixRow; columnIndex: number }): string {
  const yearIdx = columnIndex - 2
  if (yearIdx < 0 || yearIdx >= years.value.length) return ''
  const year = years.value[yearIdx]
  const classes: string[] = []
  if (unbalancedYears.value.has(year)) classes.push('cell-unbalanced')
  if (editedCells.value.has(`${row.unitId}-${year}`)) classes.push('cell-edited')
  return classes.join(' ')
}

function rowClassName({ row }: { row: MatrixRow }): string {
  return rowHasTarget(row.targets) && !row.ownerId ? 'row-missing-owner' : ''
}

function yearHeaderClass(year: number): string {
  const base = `year-col-${year}`
  return unbalancedYears.value.has(year) ? `${base} col-unbalanced` : base
}

/** 顶部 alert 点击年份：横向滚动到该年度列并短暂高亮（UI 规范 10.1-3） */
function locateYear(year: number): void {
  const el = document.querySelector(`.matrix-card .year-col-${year}`)
  if (!(el instanceof HTMLElement)) return
  el.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' })
  el.classList.add('col-highlight')
  window.setTimeout(() => el.classList.remove('col-highlight'), 1600)
}

interface SummaryColumn {
  property?: string
}

function summaryMethod({ columns }: { columns: SummaryColumn[] }): (string | VNode)[][] {
  const blank = ''
  const totalRow: (string | VNode)[] = []
  const targetRow: (string | VNode)[] = []
  const diffRow: (string | VNode)[] = []
  const caliber = current.value?.statCaliber ?? 'INCREMENT'
  const yearTotal = sumYearTargets(current.value?.yearTargets)
  const allocatedFiveYear = rows.value.reduce((sum, row) => sum + rowTotalTarget(row.targets), 0)
  columns.forEach((column, index) => {
    const yearIdx = index - 2
    if (index === 0) {
      totalRow.push('合计')
      targetRow.push(`${current.value?.level === 'A_PLUS' ? 'A+' : 'A'} 目标`)
      diffRow.push('差值')
      return
    }
    if (column.property === '__owner__' || column.property === '__actions__') {
      totalRow.push(blank)
      targetRow.push(blank)
      diffRow.push(blank)
      return
    }
    if (column.property === '__five__') {
      totalRow.push(formatNumber(allocatedFiveYear))
      targetRow.push(formatTargetValue(current.value?.totalTarget ?? yearTotal, caliber))
      const diff =
        current.value?.totalTarget !== null && current.value?.totalTarget !== undefined
          ? allocatedFiveYear - current.value.totalTarget
          : null
      diffRow.push(diffCell(diff))
      return
    }
    const year = years.value[yearIdx]
    const item = liveSummary.value.find((entry) => entry.year === year)
    totalRow.push(formatNumber(item?.allocatedTarget ?? null))
    targetRow.push(formatTargetValue(item?.indicatorTarget ?? null, caliber))
    diffRow.push(diffCell(item?.diff ?? null))
  })
  return [totalRow, targetRow, diffRow]
}

function diffCell(diff: number | null): VNode {
  return h(
    'span',
    { class: diff === null ? 'diff-na' : diff === 0 ? 'diff-ok' : 'diff-bad' },
    formatDiff(diff),
  )
}

async function loadTree(): Promise<void> {
  if (!planId.value) return
  tree.value = await listIndicators(planId.value, { view: 'tree' })
}

async function loadAll(): Promise<void> {
  if (!planId.value) return
  loading.value = true
  balanceMap.value = {}
  balanceLoaded.value = false
  allocationCache.clear()
  try {
    const [planDetail] = await Promise.all([getPlan(planId.value), loadTree()])
    plan.value = planDetail
    const queryId = route.query.indicatorId as string | undefined
    const first = allocatable.value.find((item) => item.id === queryId) ?? allocatable.value[0]
    if (first) {
      await selectIndicator(first.id, true)
    }
  } finally {
    loading.value = false
  }
}

const router = useRouter()

/** 无计划上下文（菜单直链 /allocation）时自动进入当前执行中的计划 */
async function redirectToActivePlan(): Promise<void> {
  try {
    const plans = await listPlans()
    const active = plans.find((p) => p.status === 'ACTIVE') ?? plans[0]
    if (active) await router.replace(`/plans/${active.id}/allocation`)
  } catch {
    // 拉取失败保持空状态引导
  }
}

// planId 驱动加载：组件可能被复用（直链跳转后 onMounted 不再触发），用 watch 保证有上下文即加载
watch(planId, (id) => {
  if (id) loadAll()
}, { immediate: true })

onMounted(() => {
  if (!planId.value) {
    redirectToActivePlan()
    return
  }
  getUserOptions()
    .then((list) => {
      userOptions.value = list
    })
    .catch(() => undefined)
  listOrgUnits()
    .then((list) => {
      unitOptions.value = list
    })
    .catch(() => {
      unitOptions.value = []
      unitFallback.value = true
    })
})
</script>

<template>
  <el-empty v-if="!planId" description="请先从首页选择一个计划">
    <el-button type="primary" @click="$router.push('/plans')">返回计划列表</el-button>
  </el-empty>

  <div v-else v-loading="loading" class="allocation-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">分解下发</h1>
        <p class="page-subtitle">{{ plan?.name ?? '' }}</p>
      </div>
      <div class="header-actions">
        <el-button
          :loading="batchDistributing || balanceScanning"
          :disabled="plan?.status === 'ARCHIVED'"
          @click="handleBatchDistribute"
        >
          批量下发已平衡的指标
        </el-button>
        <el-tooltip :disabled="distributeEnabled" :content="distributeTooltip" placement="top">
          <span>
            <el-button type="primary" :disabled="!distributeEnabled" @click="openPreview">
              下发预览
            </el-button>
          </span>
        </el-tooltip>
      </div>
    </div>

    <el-alert
      v-if="unitFallback"
      type="warning"
      :closable="false"
      class="page-alert"
      title="执行单元列表接口暂不可用，新增行功能已停用（矩阵行以下发数据为准）"
    />

    <div class="allocation-body">
      <el-card shadow="never" class="indicator-panel">
        <el-input v-model="keyword" clearable placeholder="搜索编号 / 名称" class="panel-search" />
        <el-scrollbar class="panel-list">
          <div
            v-for="item in filteredAllocatable"
            :key="item.id"
            class="indicator-item"
            :class="{ active: item.id === selectedId }"
            @click="selectIndicator(item.id)"
          >
            <div class="item-title">
              <span class="mono">{{ item.code }}</span>
              <el-tooltip v-if="item.status === 'DISTRIBUTED'" content="已下发" placement="top">
                <el-icon class="item-icon ok"><CircleCheck /></el-icon>
              </el-tooltip>
              <el-tooltip
                v-else-if="balanceMap[item.id] === true"
                content="已平衡，可下发"
                placement="top"
              >
                <el-icon class="item-icon balanced"><CircleCheck /></el-icon>
              </el-tooltip>
              <el-tooltip v-else-if="balanceMap[item.id] === false" content="未平衡" placement="top">
                <el-icon class="item-icon"><Warning /></el-icon>
              </el-tooltip>
              <el-tooltip v-else content="选择后检查平衡状态" placement="top">
                <el-icon class="item-icon unknown"><Warning /></el-icon>
              </el-tooltip>
            </div>
            <div class="item-name" :title="item.name">{{ item.name }}</div>
            <StatusTag kind="IndicatorStatus" :value="item.status" class="item-tag" />
          </div>
          <el-empty
            v-if="filteredAllocatable.length === 0"
            :image-size="60"
            description="没有可分解的指标"
          />
        </el-scrollbar>
      </el-card>

      <div v-if="current" v-loading="matrixLoading" class="matrix-panel">
        <el-card shadow="never" class="info-card">
          <div class="indicator-info">
            <StatusTag kind="IndicatorLevel" :value="current.level" />
            <span class="mono info-code">{{ current.code }}</span>
            <span class="info-name">{{ current.name }}</span>
            <span class="info-meta">
              {{ enumLabel('StatCaliber', current.statCaliber, undefined) }} ·
              {{ current.unitLabel ?? '—' }} · 五年
              {{ formatTargetValue(current.totalTarget ?? sumYearTargets(current.yearTargets), current.statCaliber) }}
            </span>
            <StatusTag kind="IndicatorStatus" :value="current.status" />
          </div>
        </el-card>

        <el-alert
          v-if="readOnly && current.status === 'DISTRIBUTED'"
          type="info"
          :closable="false"
          class="page-alert"
          title="该指标已下发，矩阵只读；如需调整请发起延期 / 转移申请"
        />
        <el-alert
          v-else-if="!summable"
          type="info"
          :closable="false"
          class="page-alert"
          :title="isBoolean
            ? '是否型指标不做目标求和校验，每行勾选计划完成年份'
            : '占比 / 直填百分比型指标不做目标求和校验，完成值按 Σ分子 ÷ Σ分母 汇总'"
        />
        <template v-else>
          <el-alert
            v-if="canDistribute"
            type="success"
            :closable="false"
            class="page-alert"
            title="各年度合计与指标目标一致，可以下发"
          />
          <el-alert
            v-else
            type="error"
            :closable="false"
            class="page-alert"
          >
            <template #title>
              <span v-if="!hasAnyTarget">尚未为任何执行单元分解目标；</span>
              <span v-if="ineligibleTargetRows.length > 0">
                以下单元已停用或不允许承接新 B：{{ ineligibleTargetRows.map((row) => row.unitCode).join('、') }}；
              </span>
              <template v-if="unbalancedItems.length > 0">
                合计与指标目标不一致（点击年份定位）：
                <template v-for="(item, index) in unbalancedItems" :key="item.year">
                  <a class="year-link" @click="locateYear(item.year)">
                    {{ item.year }} 年（差值 {{ formatDiff(item.diff) }}）
                  </a>
                  <span>{{ index < unbalancedItems.length - 1 ? '、' : '；' }}</span>
                </template>
              </template>
              <span v-if="missingOwners.length > 0">
                缺少学院负责人：{{ missingOwners.map((row) => row.unitCode).join('、') }}
              </span>
            </template>
          </el-alert>
        </template>

        <el-card shadow="never" class="matrix-card">
          <el-table
            :data="rows"
            border
            size="small"
            :show-summary="summable"
            :summary-method="summaryMethod"
            :cell-class-name="cellClassName"
            :row-class-name="rowClassName"
          >
            <el-table-column label="执行单元" min-width="180" fixed="left">
              <template #default="{ row }">
                <span class="mono">{{ row.unitCode }}</span>
                {{ row.unitName }}
                <el-tag v-if="row.isHistorical" type="info" size="small" effect="plain">历史只读</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="学院负责人" width="150" prop="__owner__">
              <template #default="{ row }">
                <el-select
                  v-if="!rowReadOnly(row)"
                  v-model="row.ownerId"
                  filterable
                  clearable
                  placeholder="选择负责人"
                  :class="{ 'owner-missing': rowHasTarget(row.targets) && !row.ownerId }"
                  @change="markAllRowDirty(row)"
                >
                  <el-option
                    v-for="user in userOptions"
                    :key="user.id"
                    :label="`${user.name}（${user.username}）`"
                    :value="user.id"
                  />
                </el-select>
                <span v-else>
                  {{ userOptions.find((user) => user.id === row.ownerId)?.name ?? '—' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column
              v-for="year in years"
              :key="year"
              :label="String(year)"
              :label-class-name="yearHeaderClass(year)"
              width="110"
              align="right"
            >
              <template #header>
                <span>{{ year }}<template v-if="unbalancedYears.has(year)"> ⚠</template></span>
              </template>
              <template #default="{ row }">
                <template v-if="rowReadOnly(row)">
                  {{ formatTargetValue(row.targets[year] ?? null, current.statCaliber) }}
                </template>
                <el-checkbox
                  v-else-if="isBoolean"
                  :model-value="row.targets[year] === true"
                  @update:model-value="(checked: string | number | boolean) => toggleBooleanCell(row, year, checked === true)"
                />
                <el-input-number
                  v-else-if="isPercent"
                  :model-value="percentToDisplay(row.targets[year] as number | null)"
                  :precision="1"
                  :min="0"
                  :max="999.9"
                  controls-position="right"
                  class="cell-input"
                  @update:model-value="(value: number | undefined) => setPercentCell(row, year, value)"
                />
                <el-input-number
                  v-else
                  :model-value="numberCellValue(row, year)"
                  :precision="current.statCaliber === 'INCREMENT' ? 0 : undefined"
                  :min="0"
                  controls-position="right"
                  class="cell-input"
                  @update:model-value="(value: number | undefined) => setNumberCell(row, year, value)"
                />
              </template>
            </el-table-column>
            <el-table-column label="五年合计" width="100" align="right" prop="__five__">
              <template #default="{ row }">
                <template v-if="isBoolean">
                  {{
                    rowBooleanPlanYear(row.targets) === null
                      ? '—'
                      : `是（${rowBooleanPlanYear(row.targets)} 年）`
                  }}
                </template>
                <template v-else>
                  {{ formatTargetValue(rowTotalTarget(row.targets), current.statCaliber) }}
                </template>
              </template>
            </el-table-column>
            <el-table-column v-if="!readOnly" label="操作" width="120" fixed="right" align="center" prop="__actions__">
              <template #default="{ row, $index }">
                <template v-if="!rowReadOnly(row)">
                  <el-button link type="primary" @click="copyFromPrevYear(row)">顺沿上年</el-button>
                  <el-dropdown trigger="click">
                    <el-button link type="primary">更多</el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item @click="clearRow(row)">清空本行</el-dropdown-item>
                        <el-dropdown-item class="danger-item" @click="removeRow($index)">
                          <span class="danger-text">删除行</span>
                        </el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </template>
                <span v-else class="empty-text">历史只读</span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty :image-size="80" description="暂无分解行，请在下方新增执行单元" />
            </template>
          </el-table>

          <div v-if="!readOnly" class="add-row-bar">
            <el-select
              v-model="addRowUnitId"
              filterable
              placeholder="选择执行单元新增一行"
              style="width: 240px"
              :disabled="addableUnits.length === 0"
            >
              <el-option
                v-for="unit in addableUnits"
                :key="unit.id"
                :label="`${unit.code} ${unit.name}`"
                :value="unit.id"
              />
            </el-select>
            <el-button :disabled="!addRowUnitId" @click="addRow">添加行</el-button>
          </div>
        </el-card>

        <div v-if="!readOnly" class="sticky-bar">
          <span class="bar-status">
            <template v-if="dirty">已修改未保存</template>
            <template v-else-if="lastSavedAt">上次保存 {{ lastSavedAt }}</template>
            <template v-else-if="serverBalanced !== null">
              {{ serverBalanced ? '已保存（平衡）' : '已保存（未平衡）' }}
            </template>
          </span>
          <div class="bar-actions">
            <el-button :disabled="!dirty" @click="handleReset">重置</el-button>
            <el-button :loading="saving" :disabled="!dirty" @click="handleSave">存草稿</el-button>
            <el-tooltip :disabled="distributeEnabled" :content="distributeTooltip" placement="top">
              <span>
                <el-button type="primary" :disabled="!distributeEnabled" @click="openPreview">
                  下发
                </el-button>
              </span>
            </el-tooltip>
          </div>
        </div>
      </div>

      <el-card v-else shadow="never" class="matrix-panel">
        <el-empty :image-size="100" description="请选择左侧待分解的指标" />
      </el-card>
    </div>

    <DistributePreviewDialog
      v-if="current"
      v-model="previewVisible"
      :indicator-code="current.code"
      :indicator-name="current.name"
      :caliber="current.statCaliber"
      :unit-label="current.unitLabel"
      :years="years"
      :rows="previewRows"
      :confirming="distributing"
      @confirm="handleDistribute"
    />
  </div>
</template>

<style scoped>
.page-alert {
  margin-bottom: var(--space-4);
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
}

.page-subtitle {
  margin: var(--space-1) 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.allocation-body {
  display: flex;
  gap: var(--space-4);
  align-items: flex-start;
}

.indicator-panel {
  width: 260px;
  flex-shrink: 0;
  border-radius: var(--radius-card);
}

.panel-search {
  margin-bottom: var(--space-3);
}

.panel-list {
  max-height: 600px;
}

.indicator-item {
  padding: var(--space-2) var(--space-3);
  border-radius: var(--el-border-radius-base);
  cursor: pointer;
  position: relative;
}

.indicator-item:hover {
  background-color: var(--el-color-primary-light-9);
}

.indicator-item.active {
  background-color: var(--el-color-primary-light-9);
  box-shadow: inset 3px 0 0 var(--el-color-primary);
}

.item-title {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-weight: 600;
}

.item-icon {
  color: var(--el-color-warning);
}

.item-icon.unknown {
  color: var(--el-text-color-placeholder);
}

.item-icon.ok {
  color: var(--el-color-success);
}

.item-icon.balanced {
  color: var(--el-color-primary);
}

.item-name {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin: 2px 0;
}

.matrix-panel {
  flex: 1;
  min-width: 0;
}

.info-card {
  margin-bottom: var(--space-4);
  border-radius: var(--radius-card);
}

.indicator-info {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.info-code {
  font-weight: 600;
}

.info-name {
  font-weight: 600;
}

.info-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.matrix-card {
  border-radius: var(--radius-card);
}

.mono {
  font-family: var(--app-font-mono);
}

.cell-input {
  width: 100%;
}

.owner-missing :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}

.add-row-bar {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.sticky-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 var(--space-5);
  margin-top: var(--space-4);
  background: var(--el-bg-color);
  border-radius: var(--radius-card);
  box-shadow: var(--el-box-shadow-lighter);
}

.bar-status {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.bar-actions {
  display: flex;
  gap: var(--space-2);
}

.danger-text {
  color: var(--el-color-danger);
}

:deep(.cell-unbalanced) {
  background-color: #fef0f0 !important;
}

/* 已修改未保存的单元格：左上角 6px 主色三角（UI 规范 10.1-2） */
:deep(.cell-edited) {
  position: relative;
}

:deep(.cell-edited)::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 0;
  height: 0;
  border-style: solid;
  border-width: 6px 6px 0 0;
  border-color: var(--el-color-primary) transparent transparent transparent;
  pointer-events: none;
  z-index: 1;
}

:deep(.col-unbalanced) {
  color: var(--el-color-danger);
}

/* alert 点击年份定位后的短暂高亮 */
:deep(.col-highlight) {
  background-color: var(--el-color-primary-light-8) !important;
  transition: background-color 0.3s;
}

.year-link {
  color: inherit;
  font-weight: 600;
  text-decoration: underline;
  cursor: pointer;
}

:deep(.row-missing-owner) {
  box-shadow: inset 3px 0 0 var(--el-color-warning);
}

:deep(.el-table__footer-wrapper td) {
  background-color: var(--el-fill-color-light);
  font-weight: 600;
}

:deep(.diff-ok) {
  color: var(--el-color-success);
}

:deep(.diff-bad) {
  color: var(--el-color-danger);
  font-weight: 600;
}

:deep(.diff-na) {
  color: var(--el-text-color-placeholder);
}
</style>
