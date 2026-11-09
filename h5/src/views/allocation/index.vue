<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showDialog, showToast } from 'vant'
import StatusTag from '@/components/StatusTag.vue'
import PageState from '@/components/PageState.vue'
import PlanSwitcher from '@/components/PlanSwitcher.vue'
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
  allocationToMatrixRows,
  buildSaveAllocationRequest,
  canAssignNewB,
  computeYearSummary,
  distributeYearTargetsEvenly,
  emptyMatrixTargets,
  isPendingAllocationRow,
  isSummaryBalanced,
  isSummableCaliber,
  percentToDisplay,
  percentToStore,
  rowBooleanPlanYear,
  rowHasTarget,
  rowTotalTarget,
  rowsMissingOwner,
} from '@/utils/allocation'
import type { MatrixRow } from '@/utils/allocation'
import type { PlanCard, PlanDetail } from '@/types/plan'
import type { IndicatorTreeNode, YearTarget } from '@/types/indicator'
import type { IndicatorStatus, StatCaliber } from '@/types/enums'
import type { UserOption } from '@/types/user'
import type { Allocation } from '@/types/allocation'

interface AllocatableItem {
  id: string
  code: string
  name: string
  status: IndicatorStatus
  statCaliber: StatCaliber
  unitLabel: string | null
  totalTarget: number | null
  yearTargets: YearTarget[]
  isLegacy: boolean
  legacyChildIds: string[]
}

const route = useRoute()
const router = useRouter()

const planId = computed(() => (route.params.planId as string) || (route.query.planId as string) || '')
const loading = ref(false)
const plan = ref<PlanDetail | null>(null)
const plans = ref<PlanCard[]>([])
const tree = ref<IndicatorTreeNode[]>([])
const userOptions = ref<UserOption[]>([])
const unitOptions = ref<OrgUnit[]>([])
const unitFallback = ref(false)
const keyword = ref('')
const selectedId = ref('')
const selectorVisible = ref(false)

const years = computed<number[]>(() => {
  if (!plan.value) return []
  const list: number[] = []
  for (let year = plan.value.startYear; year <= plan.value.endYear; year += 1) list.push(year)
  return list
})

const allocatable = computed<AllocatableItem[]>(() =>
  tree.value.flatMap((node) => {
    if (node.level !== 'A' || !node.isQuantifiable || !node.statCaliber) return []
    return [{
      id: node.id,
      code: node.code,
      name: node.name,
      status: node.status,
      statCaliber: node.statCaliber,
      unitLabel: node.unitLabel,
      totalTarget: node.totalTarget,
      yearTargets: node.yearTargets ?? [],
      isLegacy: node.children.length > 0,
      legacyChildIds: node.children.map((child) => child.id),
    }]
  }),
)

const filteredAllocatable = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return allocatable.value
  return allocatable.value.filter(
    (item) => item.code.toLowerCase().includes(kw) || item.name.toLowerCase().includes(kw),
  )
})

const current = computed(() => allocatable.value.find((item) => item.id === selectedId.value) ?? null)
const currentReadOnly = computed(() => current.value?.isLegacy === true || plan.value?.status === 'ARCHIVED')

// ---------- 矩阵 ----------
const rows = ref<MatrixRow[]>([])
const allocationVersion = ref(0)
const serverBalanced = ref<boolean | null>(null)
const dirty = ref(false)
const matrixLoading = ref(false)
const saving = ref(false)
const lastSavedAt = ref<string | null>(null)
const lastAllocation = ref<Allocation | null>(null)
let loadRequestId = 0

const readOnly = computed(() => currentReadOnly.value || false)
const summable = computed(() => current.value ? isSummableCaliber(current.value.statCaliber) : false)
const isBoolean = computed(() => current.value?.statCaliber === 'BOOLEAN')
const isPercent = computed(() =>
  current.value?.statCaliber === 'RATIO' || current.value?.statCaliber === 'DIRECT_PERCENT',
)

const indicatorTargets = computed<Record<number, number | null>>(() => {
  const map: Record<number, number | null> = {}
  for (const item of current.value?.yearTargets ?? []) {
    map[item.year] = typeof item.target === 'number' ? item.target : null
  }
  return map
})

