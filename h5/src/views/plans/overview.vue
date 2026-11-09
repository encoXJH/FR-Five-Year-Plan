<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showDialog, showToast } from 'vant'
import StatusTag from '@/components/StatusTag.vue'
import ReasonDialog from '@/components/ReasonDialog.vue'
import PageState from '@/components/PageState.vue'
import { activatePlan, activatePlanYear, archivePlan, getPlan } from '@/api/plan'
import { finalSettle, getYearSettlement, settleYear, unlockYear } from '@/api/settlement'
import { completeMakeup, listTaskMakeups } from '@/api/makeup'
import { createMilestone, deleteMilestone, listMilestones, updateMilestone } from '@/api/milestone'
import { listIndicators } from '@/api/indicator'
import { useUserStore } from '@/stores/user'
import { newIdempotencyKey } from '@/utils/idempotency'
import { formatDate, formatPercent } from '@/utils/format'
import { enumOptions } from '@/utils/enumLabels'
import { setCurrentPlan } from '@/utils/currentPlan'
import type { PlanDetail, PlanYear } from '@/types/plan'
import type { Milestone } from '@/types/milestone'
import type { MilestoneStatus } from '@/types/enums'
import type { SettlementItem, YearSettlement } from '@/types/stats'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const planId = computed(() => route.params.planId as string)
const loading = ref(false)
const plan = ref<PlanDetail | null>(null)

const canManage = computed(() => userStore.can('plan:manage'))
const canSettle = computed(() => userStore.can('settlement:manage'))
const isArchived = computed(() => plan.value?.status === 'ARCHIVED')

const planYears = computed<number[]>(() => {
  if (!plan.value) return []
  const list: number[] = []
  for (let year = plan.value.startYear; year <= plan.value.endYear; year += 1) list.push(year)
  return list
})

async function loadPlan(): Promise<void> {
  loading.value = true
  try {
    plan.value = await getPlan(planId.value)
    if (plan.value) setCurrentPlan(plan.value)
  } finally {
    loading.value = false
  }
}

async function handleActivate(): Promise<void> {
  try {
    await showConfirmDialog({
      title: '启动计划',
      message: '启动后计划进入「执行中」，学院可按年度填报。确认启动？',
      confirmButtonText: '确认启动',
    })
  } catch {
    return
  }
  if (!plan.value) return
  plan.value = await activatePlan(planId.value, plan.value.version)
  showToast('计划已启动')
}

const activatingYear = ref<number | null>(null)
const settlingYear = ref<number | null>(null)
const unlockingYear = ref<number | null>(null)
const finalSettling = ref(false)

async function handleActivateYear(year: PlanYear): Promise<void> {
  try {
    await showConfirmDialog({
      title: `激活年度 · ${year.year}`,
      message: `激活后，${year.year} 年已下发任务的年度状态将转为「进行中」。确认激活？`,
      confirmButtonText: '确认激活',
    })
  } catch {
    return
  }
  if (!plan.value) return
  activatingYear.value = year.year
  try {
    await activatePlanYear(planId.value, year.year, plan.value.version, newIdempotencyKey())
    showToast(`${year.year} 年度已激活`)
    await loadPlan()
  } finally {
    activatingYear.value = null
  }
}

async function handleSettleYear(year: PlanYear): Promise<void> {
  try {
    await showConfirmDialog({
      title: `年度核算 · ${year.year}`,
      message: `将锁定 ${year.year} 年度数据并生成核算结果：锁定后该年度的填报、审核、分解与调整全部只读，未达标任务将生成「待补做」记录。确认核算？`,
      confirmButtonText: '确认核算',
    })
  } catch {
    return
  }
  if (!plan.value) return
  settlingYear.value = year.year
  try {
    await settleYear(planId.value, year.year, plan.value.version, newIdempotencyKey())
    showToast(`${year.year} 年度核算完成`)
    await loadPlan()
    settlementYear.value = year.year
    await loadSettlement()
  } finally {
    settlingYear.value = null
  }
}

const unlockVisible = ref(false)
const unlockTargetYear = ref<number | null>(null)

