<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import type { VNode } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ResponsiveRecordView from '@/components/ResponsiveRecordView.vue'
import { getAllocation, saveAllocation } from '@/api/allocation'
import type { OrgUnit } from '@/api/orgUnit'
import { ApiError } from '@/utils/request'
import { formatDiff, formatNumber } from '@/utils/format'
import { formatTargetValue, sumYearTargets } from '@/utils/indicator'
import {
  allocationToMatrixRows,
  buildSaveAllocationRequest,
  canAssignNewB,
  computeYearSummary,
  distributeYearTargetsEvenly,
  emptyMatrixTargets,
  isSummableCaliber,
  isSummaryBalanced,
  isPendingAllocationRow,
  percentToDisplay,
  percentToStore,
  rowBooleanPlanYear,
  rowHasTarget,
  rowsMissingOwner,
  rowTotalTarget,
} from '@/utils/allocation'
import type {
  AllocationEditorState,
  MatrixRow,
  MatrixTargets,
} from '@/utils/allocation'
import type { Allocation } from '@/types/allocation'
import type { YearTarget } from '@/types/indicator'
import type { StatCaliber } from '@/types/enums'
import type { UserOption } from '@/types/user'

const props = withDefaults(defineProps<{
  years: number[]
  statCaliber: StatCaliber
  yearTargets: YearTarget[]
  totalTarget: number | null
  userOptions: UserOption[]
  unitOptions: OrgUnit[]
  legacy?: boolean
  distributed?: boolean
  archived?: boolean
  readOnly?: boolean
}>(), {
  legacy: false,
  distributed: false,
  archived: false,
  readOnly: false,
})

const emit = defineEmits<{
  'state-change': [state: AllocationEditorState]
  loaded: [allocation: Allocation]
  saved: [allocation: Allocation]
}>()

const root = ref<HTMLElement>()
const rows = ref<MatrixRow[]>([])
const activeIndicatorId = ref('')
const allocationVersion = ref(0)
const serverBalanced = ref<boolean | null>(null)
const dirty = ref(false)
const loading = ref(false)
const saving = ref(false)
const lastSavedAt = ref<string | null>(null)
const editedCells = ref<Set<string>>(new Set())
const lastAllocation = ref<Allocation | null>(null)
const addRowUnitId = ref('')
let loadRequestId = 0

const readOnly = computed(() =>
  props.readOnly || props.legacy || props.archived,
)
const summable = computed(() => isSummableCaliber(props.statCaliber))
const isBoolean = computed(() => props.statCaliber === 'BOOLEAN')
const isPercent = computed(() =>
  props.statCaliber === 'RATIO' || props.statCaliber === 'DIRECT_PERCENT',
)
const indicatorTargets = computed<MatrixTargets>(() => {
  const map: MatrixTargets = {}
  for (const item of props.yearTargets) {
    map[item.year] = typeof item.target === 'number' ? item.target : null
  }
  return map
})
const liveSummary = computed(() =>
  computeYearSummary(rows.value, props.years, indicatorTargets.value),
)
const balanced = computed(() => summable.value ? isSummaryBalanced(liveSummary.value) : true)
const allocatedFiveYear = computed(() =>
  rows.value.reduce((sum, row) => sum + rowTotalTarget(row.targets), 0),
)
const fiveYearDiff = computed(() =>
  props.totalTarget !== null && props.totalTarget !== undefined
    ? allocatedFiveYear.value - props.totalTarget
    : null,
)
const pendingRows = computed(() => rows.value.filter(isPendingAllocationRow))
const missingOwners = computed(() => rowsMissingOwner(pendingRows.value))
const hasAnyTarget = computed(() =>
  pendingRows.value.some((row) => rowHasTarget(row.targets)),
)
const assignableUnitIds = computed(() =>
  new Set(props.unitOptions.filter((unit) => canAssignNewB(unit)).map((unit) => unit.id)),
)
const ineligibleTargetRows = computed(() =>
  pendingRows.value.filter((row) =>
    rowHasTarget(row.targets) && !assignableUnitIds.value.has(row.unitId),
  ),
)
const distributeBlockReasons = computed<string[]>(() => {
  const reasons: string[] = []
  if (!hasAnyTarget.value) {
    reasons.push(props.distributed ? '暂无待下发的新学院' : '尚未为任何执行单元分解目标')
  }
  if (ineligibleTargetRows.value.length > 0) {
    reasons.push(
      `以下单元已停用或不允许承接新 B：${ineligibleTargetRows.value.map((row) => row.unitCode).join('、')}`,
    )
  }
  if (summable.value && !balanced.value) {
    const badYears = liveSummary.value
      .filter((item) => item.diff !== null && item.diff < 0)
      .map((item) => `${item.year} 年（差值 ${formatDiff(item.diff)}）`)
    reasons.push(`合计低于指标目标：${badYears.join('、')}`)
  }
  if (missingOwners.value.length > 0) {
    reasons.push(`缺少学院负责人：${missingOwners.value.map((row) => row.unitCode).join('、')}`)
  }
  return reasons
})
const canDistribute = computed(() =>
  !readOnly.value && !loading.value && distributeBlockReasons.value.length === 0,
)
const unbalancedItems = computed(() =>
  liveSummary.value.filter((item) => item.diff !== null && item.diff < 0),
)
const addableUnits = computed(() => {
  const used = new Set(rows.value.map((row) => row.unitId))
  return props.unitOptions.filter((unit) => canAssignNewB(unit) && !used.has(unit.id))
})