const liveSummary = computed(() =>
  computeYearSummary(rows.value, years.value, indicatorTargets.value),
)
const balanced = computed(() => (summable.value ? isSummaryBalanced(liveSummary.value) : true))
const allocatedFiveYear = computed(() =>
  rows.value.reduce((sum, row) => sum + rowTotalTarget(row.targets), 0),
)
const fiveYearDiff = computed(() => {
  const total = current.value?.totalTarget
  return total !== null && total !== undefined ? allocatedFiveYear.value - total : null
})
const pendingRows = computed(() => rows.value.filter(isPendingAllocationRow))
const missingOwners = computed(() => rowsMissingOwner(pendingRows.value))
const hasAnyTarget = computed(() => pendingRows.value.some((row) => rowHasTarget(row.targets)))
const assignableUnitIds = computed(
  () => new Set(unitOptions.value.filter((unit) => canAssignNewB(unit)).map((unit) => unit.id)),
)
const ineligibleRows = computed(() =>
  pendingRows.value.filter((row) => rowHasTarget(row.targets) && !assignableUnitIds.value.has(row.unitId)),
)
const blockReasons = computed<string[]>(() => {
  const reasons: string[] = []
  if (!hasAnyTarget.value) reasons.push('尚未为任何执行单元分解目标')
  if (ineligibleRows.value.length > 0) {
    reasons.push(`以下单元已停用或不允许承接新 B：${ineligibleRows.value.map((row) => row.unitCode).join('、')}`)
  }
  if (summable.value && !balanced.value) {
    const bad = liveSummary.value
      .filter((item) => item.diff !== null && item.diff < 0)
      .map((item) => `${item.year} 年（差值 ${formatDiff(item.diff)}）`)
    reasons.push(`合计低于指标目标：${bad.join('、')}`)
  }
  if (missingOwners.value.length > 0) {
    reasons.push(`缺少学院负责人：${missingOwners.value.map((row) => row.unitCode).join('、')}`)
  }
  return reasons
})
const canDistribute = computed(() => !readOnly.value && blockReasons.value.length === 0)

const addableUnits = computed(() => {
  const used = new Set(rows.value.map((row) => row.unitId))
  return unitOptions.value.filter((unit) => canAssignNewB(unit) && !used.has(unit.id))
})

function rowReadOnly(row: MatrixRow): boolean {
  return readOnly.value || !isPendingAllocationRow(row)
}

function rebuildRows(allocation: Allocation): void {
  if (!current.value) return
  rows.value = allocationToMatrixRows(allocation, years.value, current.value.statCaliber)
  allocationVersion.value = allocation.version
  serverBalanced.value = allocation.isBalanced
  lastAllocation.value = allocation
  dirty.value = false
}

async function loadMatrix(): Promise<void> {
  const item = current.value
  if (!item) return
  const requestId = ++loadRequestId
  matrixLoading.value = true
  rows.value = []
  allocationVersion.value = 0
  serverBalanced.value = null
  lastSavedAt.value = null
  try {
    const allocation = await getAllocation(item.id)
    if (requestId !== loadRequestId || selectedId.value !== item.id) return
    rebuildRows(allocation)
  } finally {
    if (requestId === loadRequestId) matrixLoading.value = false
  }
}

async function confirmDiscard(): Promise<boolean> {
  if (!dirty.value) return true
  try {
    await showConfirmDialog({
      title: '未保存的修改',
      message: '当前矩阵有未保存的修改，切换后将丢失。确认切换？',
      confirmButtonText: '放弃并切换',
    })
    return true
  } catch {
    return false
  }
}

async function selectIndicator(id: string): Promise<void> {
  if (id === selectedId.value) return
  if (!(await confirmDiscard())) return
  selectedId.value = id
  selectorVisible.value = false
  await loadMatrix()
}

function onPlanChange(planOption: { id: string }): void {
  if (dirty.value) {
    void confirmDiscard().then((ok) => {
      if (ok) router.replace(`/plans/${planOption.id}/allocation`)
    })
    return
  }
  router.replace(`/plans/${planOption.id}/allocation`)
}

async function loadTree(): Promise<void> {
  if (!planId.value) return
  tree.value = await listIndicators(planId.value, { view: 'tree' })
}

async function loadAll(): Promise<void> {
  if (!planId.value) return
  loading.value = true
  rows.value = []
  selectedId.value = ''
  try {
    const [planDetail] = await Promise.all([getPlan(planId.value), loadTree()])
    plan.value = planDetail
    const queryId = String(route.query.indicatorId ?? '')
    const first = allocatable.value.find((item) => item.id === queryId)
      ?? allocatable.value.find((item) => queryId && item.legacyChildIds.includes(queryId))
      ?? allocatable.value[0]
    if (first) {
      selectedId.value = first.id
      await loadMatrix()
    }
  } finally {
    loading.value = false
  }
}

