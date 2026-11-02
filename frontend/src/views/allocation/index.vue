<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CircleCheck, Warning } from '@element-plus/icons-vue'
import StatusTag from '@/components/StatusTag.vue'
import BackButton from '@/components/BackButton.vue'
import AllocationMatrixEditor from './components/AllocationMatrixEditor.vue'
import DistributePreviewDialog from './components/DistributePreviewDialog.vue'
import type { PreviewRow } from './components/DistributePreviewDialog.vue'
import { getPlan, listPlans } from '@/api/plan'
import { listIndicators } from '@/api/indicator'
import { getUserOptions } from '@/api/user'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { distributePlan, getAllocation } from '@/api/allocation'
import { ApiError } from '@/utils/request'
import { newIdempotencyKey } from '@/utils/idempotency'
import { enumLabel } from '@/utils/enumLabels'
import { formatTargetValue, sumYearTargets } from '@/utils/indicator'
import {
  canAssignNewB,
  isPendingAllocationRow,
  rowBooleanPlanYear,
  rowHasTarget,
  rowTotalTarget,
} from '@/utils/allocation'
import type { AllocationEditorState } from '@/utils/allocation'
import type { Allocation } from '@/types/allocation'
import type { PlanDetail } from '@/types/plan'
import type { IndicatorTreeNode, YearTarget } from '@/types/indicator'
import type { IndicatorStatus, StatCaliber } from '@/types/enums'
import type { UserOption } from '@/types/user'

/** 待分解的可量化 A；children 仅用于识别历史分解数据。 */
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

const props = withDefaults(defineProps<{
  embedded?: boolean
  planId?: string
  initialIndicatorId?: string
}>(), {
  embedded: false,
  planId: '',
  initialIndicatorId: '',
})
const emit = defineEmits<{
  changed: [indicatorId: string]
}>()

const route = useRoute()
const router = useRouter()
const planId = computed(() => props.planId || (route.params.planId as string) || (route.query.planId as string) || '')
const loading = ref(false)
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

const current = computed<AllocatableItem | null>(
  () => allocatable.value.find((item) => item.id === selectedId.value) ?? null,
)
const currentReadOnly = computed(() =>
  current.value?.isLegacy === true || plan.value?.status === 'ARCHIVED',
)

const editorRef = ref<InstanceType<typeof AllocationMatrixEditor>>()
const editorState = ref<AllocationEditorState>({
  indicatorId: '',
  rows: [],
  dirty: false,
  balanced: true,
  version: 0,
  serverBalanced: null,
  loading: false,
  saving: false,
  canDistribute: false,
  distributeBlockReasons: [],
  hasAnyTarget: false,
  missingOwners: [],
  ineligibleTargetRows: [],
  summary: [],
  allocatedFiveYear: 0,
  fiveYearDiff: null,
  lastSavedAt: null,
})

function onEditorState(state: AllocationEditorState): void {
  editorState.value = state
}

function cacheAllocation(allocation: Allocation): void {
  allocationCache.set(allocation.indicatorId, allocation)
  balanceMap.value = { ...balanceMap.value, [allocation.indicatorId]: allocation.isBalanced }
}

function onAllocationSaved(allocation: Allocation): void {
  cacheAllocation(allocation)
  emit('changed', allocation.indicatorId)
}

const canDistribute = computed(() =>
  current.value !== null
    && !currentReadOnly.value
    && editorState.value.indicatorId === selectedId.value
    && editorState.value.canDistribute,
)
const distributeEnabled = computed(() => canDistribute.value && !editorState.value.dirty)
const distributeTooltip = computed(() =>
  editorState.value.dirty
    ? '有未保存的修改，请先存草稿'
    : editorState.value.distributeBlockReasons.join('；'),
)

const balanceMap = ref<Record<string, boolean>>({})
const balanceLoaded = ref(false)
const balanceScanning = ref(false)
const allocationCache = new Map<string, Allocation>()