function stateSnapshot(): AllocationEditorState {
  return {
    indicatorId: activeIndicatorId.value,
    rows: rows.value,
    dirty: dirty.value,
    balanced: balanced.value,
    version: allocationVersion.value,
    serverBalanced: serverBalanced.value,
    loading: loading.value,
    saving: saving.value,
    canDistribute: canDistribute.value,
    distributeBlockReasons: distributeBlockReasons.value,
    hasAnyTarget: hasAnyTarget.value,
    missingOwners: missingOwners.value,
    ineligibleTargetRows: ineligibleTargetRows.value,
    summary: liveSummary.value,
    allocatedFiveYear: allocatedFiveYear.value,
    fiveYearDiff: fiveYearDiff.value,
    lastSavedAt: lastSavedAt.value,
  }
}

watch(
  [
    activeIndicatorId,
    dirty,
    balanced,
    allocationVersion,
    serverBalanced,
    loading,
    saving,
    canDistribute,
    distributeBlockReasons,
    liveSummary,
    allocatedFiveYear,
    fiveYearDiff,
    lastSavedAt,
  ],
  () => emit('state-change', stateSnapshot()),
  { immediate: true },
)

function rowReadOnly(row: MatrixRow): boolean {
  return readOnly.value || !isPendingAllocationRow(row)
}

function rebuildRows(allocation: Allocation): void {
  rows.value = allocationToMatrixRows(allocation, props.years, props.statCaliber)
  activeIndicatorId.value = allocation.indicatorId
  allocationVersion.value = allocation.version
  serverBalanced.value = allocation.isBalanced
  lastAllocation.value = allocation
  dirty.value = false
  editedCells.value = new Set()
}

function clear(): void {
  loadRequestId += 1
  activeIndicatorId.value = ''
  rows.value = []
  allocationVersion.value = 0
  serverBalanced.value = null
  lastAllocation.value = null
  lastSavedAt.value = null
  editedCells.value = new Set()
  addRowUnitId.value = ''
  dirty.value = false
  loading.value = false
}

async function load(indicatorId: string, cached?: Allocation): Promise<Allocation | null> {
  const requestId = ++loadRequestId
  activeIndicatorId.value = indicatorId
  rows.value = []
  allocationVersion.value = 0
  serverBalanced.value = null
  lastAllocation.value = null
  lastSavedAt.value = null
  editedCells.value = new Set()
  dirty.value = false
  loading.value = true
  try {
    const allocation = cached ?? await getAllocation(indicatorId)
    if (requestId !== loadRequestId) return null
    rebuildRows(allocation)
    emit('loaded', allocation)
    return allocation
  } finally {
    if (requestId === loadRequestId) loading.value = false
  }
}

function markDirty(row: MatrixRow, year: number): void {
  if (rowReadOnly(row)) return
  dirty.value = true
  const next = new Set(editedCells.value)
  next.add(`${row.unitId}-${year}`)
  editedCells.value = next
}