async function loadPlans(): Promise<void> {
  try {
    plans.value = await listPlans()
    if (!planId.value) {
      const active = plans.value.find((item) => item.status === 'ACTIVE') ?? plans.value[0]
      if (active) await router.replace(`/plans/${active.id}/allocation`)
    }
  } catch {
    // 拦截器已提示
  }
}

// ---------- 编辑 ----------
function setNumber(row: MatrixRow, year: number, value: string | number | null): void {
  if (rowReadOnly(row)) return
  const parsed = value === '' || value === null ? null : Number(value)
  row.targets[year] = parsed === null || !Number.isFinite(parsed) ? null : parsed
  dirty.value = true
}
function setPercent(row: MatrixRow, year: number, value: string | number | null): void {
  if (rowReadOnly(row)) return
  const parsed = value === '' || value === null ? null : Number(value)
  row.targets[year] = percentToStore(parsed)
  dirty.value = true
}
function toggleBoolean(row: MatrixRow, year: number, checked: boolean): void {
  if (rowReadOnly(row)) return
  for (const y of years.value) row.targets[y] = null
  row.targets[year] = checked ? true : null
  dirty.value = true
}
function numberValue(row: MatrixRow, year: number): number | string {
  const value = row.targets[year]
  return typeof value === 'number' ? value : ''
}
function copyFromPrevYear(row: MatrixRow): void {
  if (rowReadOnly(row)) return
  for (let index = years.value.length - 1; index > 0; index -= 1) {
    row.targets[years.value[index]] = row.targets[years.value[index - 1]]
  }
  dirty.value = true
}
function clearRow(row: MatrixRow): void {
  if (rowReadOnly(row)) return
  for (const year of years.value) row.targets[year] = null
  dirty.value = true
}
function removeRow(index: number): void {
  if (!rows.value[index] || rowReadOnly(rows.value[index])) return
  rows.value.splice(index, 1)
  dirty.value = true
}

// 负责人选择
const ownerPickerVisible = ref(false)
const ownerTargetIndex = ref<number | null>(null)
const ownerKeyword = ref('')
const filteredOwners = computed(() => {
  const kw = ownerKeyword.value.trim()
  return kw ? userOptions.value.filter((item) => item.name.includes(kw) || item.username.includes(kw)) : userOptions.value
})
function openOwnerPicker(index: number): void {
  if (rowReadOnly(rows.value[index])) return
  ownerTargetIndex.value = index
  ownerKeyword.value = ''
  ownerPickerVisible.value = true
}
function chooseOwner(user: UserOption | null): void {
  const index = ownerTargetIndex.value
  if (index === null || !rows.value[index]) return
  rows.value[index].ownerId = user?.id ?? null
  dirty.value = true
  ownerPickerVisible.value = false
}
function ownerName(ownerId: string | null): string {
  return userOptions.value.find((item) => item.id === ownerId)?.name ?? '选择负责人'
}

// 新增行
const unitPickerVisible = ref(false)
const addRowUnitId = ref('')
function chooseUnit(unit: OrgUnit): void {
  rows.value.push({
    unitId: unit.id,
    unitCode: unit.code,
    unitName: unit.name,
    ownerId: null,
    isHistorical: false,
    taskStatus: null,
    targets: emptyMatrixTargets(years.value),
  })
  dirty.value = true
  unitPickerVisible.value = false
}