function openUnlockDialog(year: PlanYear): void {
  unlockTargetYear.value = year.year
  unlockVisible.value = true
}

async function submitUnlock(reason: string): Promise<void> {
  if (!plan.value || unlockTargetYear.value == null) return
  unlockingYear.value = unlockTargetYear.value
  try {
    await unlockYear(planId.value, unlockTargetYear.value, reason, plan.value.version, newIdempotencyKey())
    showToast(`${unlockTargetYear.value} 年度已解锁`)
    unlockVisible.value = false
    await loadPlan()
  } finally {
    unlockingYear.value = null
  }
}

const allYearsLocked = computed(
  () => (plan.value?.years.length ?? 0) > 0 && plan.value!.years.every((y) => y.status === 'LOCKED'),
)

async function handleFinalSettle(): Promise<void> {
  try {
    await showConfirmDialog({
      title: '五年核算',
      message: `五年核算将汇总 ${plan.value?.startYear}–${plan.value?.endYear} 全部年度数据，生成五年目标达成结论。确认执行？`,
      confirmButtonText: '确认核算',
    })
  } catch {
    return
  }
  if (!plan.value) return
  finalSettling.value = true
  try {
    const result = await finalSettle(planId.value, plan.value.version, newIdempotencyKey())
    await showDialog({
      title: '五年核算完成',
      message: `共 ${result.indicatorCount} 项指标，达标 ${result.achievedCount} 项，未达标 ${result.indicatorCount - result.achievedCount} 项。`,
    })
    await loadPlan()
  } finally {
    finalSettling.value = false
  }
}

const lockedYears = computed(
  () => plan.value?.years.filter((y) => y.status === 'LOCKED').map((y) => y.year) ?? [],
)
const settlementYear = ref<number | null>(null)
const settlement = ref<YearSettlement | null>(null)
const settlementLoading = ref(false)
const settlementPicker = ref(false)

async function loadSettlement(): Promise<void> {
  if (settlementYear.value == null) {
    settlement.value = null
    return
  }
  settlementLoading.value = true
  try {
    settlement.value = await getYearSettlement(planId.value, settlementYear.value)
  } catch {
    settlement.value = null
  } finally {
    settlementLoading.value = false
  }
}

const settlementSummary = computed(() => {
  const items = settlement.value?.items ?? []
  return {
    total: items.length,
    achieved: items.filter((i) => i.isAchieved === true).length,
    notAchieved: items.filter((i) => i.isAchieved === false).length,
    makeupPending: items.filter((i) => i.makeupStatus === 'PENDING').length,
    madeUp: items.filter((i) => i.makeupStatus === 'DONE').length,
  }
})

const completingMakeupId = ref<string | null>(null)
const canCompleteMakeup = computed(() => userStore.can('submission:submit'))

async function handleCompleteMakeup(row: SettlementItem): Promise<void> {
  if (!row.makeupId || row.makeupStatus !== 'PENDING') return
  try {
    await showConfirmDialog({
      title: '确认补做完成',
      message: `确认 ${row.taskCode} 的补做工作已完成？来源年度将同步标记为「已补做」。`,
      confirmButtonText: '确认完成',
    })
  } catch {
    return
  }
  completingMakeupId.value = row.makeupId
  try {
    const records = await listTaskMakeups(row.taskId)
    const makeup = records.find((item) => item.id === row.makeupId && item.status === 'PENDING')
    if (!makeup) {
      showToast('补做记录状态已变化，请刷新后重试')
      await loadSettlement()
      return
    }
    await completeMakeup(makeup.id, makeup.version, newIdempotencyKey())
    showToast('已标记为已补做')
    await loadSettlement()
  } finally {
    completingMakeupId.value = null
  }
}

const archiveVisible = ref(false)
const archiving = ref(false)

function openArchive(): void {
  archiveVisible.value = true
}

async function submitArchive(reason: string): Promise<void> {
  if (!plan.value) return
  archiving.value = true
  try {
    plan.value = await archivePlan(planId.value, plan.value.version, reason)
    showToast('计划已归档')
    archiveVisible.value = false
  } finally {
    archiving.value = false
  }
}