function markAllRowDirty(row: MatrixRow): void {
  if (rowReadOnly(row)) return
  dirty.value = true
  const next = new Set(editedCells.value)
  for (const year of props.years) next.add(`${row.unitId}-${year}`)
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
  for (const y of props.years) row.targets[y] = null
  row.targets[year] = checked ? true : null
  markDirty(row, year)
}

function numberCellValue(row: MatrixRow, year: number): number | undefined {
  const value = row.targets[year]
  return typeof value === 'number' ? value : undefined
}

function copyFromPrevYear(row: MatrixRow): void {
  if (rowReadOnly(row)) return
  for (let i = props.years.length - 1; i > 0; i -= 1) {
    row.targets[props.years[i]] = row.targets[props.years[i - 1]]
  }
  markAllRowDirty(row)
}

function clearRow(row: MatrixRow): void {
  if (rowReadOnly(row)) return
  for (const year of props.years) row.targets[year] = null
  markAllRowDirty(row)
}

function addRow(): void {
  if (readOnly.value) return
  const unit = addableUnits.value.find((item) => item.id === addRowUnitId.value)
  if (!unit || !canAssignNewB(unit)) return
  rows.value.push({
    unitId: unit.id,
    unitCode: unit.code,
    unitName: unit.name,
    ownerId: null,
    isHistorical: false,
    taskStatus: null,
    targets: emptyMatrixTargets(props.years),
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

async function confirmAutoDistribute(message: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, '自动分配', {
      confirmButtonText: '继续',
      cancelButtonText: '取消',
      type: 'info',
    })
    return true
  } catch {
    return false
  }
}

/** 自动分配：把指标年度目标平均写入可编辑行，最后一行补差；没有可编辑行时先补充可承接单元。 */
async function autoDistribute(): Promise<void> {
  if (readOnly.value || !summable.value) return
  if (!props.years.some((year) => typeof indicatorTargets.value[year] === 'number')) {
    ElMessage.warning('请先设置该指标的年度目标')
    return
  }
  // 只读行（历史 / 已下发）已占用的额度先扣除，没有剩余额度时不新增空行
  const hasRemaining = props.years.some((year) => {
    const target = indicatorTargets.value[year]
    if (typeof target !== 'number') return false
    const fixedTotal = rows.value
      .filter((row) => !isPendingAllocationRow(row))
      .reduce((sum, row) => sum + (typeof row.targets[year] === 'number' ? row.targets[year] as number : 0), 0)
    return target - fixedTotal > 0
  })
  if (!hasRemaining) {
    ElMessage.warning('只读行已完成全部年度目标，无需自动分配')
    return
  }

  let editableRows = rows.value.filter((row) => !rowReadOnly(row))
  if (editableRows.length === 0) {
    const unitsToAdd = [...addableUnits.value]
    if (unitsToAdd.length === 0) {
      ElMessage.warning('暂无可承接新 B 的执行单元')
      return
    }
    const confirmed = await confirmAutoDistribute(
      `当前没有可编辑的分解行，将为 ${unitsToAdd.length} 个可承接执行单元新增分解行并平均分配，是否继续？`,
    )
    if (!confirmed) return
    for (const unit of unitsToAdd) {
      rows.value.push({
        unitId: unit.id,
        unitCode: unit.code,
        unitName: unit.name,
        ownerId: null,
        isHistorical: false,
        taskStatus: null,
        targets: emptyMatrixTargets(props.years),
      })
    }
    editableRows = rows.value.filter((row) => !rowReadOnly(row))
  } else if (editableRows.some((row) => rowHasTarget(row.targets))) {
    const confirmed = await confirmAutoDistribute('自动分配将覆盖可编辑行已填写的年度目标，是否继续？')
    if (!confirmed) return
  }

  const scale = props.statCaliber === 'INCREMENT' ? 1 : 100
  const result = distributeYearTargetsEvenly(rows.value, props.years, indicatorTargets.value, scale)
  if (result.filledYears.length === 0) {
    ElMessage.warning('没有可分配的年度（目标为空或只读行已满足目标）')
    return
  }
  dirty.value = true
  const next = new Set(editedCells.value)
  for (const row of editableRows) {
    for (const year of result.filledYears) next.add(`${row.unitId}-${year}`)
  }
  editedCells.value = next
  ElMessage.success(`已为 ${editableRows.length} 个执行单元平均分配 ${result.filledYears.length} 个年度目标`)
}

interface SaveOptions {
  indicatorId?: string
  version?: number
  notify?: boolean
  showErrors?: boolean
}

function applySavedAllocation(allocation: Allocation, notify: boolean): void {
  rebuildRows(allocation)
  lastSavedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  if (notify) {
    ElMessage.success(
      allocation.isBalanced ? '已保存，各年度合计不低于指标目标' : '已保存草稿（部分年度合计低于指标目标）',
    )
  }
  emit('saved', allocation)
}

function showSaveError(error: unknown): void {
  if (error instanceof ApiError) ElMessage.error(error.message || '保存失败')
  else ElMessage.error('网络异常，请稍后重试')
}

async function handleSaveConflict(
  indicatorId: string,
  options: SaveOptions,
): Promise<Allocation | null> {
  try {
    await ElMessageBox.confirm(
      '该矩阵已被他人修改。可以查看最新数据，或保留我的修改并覆盖。',
      '保存冲突（10005）',
      {
        confirmButtonText: '保留我的修改并覆盖',
        cancelButtonText: '查看最新',
        type: 'warning',
        distinguishCancelAndClose: true,
      },
    )
  } catch (action) {
    if (action === 'cancel') await load(indicatorId)
    return null
  }
  try {
    const latest = await getAllocation(indicatorId)
    allocationVersion.value = latest.version
    const allocation = await saveAllocation(
      indicatorId,
      buildSaveAllocationRequest(rows.value, props.years, latest.version),
      true,
    )
    applySavedAllocation(allocation, options.notify !== false)
    return allocation
  } catch (error) {
    if (options.showErrors !== false) {
      if (error instanceof ApiError && error.code === 10005) {
        ElMessage.error('矩阵仍被他人修改，请稍后再试')
      } else {
        showSaveError(error)
      }
    }
    return null
  }
}

/** 供父级编排保存；返回 null 表示失败或用户选择先查看最新，调用方不得关闭表单。 */
async function saveDraft(options: SaveOptions = {}): Promise<Allocation | null> {
  const indicatorId = options.indicatorId ?? activeIndicatorId.value
  if (!indicatorId || readOnly.value || saving.value) return null
  activeIndicatorId.value = indicatorId
  saving.value = true
  try {
    const version = options.version ?? allocationVersion.value
    const allocation = await saveAllocation(
      indicatorId,
      buildSaveAllocationRequest(rows.value, props.years, version),
      true,
    )
    applySavedAllocation(allocation, options.notify !== false)
    return allocation
  } catch (error) {
    if (error instanceof ApiError && error.code === 10005) {
      return await handleSaveConflict(indicatorId, options)
    }
    if (options.showErrors !== false) showSaveError(error)
    return null
  } finally {
    saving.value = false
  }
}

function handleReset(): void {
  if (lastAllocation.value) rebuildRows(lastAllocation.value)
  else clear()
}

function cellClassName({ row, columnIndex }: { row: MatrixRow; columnIndex: number }): string {
  const yearIdx = columnIndex - 2
  if (yearIdx < 0 || yearIdx >= props.years.length) return ''
  const year = props.years[yearIdx]
  return editedCells.value.has(`${row.unitId}-${year}`) ? 'cell-edited' : ''
}

function rowClassName({ row }: { row: MatrixRow }): string {
  return rowHasTarget(row.targets) && !row.ownerId ? 'row-missing-owner' : ''
}

function yearHeaderClass(year: number): string {
  return `year-col-${year}`
}

function locateYear(year: number): void {
  const selector = window.innerWidth <= 768
    ? `.mobile-year-col-${year}`
    : `.year-col-${year}`
  const el = root.value?.querySelector(selector)
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
  const yearTotal = sumYearTargets(props.yearTargets)
  columns.forEach((column, index) => {
    const yearIdx = index - 2
    if (index === 0) {
      totalRow.push('合计')
      targetRow.push('A 目标')
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
      totalRow.push(formatNumber(allocatedFiveYear.value))
      targetRow.push(formatTargetValue(props.totalTarget ?? yearTotal, props.statCaliber))
      diffRow.push(diffCell(fiveYearDiff.value))
      return
    }
    const year = props.years[yearIdx]
    const item = liveSummary.value.find((entry) => entry.year === year)
    totalRow.push(formatNumber(item?.allocatedTarget ?? null))
    targetRow.push(formatTargetValue(item?.indicatorTarget ?? null, props.statCaliber))
    diffRow.push(diffCell(item?.diff ?? null))
  })
  return [totalRow, targetRow, diffRow]
}

function diffCell(diff: number | null): VNode {
  return h(
    'span',
    { class: diff === null ? 'diff-na' : diff >= 0 ? 'diff-ok' : 'diff-warn' },
    formatDiff(diff),
  )
}

defineExpose({
  clear,
  load,
  reset: handleReset,
  saveDraft,
  state: stateSnapshot,
  rows,
  dirty,
  balanced,
  version: allocationVersion,
})
</script>

<template>
  <div ref="root" v-loading="loading" class="allocation-matrix-editor">
    <el-alert
      v-if="legacy"
      type="info"
      :closable="false"
      class="editor-alert"
      title="该 A 含历史分解数据，以下仅汇总展示既有学院 B 分工；不能新增、保存或再次下发"
    />
    <el-alert
      v-else-if="distributed"
      type="info"
      :closable="false"
      class="editor-alert"
      title="已有学院任务只读，可新增学院并单独下发；已有目标调整请发起延期 / 转移申请"
    />
    <el-alert
      v-else-if="archived"
      type="info"
      :closable="false"
      class="editor-alert"
      title="该计划已归档，分工矩阵只读"
    />
    <el-alert
      v-else-if="readOnly"
      type="info"
      :closable="false"
      class="editor-alert"
      title="当前指标分工只读"
    />
    <el-alert
      v-else-if="!summable"
      type="info"
      :closable="false"
      class="editor-alert"
      :title="isBoolean
        ? '是否型指标不做目标求和校验，每行勾选计划完成年份'
        : '占比 / 直填百分比型指标不做目标求和校验，完成值按 Σ分子 ÷ Σ分母 汇总'"
    />
    <template v-else>
      <el-alert
        v-if="canDistribute"
        type="success"
        :closable="false"
        class="editor-alert"
        title="各年度合计不低于指标目标，可以下发；超额分配允许"
      />
      <el-alert v-else type="warning" :closable="false" class="editor-alert">
        <template #title>
          <span v-if="!hasAnyTarget">尚未为任何执行单元分解目标；</span>
          <span v-if="ineligibleTargetRows.length > 0">
            以下单元已停用或不允许承接新 B：{{ ineligibleTargetRows.map((row) => row.unitCode).join('、') }}；
          </span>
          <template v-if="unbalancedItems.length > 0">
            合计低于指标目标（点击年份定位）：
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
      <ResponsiveRecordView class="matrix-records">
        <template #table>
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
                <span v-else>{{ userOptions.find((user) => user.id === row.ownerId)?.name ?? '—' }}</span>
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
                <span>{{ year }}</span>
              </template>
              <template #default="{ row }">
                <template v-if="rowReadOnly(row)">
                  {{ formatTargetValue(row.targets[year] ?? null, statCaliber) }}
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
                  :precision="statCaliber === 'INCREMENT' ? 0 : undefined"
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
                  {{ rowBooleanPlanYear(row.targets) === null ? '—' : `是（${rowBooleanPlanYear(row.targets)} 年）` }}
                </template>
                <template v-else>{{ formatTargetValue(rowTotalTarget(row.targets), statCaliber) }}</template>
              </template>
            </el-table-column>
            <el-table-column
              v-if="!readOnly"
              label="操作"
              width="120"
              fixed="right"
              align="center"
              prop="__actions__"
            >
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
                <span v-else class="empty-text">{{ row.isHistorical ? '历史只读' : '已生效只读' }}</span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty :image-size="80" description="暂无分解行，请在下方新增执行单元" />
            </template>
          </el-table>
        </template>
        <template #cards>
          <div class="responsive-card-list allocation-mobile-list">
            <article
              v-for="(row, index) in rows"
              :key="row.unitId"
              class="responsive-card allocation-mobile-card"
              :class="{ 'missing-owner': rowHasTarget(row.targets) && !row.ownerId, 'is-historical': row.isHistorical }"
            >
              <div class="responsive-card__header">
                <div class="responsive-card__title">
                  <span class="mono">{{ row.unitCode }}</span> {{ row.unitName }}
                </div>
                <el-tag v-if="row.isHistorical" type="info" size="small" effect="plain">历史只读</el-tag>
              </div>
              <div class="mobile-owner-field">
                <label>学院负责人</label>
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
                <strong v-else class="mobile-readonly-value">
                  {{ userOptions.find((user) => user.id === row.ownerId)?.name ?? '—' }}
                </strong>
              </div>
              <div class="responsive-year-grid allocation-mobile-years">
                <div
                  v-for="year in years"
                  :key="year"
                  class="mobile-target-field"
                  :class="[`mobile-year-col-${year}`, {
                    'is-edited': editedCells.has(`${row.unitId}-${year}`),
                  }]"
                >
                  <label>{{ year }} 年</label>
                  <strong v-if="rowReadOnly(row)" class="mobile-readonly-value">
                    {{ formatTargetValue(row.targets[year] ?? null, statCaliber) }}
                  </strong>
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
                    class="mobile-cell-input"
                    @update:model-value="(value: number | undefined) => setPercentCell(row, year, value)"
                  />
                  <el-input-number
                    v-else
                    :model-value="numberCellValue(row, year)"
                    :precision="statCaliber === 'INCREMENT' ? 0 : undefined"
                    :min="0"
                    controls-position="right"
                    class="mobile-cell-input"
                    @update:model-value="(value: number | undefined) => setNumberCell(row, year, value)"
                  />
                </div>
              </div>
              <div class="allocation-mobile-total">
                <span>五年目标</span>
                <strong v-if="isBoolean">
                  {{ rowBooleanPlanYear(row.targets) === null ? '—' : `是（${rowBooleanPlanYear(row.targets)} 年）` }}
                </strong>
                <strong v-else>{{ formatTargetValue(rowTotalTarget(row.targets), statCaliber) }}</strong>
              </div>
              <div v-if="!rowReadOnly(row)" class="responsive-card__actions allocation-mobile-actions">
                <el-button type="primary" plain @click="copyFromPrevYear(row)">顺沿上年</el-button>
                <el-button @click="clearRow(row)">清空本行</el-button>
                <el-button type="danger" plain @click="removeRow(index)">删除行</el-button>
              </div>
              <div v-else class="mobile-readonly-note">
                {{ legacy ? '历史分工只读' : row.isHistorical ? '历史目标冻结，仅参与守恒' : archived ? '计划已归档，目标只读' : '任务已下发，目标只读' }}
              </div>
            </article>
            <el-empty
              v-if="rows.length === 0"
              :image-size="80"
              description="暂无分解行，请在下方新增执行单元"
            />
            <div v-if="summable" class="responsive-card allocation-mobile-summary">
              <div class="responsive-card__title">逐年守恒核对</div>
              <div class="responsive-year-grid mobile-summary-grid">
                <div
                  v-for="item in liveSummary"
                  :key="item.year"
                  class="mobile-summary-year"
                  :class="item.diff === null || item.diff >= 0 ? 'is-ok' : 'is-warn'"
                >
                  <strong>{{ item.year }} 年</strong>
                  <span>合计 {{ formatTargetValue(item.allocatedTarget, statCaliber) }}</span>
                  <span>A 目标 {{ formatTargetValue(item.indicatorTarget, statCaliber) }}</span>
                  <span :class="item.diff === null ? 'diff-na' : item.diff >= 0 ? 'diff-ok' : 'diff-warn'">
                    差值 {{ formatDiff(item.diff) }}
                  </span>
                </div>
              </div>
              <div class="allocation-mobile-summary-total">
                <span>五年合计 {{ formatNumber(allocatedFiveYear) }}</span>
                <span>A 目标 {{ formatTargetValue(totalTarget ?? sumYearTargets(yearTargets), statCaliber) }}</span>
                <span :class="fiveYearDiff === null ? 'diff-na' : fiveYearDiff >= 0 ? 'diff-ok' : 'diff-warn'">
                  差值 {{ formatDiff(fiveYearDiff) }}
                </span>
              </div>
            </div>
          </div>
        </template>
      </ResponsiveRecordView>

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
        <el-button
          type="primary"
          plain
          :disabled="!summable"
          :title="summable ? '按指标各年度目标平均分配给可编辑执行单元' : '占比 / 直填百分比 / 是否型不参与合计，无法自动分配'"
          @click="autoDistribute"
        >
          自动分配
        </el-button>
      </div>
    </el-card>

    <div v-if="!readOnly" class="sticky-bar">
      <span class="bar-status">
        <template v-if="dirty">已修改未保存</template>
        <template v-else-if="lastSavedAt">上次保存 {{ lastSavedAt }}</template>
        <template v-else-if="serverBalanced !== null">
          {{ serverBalanced ? '已保存（平衡）' : '已保存（未平衡）' }}
        </template>
        <template v-else>保存指标后可单独保存分工草稿</template>
      </span>
      <div class="bar-actions">
        <el-button :disabled="!dirty" @click="handleReset">重置</el-button>
        <el-button
          :loading="saving"
          :disabled="!dirty || !activeIndicatorId"
          @click="saveDraft()"
        >
          存草稿
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.editor-alert {
  margin-bottom: var(--space-4);
}