async function autoDistribute(): Promise<void> {
  if (readOnly.value || !summable.value) return
  if (!years.value.some((year) => typeof indicatorTargets.value[year] === 'number')) {
    showToast('请先设置该指标的年度目标')
    return
  }
  let editableRows = rows.value.filter((row) => !rowReadOnly(row))
  if (editableRows.length === 0) {
    const unitsToAdd = [...addableUnits.value]
    if (unitsToAdd.length === 0) {
      showToast('暂无可承接新任务的执行单元')
      return
    }
    try {
      await showConfirmDialog({
        title: '自动分配',
        message: `当前没有可编辑的分解行，将为 ${unitsToAdd.length} 个可承接执行单元新增分解行并平均分配，是否继续？`,
        confirmButtonText: '继续',
      })
    } catch {
      return
    }
    for (const unit of unitsToAdd) {
      rows.value.push({
        unitId: unit.id,
        unitCode: unit.code,
        unitName: unit.name,
        ownerId: null,
        isHistorical: false,
        taskStatus: null,
        targets: emptyMatrixTargets(years.value),
      })
    }
    editableRows = rows.value.filter((row) => !rowReadOnly(row))
  } else if (editableRows.some((row) => rowHasTarget(row.targets))) {
    try {
      await showConfirmDialog({
        title: '自动分配',
        message: '自动分配将覆盖可编辑行已填写的年度目标，是否继续？',
        confirmButtonText: '继续',
      })
    } catch {
      return
    }
  }
  const scale = current.value?.statCaliber === 'INCREMENT' ? 1 : 100
  const result = distributeYearTargetsEvenly(rows.value, years.value, indicatorTargets.value, scale)
  if (result.filledYears.length === 0) {
    showToast('没有可分配的年度（目标为空或只读行已满足目标）')
    return
  }
  dirty.value = true
  showToast(`已为 ${editableRows.length} 个执行单元平均分配 ${result.filledYears.length} 个年度目标`)
}

// ---------- 保存 / 下发 ----------
async function saveDraft(): Promise<boolean> {
  if (!current.value || readOnly.value || saving.value) return false
  saving.value = true
  try {
    const allocation = await saveAllocation(
      current.value.id,
      buildSaveAllocationRequest(rows.value, years.value, allocationVersion.value),
      true,
    )
    rebuildRows(allocation)
    lastSavedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    showToast(allocation.isBalanced || !summable.value ? '已保存' : '已保存草稿（部分年度低于指标目标）')
    return true
  } catch (error) {
    if (error instanceof ApiError && error.code === 10005) {
      try {
        await showConfirmDialog({
          title: '保存冲突',
          message: '该矩阵已被他人修改。是否保留我的修改并覆盖？',
          confirmButtonText: '覆盖保存',
        })
        const latest = await getAllocation(current.value.id)
        allocationVersion.value = latest.version
        const allocation = await saveAllocation(
          current.value.id,
          buildSaveAllocationRequest(rows.value, years.value, latest.version),
          true,
        )
        rebuildRows(allocation)
        showToast('已覆盖保存')
        return true
      } catch {
        await loadMatrix()
        return false
      }
    }
    showToast(error instanceof ApiError ? error.message : '保存失败，请稍后重试')
    return false
  } finally {
    saving.value = false
  }
}

function resetMatrix(): void {
  if (lastAllocation.value) rebuildRows(lastAllocation.value)
  dirty.value = false
}

const previewVisible = ref(false)
const distributing = ref(false)
const distributeKey = ref(newIdempotencyKey())
const batchDistributing = ref(false)
const batchDistributeKey = ref(newIdempotencyKey())
const batchScanning = ref(false)

const previewRows = computed(() =>
  pendingRows.value
    .filter((row) => assignableUnitIds.value.has(row.unitId) && rowHasTarget(row.targets))
    .map((row) => ({
      code: `${current.value?.code ?? ''}-${row.unitCode}`,
      unitName: row.unitName,
      ownerName: userOptions.value.find((user) => user.id === row.ownerId)?.name ?? '—',
      fiveYearTotal: rowTotalTarget(row.targets),
    })),
)

async function openPreview(): Promise<void> {
  if (!canDistribute.value) {
    showToast(blockReasons.value.join('；') || '当前不可下发')
    return
  }
  if (dirty.value) {
    showToast('有未保存的修改，请先存草稿')
    return
  }
  if (previewRows.value.length === 0) {
    showToast('没有待下发的学院任务')
    return
  }
  previewVisible.value = true
}

async function handleDistribute(): Promise<void> {
  if (!current.value || !plan.value) return
  distributing.value = true
  try {
    await distributePlan(
      planId.value,
      { indicatorIds: [current.value.id], version: plan.value.version },
      distributeKey.value,
    )
    distributeKey.value = newIdempotencyKey()
    showToast(`「${current.value.code}」已正式交给学院`)
    previewVisible.value = false
    await loadTree()
    plan.value = await getPlan(planId.value)
    await loadMatrix()
  } catch (error) {
    showDistributeError(error)
  } finally {
    distributing.value = false
  }
}

function showDistributeError(error: unknown): void {
  if (error instanceof ApiError && [10001, 40002, 40003, 50001, 50002].includes(error.code)) {
    const lines = error.fieldErrors.map((item) => item.message)
    void showDialog({
      title: `${error.message}（${error.code}）`,
      message: lines.length > 0 ? lines.join('\n') : error.message,
      confirmButtonText: '返回修改',
    })
  }
}