// ---------- 里程碑 ----------
const indicatorOptions = ref<{ id: string; label: string }[]>([])
const selectedIndicatorId = ref('')
const milestoneLoading = ref(false)
const milestones = ref<Milestone[]>([])
let indicatorSequence = 0
let milestoneSequence = 0

const canEditMilestone = computed(
  () => !isArchived.value && userStore.can('indicator:edit', 'submission:submit'),
)

async function loadIndicatorOptions(): Promise<void> {
  const sequence = ++indicatorSequence
  const currentPlanId = planId.value
  milestones.value = []
  selectedIndicatorId.value = ''
  try {
    const tree = await listIndicators(currentPlanId)
    if (sequence !== indicatorSequence || currentPlanId !== planId.value) return
    indicatorOptions.value = tree.map((node) => ({ id: node.id, label: `${node.code} ${node.name}` }))
    if (indicatorOptions.value.length > 0) {
      selectedIndicatorId.value = indicatorOptions.value[0].id
      await loadMilestones()
    }
  } catch {
    // 拦截器已提示
  }
}

const sortedMilestones = computed(() =>
  [...milestones.value].sort((a, b) => (a.dueDate ?? '').localeCompare(b.dueDate ?? '')),
)

async function loadMilestones(): Promise<void> {
  const indicatorId = selectedIndicatorId.value
  const sequence = ++milestoneSequence
  if (!indicatorId) {
    milestones.value = []
    return
  }
  milestoneLoading.value = true
  try {
    const result = await listMilestones({ indicatorId })
    if (sequence === milestoneSequence && indicatorId === selectedIndicatorId.value) {
      milestones.value = result
    }
  } finally {
    if (sequence === milestoneSequence) milestoneLoading.value = false
  }
}

const indicatorPickerVisible = ref(false)
const milestoneVisible = ref(false)
const milestoneSaving = ref(false)
const editingMilestoneId = ref<string | null>(null)
const yearPickerVisible = ref(false)
const statusPickerVisible = ref(false)
const datePickerVisible = ref(false)
const statusOptions = computed(() => enumOptions('MilestoneStatus', userStore.enums))

const milestoneForm = reactive({
  name: '',
  year: null as number | null,
  dueDate: '',
  deliverable: '',
  status: 'NOT_STARTED' as MilestoneStatus,
  version: 0,
})

function openCreateMilestone(): void {
  editingMilestoneId.value = null
  milestoneForm.name = ''
  milestoneForm.year = null
  milestoneForm.dueDate = ''
  milestoneForm.deliverable = ''
  milestoneForm.status = 'NOT_STARTED'
  milestoneForm.version = 0
  milestoneVisible.value = true
}

function openEditMilestone(item: Milestone): void {
  editingMilestoneId.value = item.id
  milestoneForm.name = item.name
  milestoneForm.year = item.year ?? null
  milestoneForm.dueDate = item.dueDate ? item.dueDate.slice(0, 10) : ''
  milestoneForm.deliverable = item.deliverable ?? ''
  milestoneForm.status = item.status
  milestoneForm.version = item.version
  milestoneVisible.value = true
}

async function saveMilestone(): Promise<void> {
  if (!milestoneForm.name.trim()) {
    showToast('请填写里程碑名称')
    return
  }
  if (!milestoneForm.dueDate) {
    showToast('请选择计划完成时间')
    return
  }
  milestoneSaving.value = true
  try {
    if (editingMilestoneId.value) {
      await updateMilestone(editingMilestoneId.value, {
        name: milestoneForm.name.trim(),
        year: milestoneForm.year,
        dueDate: milestoneForm.dueDate,
        deliverable: milestoneForm.deliverable.trim() || null,
        status: milestoneForm.status,
        version: milestoneForm.version,
      })
      showToast('里程碑已更新')
    } else {
      const indicatorId = selectedIndicatorId.value
      if (!indicatorId || !indicatorOptions.value.some((item) => item.id === indicatorId)) {
        showToast('当前计划指标已变化，请重新选择')
        milestoneVisible.value = false
        return
      }
      await createMilestone({
        indicatorId,
        name: milestoneForm.name.trim(),
        year: milestoneForm.year,
        dueDate: milestoneForm.dueDate,
        deliverable: milestoneForm.deliverable.trim() || null,
        status: milestoneForm.status,
      })
      showToast('里程碑已创建')
    }
    milestoneVisible.value = false
    await loadMilestones()
  } finally {
    milestoneSaving.value = false
  }
}