.matrix-card,
.matrix-records,
.allocation-mobile-list,
.allocation-mobile-card {
  min-width: 0;
}

.matrix-card {
  border-radius: var(--radius-card);
}

.allocation-mobile-card.missing-owner {
  border-left: 3px solid var(--el-color-warning);
}

.allocation-mobile-card.is-historical {
  background: var(--el-fill-color-light);
}

.mobile-owner-field {
  margin: var(--space-3) 0;
}

.mobile-owner-field label,
.mobile-target-field label {
  display: block;
  margin-bottom: var(--space-1);
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.mobile-owner-field :deep(.el-select),
.mobile-cell-input {
  width: 100%;
}

.mobile-target-field {
  position: relative;
  min-width: 0;
  padding: var(--space-2);
  background: var(--el-fill-color-light);
  border: 1px solid transparent;
  border-radius: var(--el-border-radius-base);
}

.mobile-target-field.is-edited {
  border-top-color: var(--el-color-primary);
}

.mobile-readonly-value {
  display: flex;
  align-items: center;
  min-height: 40px;
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}

.allocation-mobile-total {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-top: var(--space-3);
  padding: var(--space-2) var(--space-3);
  background: var(--el-fill-color-light);
  border-radius: var(--el-border-radius-base);
  font-variant-numeric: tabular-nums;
}

.allocation-mobile-total > span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.allocation-mobile-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin-top: var(--space-3);
}