async function handleBatchDistribute(): Promise<void> {
  if (!plan.value || plan.value.status === 'ARCHIVED') return
  batchScanning.value = true
  try {
    const pending = allocatable.value.filter((item) => !item.isLegacy && item.status !== 'DISTRIBUTED')
    const ready: string[] = []
    const results = await Promise.all(
      pending.map((item) =>
        getAllocation(item.id)
          .then((allocation) => ({
            item,
            allocation,
          }))
          .catch(() => null),
      ),
    )
    for (const result of results) {
      if (!result) continue
      const hasPending = result.allocation.rows.some(
        (row) => !row.isHistorical && row.taskStatus === 'PENDING_DISTRIBUTION',
      )
      if (result.allocation.isBalanced && hasPending) ready.push(result.item.id)
    }
    if (ready.length === 0) {
      showToast('当前没有「已平衡且未下发」的指标')
      return
    }
    const codes = allocatable.value.filter((item) => ready.includes(item.id)).map((item) => item.code)
    try {
      await showConfirmDialog({
        title: '批量正式交给学院',
        message: `将把以下 ${ready.length} 个已平衡指标交给学院：${codes.join('、')}。确认后生成学院任务并通知负责人。`,
        confirmButtonText: '确认交给学院',
      })
    } catch {
      return
    }
    batchDistributing.value = true
    await distributePlan(
      planId.value,
      { indicatorIds: ready, version: plan.value.version },
      batchDistributeKey.value,
    )
    batchDistributeKey.value = newIdempotencyKey()
    showToast(`已将 ${ready.length} 个指标正式交给学院`)
    await loadTree()
    plan.value = await getPlan(planId.value)
    if (selectedId.value) await loadMatrix()
  } catch (error) {
    showDistributeError(error)
  } finally {
    batchScanning.value = false
    batchDistributing.value = false
  }
}

// ---------- 计算属性 ----------
const statusHint = computed(() => {
  if (current.value?.isLegacy) return '该 A 含历史分解数据，以下仅汇总展示既有学院任务，不能新增、保存或再次下发'
  if (plan.value?.status === 'ARCHIVED') return '该计划已归档，分工矩阵只读'
  if (current.value?.status === 'DISTRIBUTED') return '已有学院任务只读，可新增学院并单独下发；已有目标调整请发起延期 / 转移申请'
  if (!summable.value) {
    if (current.value?.statCaliber === 'BOOLEAN') return '是否型指标不做目标求和校验，每行勾选计划完成年份'
    if (current.value?.statCaliber === 'GROWTH') return '增长率型指标不做目标求和校验，完成值按汇总总量计算增长率'
    if (current.value?.statCaliber === 'RATIO') return '占比型指标不做目标求和校验，完成值按 Σ分子 ÷ Σ分母 汇总'
    return '直填百分比型指标不做目标求和校验'
  }
  return ''
})

function rowFiveYear(row: MatrixRow): string {
  if (isBoolean.value) {
    const year = rowBooleanPlanYear(row.targets)
    return year === null ? '—' : `是（${year} 年）`
  }
  return formatTargetValue(rowTotalTarget(row.targets), current.value?.statCaliber)
}

watch(planId, () => {
  plan.value = null
  tree.value = []
  rows.value = []
  selectedId.value = ''
  dirty.value = false
  if (planId.value) void loadAll()
}, { immediate: true })

onMounted(() => {
  void loadPlans()
  getUserOptions().then((list) => {
    userOptions.value = list
  }).catch(() => undefined)
  listOrgUnits().then((list) => {
    unitOptions.value = list
  }).catch(() => {
    unitOptions.value = []
    unitFallback.value = true
  })
})

const selectedLabel = computed(() => (current.value ? `${current.value.code} ${current.value.name}` : '选择指标'))
</script>