async function removeMilestone(item: Milestone): Promise<void> {
  try {
    await showConfirmDialog({
      title: '删除里程碑',
      message: `确认删除里程碑「${item.name}」？`,
      confirmButtonText: '删除',
    })
  } catch {
    return
  }
  await deleteMilestone(item.id, item.version)
  showToast('已删除')
  await loadMilestones()
}

const selectedIndicatorLabel = computed(
  () => indicatorOptions.value.find((item) => item.id === selectedIndicatorId.value)?.label ?? '',
)

const statusLabel = computed(
  () => statusOptions.value.find((item) => item.value === milestoneForm.status)?.label ?? '',
)

watch(
  planId,
  async () => {
    await loadPlan()
    settlementYear.value = lockedYears.value[0] ?? null
    await loadSettlement()
    await loadIndicatorOptions()
  },
  { immediate: true },
)

const modules = computed(() => {
  const id = planId.value
  const list = [
    { label: '指标总表', icon: 'apps-o', path: `/plans/${id}/indicators`, permission: '' },
    { label: '分解下发', icon: 'cluster-o', path: `/plans/${id}/allocation`, permission: 'allocation:edit' },
    { label: '指标进度', icon: 'chart-trending-o', path: `/plans/${id}/indicator-progress`, permission: '' },
    { label: '统计报表', icon: 'bar-chart-o', path: '/stats', permission: 'stats:view' },
    { label: '材料库', icon: 'folder-o', path: '/materials', permission: 'material:view' },
    { label: '可视化大屏', icon: 'desktop-o', path: `/screen/${id}`, permission: 'screen:view' },
  ]
  return list.filter((item) => !item.permission || userStore.can(item.permission))
})
</script>