async function loadBalanceStates(): Promise<void> {
  balanceScanning.value = true
  try {
    const pending = allocatable.value.filter((item) => !item.isLegacy)
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

async function selectIndicator(id: string, force = false): Promise<void> {
  if (!force && editorState.value.dirty) {
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
  await nextTick()
  const cached = force ? undefined : allocationCache.get(id)
  await editorRef.value?.load(id, cached)
}

const previewVisible = ref(false)
const distributing = ref(false)
const distributeKey = ref(newIdempotencyKey())
const batchDistributeKey = ref(newIdempotencyKey())
const assignableUnitIds = computed(() =>
  new Set(unitOptions.value.filter((unit) => canAssignNewB(unit)).map((unit) => unit.id)),
)
const previewRows = computed<PreviewRow[]>(() => {
  if (!current.value) return []
  const ownerNameOf = (id: string | null) =>
    id ? (userOptions.value.find((user) => user.id === id)?.name ?? null) : null
  return editorState.value.rows
    .filter((row) =>
      isPendingAllocationRow(row) && assignableUnitIds.value.has(row.unitId) && rowHasTarget(row.targets),
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
  if (!canDistribute.value || editorState.value.dirty) return
  previewVisible.value = true
}

function showDistributeError(error: unknown): void {
  if (error instanceof ApiError && [10001, 40002, 40003, 50001, 50002].includes(error.code)) {
    const lines = error.fieldErrors.map((item) => item.message)
    ElMessageBox.alert(
      lines.length > 0 ? lines.join('\n') : error.message,
      `${error.message}（${error.code}）`,
      { confirmButtonText: '返回修改', type: 'error' },
    )
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
    ElMessage.success(`「${current.value.code}」已正式交给学院，学院任务已生成并通知负责人`)
    previewVisible.value = false
    await loadTree()
    await selectIndicator(selectedId.value, true)
    emit('changed', current.value.id)
  } catch (error) {
    showDistributeError(error)
  } finally {
    distributing.value = false
  }
}

const batchDistributing = ref(false)
const balancedPending = computed(() =>
  allocatable.value.filter((item) => {
    const allocation = allocationCache.get(item.id)
    return !item.isLegacy
      && balanceMap.value[item.id] === true
      && allocation?.rows.some((row) =>
        !row.isHistorical && row.taskStatus === 'PENDING_DISTRIBUTION') === true
  }),
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
      `将把以下 ${ready.length} 个已平衡的指标正式交给学院：${ready.map((item) => item.code).join('、')}。确认后生成 学院任务并通知学院负责人，任务编号与负责人不可修改。`,
      '批量正式交给学院',
      { confirmButtonText: '确认交给学院', cancelButtonText: '取消' },
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
    ElMessage.success(`已将 ${ready.length} 个指标正式交给学院`)
    await loadTree()
    if (selectedId.value) await selectIndicator(selectedId.value, true)
    if (ready[0]) emit('changed', ready[0].id)
  } catch (error) {
    showDistributeError(error)
  } finally {
    batchDistributing.value = false
  }
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
    const queryId = props.initialIndicatorId || route.query.indicatorId as string | undefined
    const first = allocatable.value.find((item) => item.id === queryId)
      ?? allocatable.value.find((item) => queryId !== undefined && item.legacyChildIds.includes(queryId))
      ?? allocatable.value[0]
    if (first) await selectIndicator(first.id, true)
  } finally {
    loading.value = false
  }
}

async function redirectToActivePlan(): Promise<void> {
  try {
    const plans = await listPlans()
    const active = plans.find((p) => p.status === 'ACTIVE') ?? plans[0]
    if (active) await router.replace(`/plans/${active.id}/allocation`)
  } catch {
    // 拉取失败保持空状态引导
  }
}

watch(planId, (id) => {
  if (id) loadAll()
}, { immediate: true })

onMounted(() => {
  if (!planId.value) {
    redirectToActivePlan()
    return
  }
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
</script>

<template>
  <BackButton v-if="!embedded" />
  <el-empty v-if="!planId" description="请先从首页选择一个计划" />

  <div v-else v-loading="loading" class="allocation-page" :class="{ 'is-embedded': embedded }">
    <div class="page-header">
      <div>
        <h1 v-if="!embedded" class="page-title">任务分解</h1>
        <p class="page-subtitle">{{ plan?.name ?? '' }}</p>
      </div>
    </div>
    <div class="app-action-row header-actions">
      <el-tooltip :disabled="distributeEnabled" :content="distributeTooltip" placement="top">
        <span>
          <el-button type="primary" :disabled="!distributeEnabled" @click="openPreview">
            正式交给学院
          </el-button>
        </span>
      </el-tooltip>
      <el-button
        :loading="batchDistributing || balanceScanning"
        :disabled="plan?.status === 'ARCHIVED'"
        @click="handleBatchDistribute"
      >
        批量正式交给学院
      </el-button>
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
              <el-tooltip v-else-if="balanceMap[item.id] === true" content="已平衡，可下发" placement="top">
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

      <div v-if="current" class="matrix-panel">
        <el-card shadow="never" class="info-card">
          <div class="indicator-info">
            <StatusTag kind="IndicatorLevel" value="A" />
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

        <AllocationMatrixEditor
          ref="editorRef"
          :years="years"
          :stat-caliber="current.statCaliber"
          :year-targets="current.yearTargets"
          :total-target="current.totalTarget"
          :user-options="userOptions"
          :unit-options="unitOptions"
          :legacy="current.isLegacy"
          :distributed="current.status === 'DISTRIBUTED'"
          :archived="plan?.status === 'ARCHIVED'"
          @state-change="onEditorState"
          @loaded="cacheAllocation"
          @saved="onAllocationSaved"
        />
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
.allocation-page.is-embedded {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.is-embedded .page-header {
  flex-shrink: 0;
  margin-bottom: var(--space-3);
}

.is-embedded .allocation-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.is-embedded .indicator-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.is-embedded .indicator-panel :deep(.el-card__body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.is-embedded .panel-list {
  flex: 1;
  max-height: none;
}

.is-embedded .matrix-panel {
  height: 100%;
  min-height: 0;
  padding-right: 2px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

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

.indicator-item:hover,
.indicator-item.active {
  background-color: var(--el-color-primary-light-9);
}

.indicator-item.active {
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

.info-code,
.info-name {
  font-weight: 600;
}

.info-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.mono {
  font-family: var(--app-font-mono);
}

@media (max-width: 900px) {
  .allocation-body {
    flex-direction: column;
  }

  .is-embedded .allocation-body {
    overflow-y: auto;
    overscroll-behavior: contain;
  }

  .is-embedded .indicator-panel {
    height: auto;
    max-height: 280px;
  }

  .is-embedded .matrix-panel {
    height: auto;
    overflow: visible;
  }

  .indicator-panel,
  .matrix-panel {
    width: 100%;
  }

  .panel-list {
    max-height: 280px;
  }

  .header-actions {
    flex-wrap: wrap;
  }
}

@media (max-width: 768px) {
  .page-header {
    align-items: stretch;
    flex-direction: column;
    gap: var(--space-3);
  }

  .header-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    width: 100%;
  }

  .header-actions > .el-button,
  .header-actions > span,
  .header-actions .el-button {
    width: 100%;
    min-height: 44px;
    margin: 0;
  }
}
</style>