<template>
  <div class="page" :class="{ 'page--footer': !readOnly && current }">
    <PlanSwitcher
      v-if="plans.length > 0"
      :model-value="planId"
      :plans="plans"
      @change="onPlanChange"
    />

    <PageState :loading="loading && !plan" loading-text="加载分解数据…">
      <template v-if="plan">
        <div class="page-head">
          <div>
            <h1>任务分解</h1>
            <p class="muted small">{{ plan.name }}</p>
          </div>
          <div class="head-actions">
            <van-button size="small" type="primary" :disabled="!canDistribute" @click="openPreview">
              正式交给学院
            </van-button>
            <van-button
              size="small"
              :loading="batchScanning || batchDistributing"
              :disabled="plan.status === 'ARCHIVED'"
              @click="handleBatchDistribute"
            >批量下发</van-button>
          </div>
        </div>

        <div v-if="unitFallback" class="warn-box">
          执行单元列表接口暂不可用，新增行功能已停用（矩阵行以下发数据为准）
        </div>

        <button type="button" class="indicator-selector" @click="selectorVisible = true">
          <div class="grow">
            <div class="small muted">当前指标</div>
            <div class="ellipsis">{{ selectedLabel }}</div>
          </div>
          <van-icon name="arrow-down" size="14" />
        </button>

        <PageState :empty="allocatable.length === 0" empty-text="没有可分解的量化指标">
          <template v-if="current">
            <div class="indicator-info">
              <StatusTag kind="IndicatorLevel" value="A" />
              <span class="code">{{ current.code }}</span>
              <StatusTag kind="IndicatorStatus" :value="current.status" />
              <div class="small muted info-meta">
                {{ enumLabel('StatCaliber', current.statCaliber) }} · {{ current.unitLabel ?? '—' }} · 五年
                {{ formatTargetValue(current.totalTarget ?? sumYearTargets(current.yearTargets), current.statCaliber) }}
              </div>
            </div>

            <div v-if="statusHint" class="info-box">{{ statusHint }}</div>
            <div
              v-else-if="canDistribute"
              class="ok-box"
            >各年度合计不低于指标目标，可以下发；超额分配允许</div>
            <div v-else class="warn-box">{{ blockReasons.join('；') }}</div>

            <div v-if="matrixLoading" class="skeleton-block">加载矩阵…</div>
            <template v-else>
              <div class="matrix-list">
                <div
                  v-for="(row, index) in rows"
                  :key="row.unitId"
                  class="unit-card"
                  :class="{
                    'is-readonly': rowReadOnly(row),
                    'is-missing-owner': rowHasTarget(row.targets) && !row.ownerId,
                  }"
                >
                  <div class="row--between">
                    <div class="unit-name">
                      <span class="code">{{ row.unitCode }}</span>
                      {{ row.unitName }}
                    </div>
                    <span v-if="row.isHistorical" class="chip chip--gray">历史只读</span>
                    <span v-else-if="rowReadOnly(row)" class="chip chip--gray">已生效只读</span>
                  </div>

                  <div class="owner-line">
                    <span class="small muted">学院负责人</span>
                    <button
                      v-if="!rowReadOnly(row)"
                      type="button"
                      class="owner-btn"
                      :class="{ 'is-missing': rowHasTarget(row.targets) && !row.ownerId }"
                      @click="openOwnerPicker(index)"
                    >{{ ownerName(row.ownerId) }}</button>
                    <span v-else>{{ ownerName(row.ownerId) }}</span>
                  </div>

                  <div class="year-grid">
                    <div v-for="year in years" :key="year" class="year-field">
                      <label>{{ year }} 年</label>
                      <strong v-if="rowReadOnly(row)">{{ formatTargetValue(row.targets[year] ?? null, current.statCaliber) }}</strong>
                      <van-checkbox
                        v-else-if="isBoolean"
                        :model-value="row.targets[year] === true"
                        shape="square"
                        @update:model-value="(checked: boolean) => toggleBoolean(row, year, checked)"
                      />
                      <van-field
                        v-else-if="isPercent"
                        :model-value="percentToDisplay(row.targets[year] as number | null) ?? ''"
                        type="number"
                        inputmode="decimal"
                        placeholder="%"
                        @update:model-value="(value: string) => setPercent(row, year, value)"
                      />
                      <van-field
                        v-else
                        :model-value="numberValue(row, year)"
                        type="number"
                        inputmode="decimal"
                        placeholder="0"
                        @update:model-value="(value: string) => setNumber(row, year, value)"
                      />
                    </div>
                  </div>

                  <div class="unit-total">
                    <span>五年目标</span>
                    <strong>{{ rowFiveYear(row) }}</strong>
                  </div>

                  <div v-if="!rowReadOnly(row)" class="unit-actions">
                    <van-button size="mini" type="primary" plain @click="copyFromPrevYear(row)">顺沿上年</van-button>
                    <van-button size="mini" @click="clearRow(row)">清空本行</van-button>
                    <van-button size="mini" type="danger" plain @click="removeRow(index)">删除行</van-button>
                  </div>
                </div>
              </div>

              <div v-if="rows.length === 0" class="empty-state">暂无分解行，请新增执行单元</div>

              <div v-if="summable" class="card summary-card">
                <div class="card-title"><span>逐年守恒核对</span></div>
                <div class="summary-grid">
                  <div
                    v-for="item in liveSummary"
                    :key="item.year"
                    class="summary-cell"
                    :class="item.diff === null || item.diff >= 0 ? 'is-ok' : 'is-warn'"
                  >
                    <strong>{{ item.year }} 年</strong>
                    <span>合计 {{ formatTargetValue(item.allocatedTarget, current.statCaliber) }}</span>
                    <span>A 目标 {{ formatTargetValue(item.indicatorTarget, current.statCaliber) }}</span>
                    <span>差值 {{ formatDiff(item.diff) }}</span>
                  </div>
                </div>
                <div class="summary-total">
                  <span>五年合计 {{ formatNumber(allocatedFiveYear) }}</span>
                  <span>A 目标 {{ formatTargetValue(current.totalTarget ?? sumYearTargets(current.yearTargets), current.statCaliber) }}</span>
                  <span>差值 {{ formatDiff(fiveYearDiff) }}</span>
                </div>
              </div>

              <div v-if="!readOnly" class="add-row">
                <van-button size="small" :disabled="addableUnits.length === 0" @click="unitPickerVisible = true">
                  新增执行单元
                </van-button>
                <van-button size="small" type="primary" plain :disabled="!summable" @click="autoDistribute">
                  自动分配
                </van-button>
              </div>
            </template>
          </template>
        </PageState>
      </template>
    </PageState>

    <div v-if="!readOnly && current" class="sticky-footer">
      <div class="save-hint">
        <template v-if="dirty">已修改未保存</template>
        <template v-else-if="lastSavedAt">上次保存 {{ lastSavedAt }}</template>
        <template v-else-if="serverBalanced !== null">
          {{ summable ? (serverBalanced ? '已保存（平衡）' : '已保存（未平衡）') : '已保存' }}
        </template>
        <template v-else>保存指标后可单独保存分工草稿</template>
      </div>
      <div class="sticky-footer__row">
        <van-button :disabled="!dirty" @click="resetMatrix">重置</van-button>
        <van-button type="primary" :loading="saving" :disabled="!dirty" @click="saveDraft">存草稿</van-button>
      </div>
    </div>

    <!-- 指标选择 -->
    <van-popup v-model:show="selectorVisible" position="bottom" round :style="{ height: '72vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>选择指标</strong>
          <button type="button" class="close-btn" @click="selectorVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <van-search v-model="keyword" placeholder="搜索编号 / 名称" shape="round" />
        <div class="picker-list">
          <button
            v-for="item in filteredAllocatable"
            :key="item.id"
            type="button"
            class="picker-item"
            :class="{ 'is-active': item.id === selectedId }"
            @click="selectIndicator(item.id)"
          >
            <div class="row--between">
              <strong class="code">{{ item.code }}</strong>
              <StatusTag kind="IndicatorStatus" :value="item.status" />
            </div>
            <div class="ellipsis small">{{ item.name }}</div>
          </button>
          <div v-if="filteredAllocatable.length === 0" class="empty-state">没有可分解的指标</div>
        </div>
      </div>
    </van-popup>

    <!-- 负责人选择 -->
    <van-popup v-model:show="ownerPickerVisible" position="bottom" round :style="{ height: '65vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>选择学院负责人</strong>
          <button type="button" class="close-btn" @click="ownerPickerVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <van-search v-model="ownerKeyword" placeholder="搜索姓名 / 工号" shape="round" />
        <div class="picker-list">
          <button type="button" class="picker-item" @click="chooseOwner(null)">不指定负责人</button>
          <button
            v-for="user in filteredOwners"
            :key="user.id"
            type="button"
            class="picker-item"
            @click="chooseOwner(user)"
          >
            <strong>{{ user.name }}</strong>
            <span class="small muted">（{{ user.username }}）</span>
          </button>
        </div>
      </div>
    </van-popup>

    <!-- 执行单元选择 -->
    <van-popup v-model:show="unitPickerVisible" position="bottom" round :style="{ height: '65vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>新增执行单元</strong>
          <button type="button" class="close-btn" @click="unitPickerVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="picker-list">
          <button
            v-for="unit in addableUnits"
            :key="unit.id"
            type="button"
            class="picker-item"
            @click="chooseUnit(unit)"
          >
            <strong>{{ unit.code }}</strong> {{ unit.name }}
          </button>
          <div v-if="addableUnits.length === 0" class="empty-state">暂无可新增的执行单元</div>
        </div>
      </div>
    </van-popup>

    <!-- 下发预览 -->
    <van-popup v-model:show="previewVisible" position="bottom" round :style="{ height: '75vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>正式交给学院</strong>
          <button type="button" class="close-btn" @click="previewVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="preview-body">
          <p class="small muted">{{ current?.code }} {{ current?.name }}</p>
          <div class="preview-row" v-for="row in previewRows" :key="row.code">
            <div class="row--between">
              <strong>{{ row.code }}</strong>
              <span class="small">五年 {{ formatTargetValue(row.fiveYearTotal, current?.statCaliber) }}</span>
            </div>
            <div class="small muted">{{ row.unitName }} · 负责人 {{ row.ownerName }}</div>
          </div>
          <p class="small muted">确认后生成学院任务并通知负责人，任务编号与负责人不可修改。</p>
        </div>
        <div class="sheet-actions">
          <van-button block @click="previewVisible = false">取消</van-button>
          <van-button block type="primary" :loading="distributing" @click="handleDistribute">确认交给学院</van-button>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin: 12px 0;
}
.page-head h1 {
  margin: 0;
  font-size: 22px;
}
.page-head p {
  margin: 2px 0 0;
}
.head-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0;
}
.indicator-selector {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 52px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--text);
  text-align: left;
}
.indicator-info {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin: 10px 0;
}
.info-meta {
  width: 100%;
}
.code {
  color: var(--brand);
  font-family: Consolas, Menlo, monospace;
  font-size: 13px;
  font-weight: 600;
}
.info-box,
.warn-box,
.ok-box {
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 18px;
}
.info-box {
  background: #eef3fc;
  color: #4a5f85;
}
.warn-box {
  background: #fdf3e5;
  color: #8f5d16;
}
.ok-box {
  background: #e8f7f0;
  color: #147a51;
}
.matrix-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.unit-card {
  padding: 12px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.unit-card.is-readonly {
  background: #f7f9fc;
}
.unit-card.is-missing-owner {
  border-left: 3px solid var(--warning);
}
.unit-name {
  font-size: 14px;
  font-weight: 600;
}
.owner-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 10px 0;
}
.owner-btn {
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fbfcfe;
  color: var(--text);
  font-size: 13px;
}
.owner-btn.is-missing {
  border-color: var(--danger);
  color: var(--danger);
}
.year-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.year-field {
  padding: 8px;
  border-radius: 8px;
  background: #f8fafd;
}
.year-field label {
  display: block;
  margin-bottom: 4px;
  color: var(--text-secondary);
  font-size: 11px;
}
.year-field :deep(.van-field) {
  padding: 6px 8px;
  border-radius: 6px;
  background: #fff;
}
.year-field :deep(.van-field__control) {
  font-size: 14px;
}
.unit-total {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f5f8ff;
  font-size: 12px;
}
.unit-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.summary-card {
  margin-top: 12px;
}
.summary-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 8px;
}
.summary-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8fafd;
  font-size: 12px;
}
.summary-cell.is-warn {
  background: #fdf3e5;
}
.summary-total {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--line);
  font-size: 12px;
  font-weight: 600;
}
.add-row {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.save-hint {
  margin-bottom: 6px;
  color: var(--text-secondary);
  font-size: 11px;
  text-align: center;
}
.picker-sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 14px 14px calc(14px + var(--safe-bottom));
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-size: 16px;
}
.close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background: #f4f6fa;
  color: var(--text-secondary);
}
.picker-list {
  flex: 1;
  overflow-y: auto;
  margin-top: 8px;
}
.picker-item {
  width: 100%;
  min-height: 50px;
  padding: 10px;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--text);
  text-align: left;
  font-size: 13px;
}
.picker-item.is-active {
  background: #f5f8ff;
}
.preview-body {
  flex: 1;
  overflow-y: auto;
}
.preview-row {
  padding: 10px;
  margin-bottom: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fbfcfe;
}
.sheet-actions {
  display: flex;
  gap: 10px;
  margin-top: 10px;
}
</style>