<template>
  <div class="page">
    <PageState :loading="loading && !plan" loading-text="加载计划…">
      <template v-if="plan">
        <div v-if="isArchived" class="archived-tip">
          <van-icon name="info-o" /> 该计划已归档，只能查看
        </div>

        <div class="plan-hero">
          <div class="row--between">
            <h1 class="plan-title">{{ plan.name }}</h1>
            <StatusTag kind="PlanStatus" :value="plan.status" />
          </div>
          <p class="plan-meta">
            周期 {{ plan.startYear }}–{{ plan.endYear }} · 基线 {{ plan.baselineYear }}
            <template v-if="plan.settleDeadline"> · 核算截止 {{ plan.settleDeadline }}</template>
          </p>
          <div v-if="canManage && !isArchived" class="plan-actions">
            <van-button
              v-if="plan.status === 'DRAFT'"
              size="small"
              type="primary"
              @click="handleActivate"
            >启动执行</van-button>
            <van-button size="small" plain type="danger" @click="openArchive">归档</van-button>
          </div>
        </div>

        <div class="module-grid">
          <button
            v-for="item in modules"
            :key="item.label"
            type="button"
            class="module-item"
            @click="router.push(item.path)"
          >
            <van-icon :name="item.icon" size="22" />
            <span>{{ item.label }}</span>
          </button>
        </div>

        <div v-if="plan.years && plan.years.length > 0" class="card">
          <div class="card-title">
            <span>年度核算管理</span>
            <van-button
              v-if="canSettle && !isArchived"
              size="mini"
              :type="allYearsLocked ? 'primary' : 'default'"
              :disabled="!allYearsLocked"
              :loading="finalSettling"
              @click="handleFinalSettle"
            >五年核算</van-button>
          </div>
          <p v-if="canSettle && !isArchived && !allYearsLocked" class="small muted year-hint">
            全部年度核算锁定后开放五年核算
          </p>
          <div class="year-list">
            <div v-for="year in plan.years" :key="year.year" class="year-item">
              <div class="year-main">
                <div class="row">
                  <strong>{{ year.year }} 年</strong>
                  <StatusTag kind="PlanYearStatus" :value="year.status" />
                </div>
                <div v-if="year.status === 'LOCKED' && year.settledAt" class="small muted">
                  核算于 {{ formatDate(year.settledAt) }}
                </div>
              </div>
              <div v-if="canSettle && !isArchived" class="year-actions">
                <van-button
                  v-if="year.status === 'NOT_ACTIVATED'"
                  size="mini"
                  type="primary"
                  plain
                  :loading="activatingYear === year.year"
                  @click="handleActivateYear(year)"
                >激活</van-button>
                <van-button
                  v-if="year.status === 'ACTIVE'"
                  size="mini"
                  type="warning"
                  plain
                  :loading="settlingYear === year.year"
                  @click="handleSettleYear(year)"
                >核算</van-button>
                <van-button
                  v-if="year.status === 'LOCKED'"
                  size="mini"
                  type="danger"
                  plain
                  @click="openUnlockDialog(year)"
                >解锁</van-button>
              </div>
            </div>
          </div>
        </div>

        <div v-if="lockedYears.length > 0 || settlement" class="card">
          <div class="card-title">
            <span>年度核算结果</span>
            <button type="button" class="card-title__extra" @click="settlementPicker = true">
              {{ settlementYear ? `${settlementYear} 年` : '选择年度' }}
              <van-icon name="arrow-down" size="11" />
            </button>
          </div>
          <div v-if="settlementLoading" class="skeleton-block">加载中…</div>
          <template v-else-if="settlement">
            <div class="settle-summary">
              <span class="chip chip--success">达标 {{ settlementSummary.achieved }}</span>
              <span class="chip chip--danger">未达标 {{ settlementSummary.notAchieved }}</span>
              <span class="chip chip--danger">待补做 {{ settlementSummary.makeupPending }}</span>
              <span class="chip chip--success">已补做 {{ settlementSummary.madeUp }}</span>
              <span class="small muted">共 {{ settlementSummary.total }} 条任务</span>
            </div>
            <div class="settle-list">
              <div v-for="row in settlement.items" :key="row.taskId" class="settle-item">
                <div class="row--between">
                  <strong>{{ row.taskCode }}</strong>
                  <StatusTag kind="TaskYearStatus" :value="row.yearStatus" />
                </div>
                <div class="small muted ellipsis">{{ row.indicator?.name ?? '—' }} · {{ row.unit?.name ?? '—' }}</div>
                <div class="settle-numbers">
                  <span>完成值 {{ row.actualValue ?? '—' }}</span>
                  <span>完成率 {{ formatPercent(row.completionRate) }}</span>
                </div>
                <div v-if="row.makeupStatus === 'PENDING'" class="settle-makeup">
                  <span>待补做 {{ row.makeupGap ?? '—' }}</span>
                  <van-button
                    v-if="canCompleteMakeup"
                    size="mini"
                    type="primary"
                    plain
                    :loading="completingMakeupId === row.makeupId"
                    @click="handleCompleteMakeup(row)"
                  >确认补做完成</van-button>
                </div>
                <div v-else-if="row.makeupStatus === 'DONE'" class="small text-success">已补做</div>
              </div>
            </div>
          </template>
          <div v-else class="empty-state">选择已核算年度查看结果</div>
        </div>

        <div class="card">
          <div class="card-title"><span>里程碑管理</span></div>
          <button
            type="button"
            class="selector-button"
            @click="indicatorPickerVisible = true"
          >
            <span class="ellipsis">{{ selectedIndicatorLabel || '选择指标查看里程碑' }}</span>
            <van-icon name="arrow-down" size="12" />
          </button>
          <div v-if="milestoneLoading" class="skeleton-block">加载中…</div>
          <template v-else-if="sortedMilestones.length > 0">
            <div class="milestone-list">
              <div v-for="item in sortedMilestones" :key="item.id" class="milestone-item">
                <div class="milestone-line">
                  <span class="milestone-dot" />
                  <span class="small muted">{{ formatDate(item.dueDate) }}</span>
                  <StatusTag kind="MilestoneStatus" :value="item.status" />
                </div>
                <div class="milestone-name">{{ item.name }}</div>
                <div v-if="item.year" class="small muted">{{ item.year }} 年目标</div>
                <div v-if="item.deliverable" class="small muted">交付物：{{ item.deliverable }}</div>
                <div v-if="canEditMilestone" class="milestone-actions">
                  <button type="button" class="text-btn" @click="openEditMilestone(item)">编辑</button>
                  <button type="button" class="text-btn text-danger" @click="removeMilestone(item)">删除</button>
                </div>
              </div>
            </div>
          </template>
          <div v-else class="empty-state">
            {{ selectedIndicatorId ? '该指标暂无里程碑' : '请先选择指标' }}
          </div>
          <van-button
            v-if="canEditMilestone"
            block
            type="primary"
            plain
            :disabled="!selectedIndicatorId"
            class="milestone-add"
            @click="openCreateMilestone"
          >新增里程碑</van-button>
        </div>
      </template>
    </PageState>

    <van-popup v-model:show="settlementPicker" position="bottom" round>
      <van-picker
        :columns="lockedYears.map((year) => ({ text: `${year} 年`, value: year }))"
        @confirm="(value: { selectedOptions: Array<{ text: string; value: number }> }) => {
          settlementYear = value.selectedOptions[0]?.value ?? null
          settlementPicker = false
          loadSettlement()
        }"
        @cancel="settlementPicker = false"
      />
    </van-popup>

    <van-popup v-model:show="indicatorPickerVisible" position="bottom" round>
      <van-picker
        :columns="indicatorOptions.map((item) => ({ text: item.label, value: item.id }))"
        @confirm="(value: { selectedOptions: Array<{ text: string; value: string }> }) => {
          selectedIndicatorId = value.selectedOptions[0]?.value ?? ''
          indicatorPickerVisible = false
          loadMilestones()
        }"
        @cancel="indicatorPickerVisible = false"
      />
    </van-popup>

    <van-popup v-model:show="milestoneVisible" position="bottom" round :style="{ maxHeight: '86vh' }">
      <div class="milestone-sheet">
        <div class="sheet-head">
          <strong>{{ editingMilestoneId ? '编辑里程碑' : '新增里程碑' }}</strong>
          <button type="button" class="close-btn" @click="milestoneVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="field">
          <label>名称<span class="required">*</span></label>
          <van-field v-model="milestoneForm.name" maxlength="100" show-word-limit />
        </div>
        <div class="field">
          <label>计划完成时间<span class="required">*</span></label>
          <van-field
            readonly
            is-link
            :model-value="milestoneForm.dueDate"
            placeholder="选择日期"
            @click="datePickerVisible = true"
          />
        </div>
        <div class="field">
          <label>关联年度</label>
          <van-field
            readonly
            is-link
            :model-value="milestoneForm.year ? `${milestoneForm.year} 年` : ''"
            placeholder="选填"
            @click="yearPickerVisible = true"
          />
        </div>
        <div class="field">
          <label>交付物要求</label>
          <van-field v-model="milestoneForm.deliverable" type="textarea" rows="2" maxlength="500" show-word-limit />
        </div>
        <div class="field">
          <label>状态</label>
          <van-field readonly is-link :model-value="statusLabel" @click="statusPickerVisible = true" />
        </div>
        <van-button block type="primary" :loading="milestoneSaving" @click="saveMilestone">保存</van-button>
      </div>
    </van-popup>

    <van-popup v-model:show="yearPickerVisible" position="bottom" round>
      <van-picker
        :columns="[{ text: '不关联', value: 0 }, ...planYears.map((year) => ({ text: `${year} 年`, value: year }))]"
        @confirm="(value: { selectedOptions: Array<{ text: string; value: number }> }) => {
          milestoneForm.year = value.selectedOptions[0]?.value || null
          yearPickerVisible = false
        }"
        @cancel="yearPickerVisible = false"
      />
    </van-popup>

    <van-popup v-model:show="statusPickerVisible" position="bottom" round>
      <van-picker
        :columns="statusOptions.map((item) => ({ text: item.label, value: item.value }))"
        @confirm="(value: { selectedOptions: Array<{ text: string; value: string }> }) => {
          milestoneForm.status = (value.selectedOptions[0]?.value ?? 'NOT_STARTED') as MilestoneStatus
          statusPickerVisible = false
        }"
        @cancel="statusPickerVisible = false"
      />
    </van-popup>

    <van-popup v-model:show="datePickerVisible" position="bottom" round>
      <van-date-picker
        :min-date="new Date(2000, 0, 1)"
        :max-date="new Date(2100, 11, 31)"
        @confirm="(value: { selectedValues: string[] }) => {
          milestoneForm.dueDate = value.selectedValues.join('-')
          datePickerVisible = false
        }"
        @cancel="datePickerVisible = false"
      />
    </van-popup>

    <ReasonDialog
      v-model="unlockVisible"
      :title="`解锁 ${unlockTargetYear ?? ''} 年度`"
      label="解锁原因"
      confirm-text="确认解锁"
      :loading="unlockingYear !== null"
      @confirm="submitUnlock"
    />
    <ReasonDialog
      v-model="archiveVisible"
      title="归档计划"
      label="归档原因"
      confirm-text="确认归档"
      danger
      :loading="archiving"
      @confirm="submitArchive"
    />
  </div>