.allocation-mobile-actions .el-button {
  width: 100%;
  min-height: 44px;
  margin: 0;
}

.mobile-readonly-note {
  margin-top: var(--space-3);
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-align: right;
}

.mobile-summary-grid {
  margin-top: var(--space-3);
}

.mobile-summary-year {
  display: flex;
  min-width: 0;
  flex-direction: column;
  padding: var(--space-2) var(--space-3);
  background: var(--el-fill-color-light);
  border-radius: var(--el-border-radius-base);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.mobile-summary-year.is-warn {
  background: var(--el-color-warning-light-9);
}

.mobile-summary-year > strong {
  margin-bottom: 2px;
  font-size: 13px;
}

.allocation-mobile-summary-total {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
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
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 56px;
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

:deep(.diff-warn) {
  color: var(--el-color-warning);
  font-weight: 600;
}

:deep(.diff-na) {
  color: var(--el-text-color-placeholder);
}

@media (max-width: 768px) {
  .matrix-card :deep(.el-card__body) {
    padding: var(--space-3);
  }

  .add-row-bar {
    align-items: stretch;
    flex-direction: column;
  }

  .add-row-bar :deep(.el-select),
  .add-row-bar .el-button {
    width: 100% !important;
    min-height: 44px;
    margin: 0;
  }

  .sticky-bar {
    align-items: stretch;
    flex-direction: column;
    height: auto;
    gap: var(--space-3);
    padding: var(--space-3);
    padding-bottom: calc(var(--space-3) + env(safe-area-inset-bottom));
  }

  .bar-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    width: 100%;
  }

  .bar-actions .el-button {
    width: 100%;
    min-height: 44px;
    margin: 0;
  }
}

@media (max-width: 428px) {
  .allocation-mobile-actions {
    grid-template-columns: minmax(0, 1fr);
  }

  .allocation-mobile-actions .el-button {
    justify-content: center;
  }

  .allocation-mobile-total {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--space-1);
  }
}
</style>