</template>

<style scoped>
.archived-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f1f3f7;
  color: var(--text-secondary);
  font-size: 12px;
}
.plan-hero {
  padding: 16px;
  border-radius: var(--radius);
  background: linear-gradient(135deg, #2b5bc0, #1f4699);
  color: #fff;
  margin-bottom: 12px;
}
.plan-title {
  margin: 0;
  font-size: 20px;
  line-height: 27px;
  overflow-wrap: anywhere;
}
.plan-meta {
  margin: 6px 0 0;
  font-size: 12px;
  opacity: 0.82;
}
.plan-actions {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}
.module-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 12px;
}
.module-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  min-height: 74px;
  padding: 12px 4px;
  border: none;
  border-radius: 12px;
  background: #fff;
  color: var(--brand);
  font-size: 12px;
  box-shadow: var(--shadow);
}
.year-hint {
  margin: -4px 0 8px;
}
.year-list {
  display: flex;
  flex-direction: column;
}
.year-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 54px;
  padding: 8px 0;
  border-bottom: 1px solid var(--line);
}
.year-item:last-child {
  border-bottom: none;
}
.year-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.year-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}
.settle-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.settle-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.settle-item {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
}
.settle-numbers {
  display: flex;
  gap: 14px;
  margin-top: 4px;
  color: var(--text-secondary);
  font-size: 12px;
}
.settle-makeup {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
  color: var(--text-secondary);
  font-size: 12px;
}
.selector-button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  min-height: 42px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  color: var(--text);
  font-size: 13px;
  text-align: left;
}
.milestone-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
}
.milestone-item {
  position: relative;
  padding: 0 0 10px 16px;
  border-left: 2px solid #dfe7f3;
}
.milestone-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.milestone-dot {
  position: absolute;
  left: -5px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--brand);
}
.milestone-name {
  margin-top: 4px;
  font-size: 14px;
  font-weight: 600;
}
.milestone-actions {
  display: flex;
  gap: 12px;
  margin-top: 4px;
}
.text-btn {
  min-height: 30px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--brand);
  font-size: 12px;
}
.milestone-add {
  margin-top: 12px;
}
.milestone-sheet {
  padding: 16px 16px calc(16px + var(--safe-bottom));
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
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
.field {
  margin-bottom: 10px;
}
.field label {
  display: block;
  margin-bottom: 4px;
  color: var(--text-secondary);
  font-size: 13px;
}
.required {
  color: var(--danger);
}
.field :deep(.van-field) {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  padding: 10px 12px;
}
</style>
