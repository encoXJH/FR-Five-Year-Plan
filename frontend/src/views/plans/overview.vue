<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import BackButton from '@/components/BackButton.vue'
import ReasonDialog from '@/components/ReasonDialog.vue'
import StatusTag from '@/components/StatusTag.vue'
import MilestonePanel from './components/MilestonePanel.vue'
import { activatePlan, activatePlanYear, archivePlan, getPlan } from '@/api/plan'
import { finalSettle, getYearSettlement, settleYear, unlockYear } from '@/api/settlement'
import { completeMakeup, listTaskMakeups } from '@/api/makeup'
import { useUserStore } from '@/stores/user'
import { newIdempotencyKey } from '@/utils/idempotency'
import { formatPercent } from '@/utils/format'
import { setCurrentPlan } from '@/utils/currentPlan'
import type { PlanDetail, PlanYear } from '@/types/plan'
import type { SettlementItem, YearSettlement } from '@/types/stats'

const route = useRoute()
const userStore = useUserStore()

const planId = computed(() => route.params.planId as string)
const loading = ref(false)
const plan = ref<PlanDetail | null>(null)

const canManage = computed(() => userStore.can('plan:manage'))
const canSettle = computed(() => userStore.can('settlement:manage'))
const isArchived = computed(() => plan.value?.status === 'ARCHIVED')

const viewportWidth = ref(typeof window === 'undefined' ? 1440 : window.innerWidth)
const stepDirection = computed<'horizontal' | 'vertical'>(() =>
  viewportWidth.value <= 768 ? 'vertical' : 'horizontal',
)

function syncViewportWidth(): void {
  viewportWidth.value = window.innerWidth
}

onMounted(() => window.addEventListener('resize', syncViewportWidth, { passive: true }))
onBeforeUnmount(() => window.removeEventListener('resize', syncViewportWidth))

/** 计划年度列表（里程碑关联年度下拉） */
const planYears = computed<number[]>(() => {
  if (!plan.value) return []
  const list: number[] = []
  for (let year = plan.value.startYear; year <= plan.value.endYear; year += 1) {
    list.push(year)
  }
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
  await ElMessageBox.confirm('启动后计划进入「执行中」，学院可按年度填报。确认启动？', '启动计划', {
    confirmButtonText: '确认启动',
    cancelButtonText: '取消',
  })
  if (!plan.value) return
  plan.value = await activatePlan(planId.value, plan.value.version)
  ElMessage.success('计划已启动')
}

// ---------- 年度激活 / 核算 / 解锁 / 五年核算（settlement:manage） ----------
const activatingYear = ref<number | null>(null)
const settlingYear = ref<number | null>(null)
const unlockingYear = ref<number | null>(null)
const finalSettling = ref(false)

async function handleActivateYear(year: PlanYear): Promise<void> {
  await ElMessageBox.confirm(
    `激活后，${year.year} 年已下发任务的年度状态将转为「进行中」。确认激活？`,
    `激活年度 · ${year.year}`,
    { confirmButtonText: '确认激活', cancelButtonText: '取消' },
  )
  if (!plan.value) return
  activatingYear.value = year.year
  try {
    await activatePlanYear(planId.value, year.year, plan.value.version, newIdempotencyKey())
    ElMessage.success(`${year.year} 年度已激活`)
    await loadPlan()
  } finally {
    activatingYear.value = null
  }
}

async function handleSettleYear(year: PlanYear): Promise<void> {
  await ElMessageBox.confirm(
    `将锁定 ${year.year} 年度数据并生成核算结果：锁定后该年度的填报、审核、分解与调整全部只读，未达标任务将生成「待补做」记录。确认核算？`,
    `年度核算 · ${year.year}`,
    { confirmButtonText: '确认核算', cancelButtonText: '取消', type: 'warning' },
  )
  if (!plan.value) return
  settlingYear.value = year.year
  try {
    await settleYear(planId.value, year.year, plan.value.version, newIdempotencyKey())
    ElMessage.success(`${year.year} 年度核算完成`)
    await loadPlan()
    settlementYear.value = year.year
    await loadSettlement()
  } finally {
    settlingYear.value = null
  }
}

const unlockDialogVisible = ref(false)
const unlockTargetYear = ref<number | null>(null)

function openUnlockDialog(year: PlanYear): void {
  unlockTargetYear.value = year.year
  unlockDialogVisible.value = true
}

async function submitUnlock(reason: string): Promise<void> {
  if (!plan.value || unlockTargetYear.value == null) return
  unlockingYear.value = unlockTargetYear.value
  try {
    await unlockYear(
      planId.value,
      unlockTargetYear.value,
      reason,
      plan.value.version,
      newIdempotencyKey(),
    )
    ElMessage.success(`${unlockTargetYear.value} 年度已解锁`)
    unlockDialogVisible.value = false
    await loadPlan()
  } finally {
    unlockingYear.value = null
  }
}

/** 五年核算：规划期所有年度核算锁定后开放（PRD FR-M8-03） */
const allYearsLocked = computed(
  () =>
    (plan.value?.years.length ?? 0) > 0 && plan.value!.years.every((y) => y.status === 'LOCKED'),
)

async function handleFinalSettle(): Promise<void> {
  await ElMessageBox.confirm(
    `五年核算将汇总 ${plan.value?.startYear}–${plan.value?.endYear} 全部年度数据，生成五年目标达成结论。确认执行？`,
    '五年核算',
    { confirmButtonText: '确认核算', cancelButtonText: '取消', type: 'warning' },
  )
  if (!plan.value) return
  finalSettling.value = true
  try {
    const result = await finalSettle(planId.value, plan.value.version, newIdempotencyKey())
    ElMessageBox.alert(
      `五年核算完成：共 ${result.indicatorCount} 项指标，达标 ${result.achievedCount} 项，未达标 ${result.indicatorCount - result.achievedCount} 项。`,
      '五年核算完成',
      { confirmButtonText: '知道了' },
    )
    await loadPlan()
  } finally {
    finalSettling.value = false
  }
}

// ---------- 核算结果面板 ----------
const lockedYears = computed<number[]>(
  () => plan.value?.years.filter((y) => y.status === 'LOCKED').map((y) => y.year) ?? [],
)
const settlementYear = ref<number | null>(null)
const settlement = ref<YearSettlement | null>(null)
const settlementLoading = ref(false)

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

/** 核算汇总由明细行计算（后端不单独返回 summary 块） */
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
    await ElMessageBox.confirm(
      `确认 ${row.taskCode} 的补做工作已完成？来源年度将同步标记为「已补做」。`,
      '确认补做完成',
      { confirmButtonText: '确认完成', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  completingMakeupId.value = row.makeupId
  try {
    const records = await listTaskMakeups(row.taskId)
    const makeup = records.find((item) => item.id === row.makeupId && item.status === 'PENDING')
    if (!makeup) {
      ElMessage.warning('补做记录状态已变化，请刷新后重试')
      await loadSettlement()
      return
    }
    await completeMakeup(makeup.id, makeup.version, newIdempotencyKey())
    ElMessage.success('已标记为已补做')
    await loadSettlement()
  } catch {
    // 请求异常由拦截器统一提示。
  } finally {
    completingMakeupId.value = null
  }
}

async function handleArchive(): Promise<void> {
  await ElMessageBox.confirm(
    `归档后「${plan.value?.name}」只能查看，不能修改。确认归档？`,
    '归档计划',
    { confirmButtonText: '确认归档', cancelButtonText: '取消', type: 'warning' },
  )
  if (!plan.value) return
  plan.value = await archivePlan(planId.value, plan.value.version)
  ElMessage.success('计划已归档')
}

// planId 驱动加载：顶栏切换计划时组件复用不触发 onMounted，用 watch 保证切换即刷新
watch(planId, async (id) => {
  if (!id) return
  await loadPlan()
  settlementYear.value = lockedYears.value[0] ?? null
  await loadSettlement()
}, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="overview-page">
    <BackButton />
    <template v-if="plan">
      <el-alert
        v-if="isArchived"
        type="info"
        :closable="false"
        title="该计划已归档，只能查看"
        class="page-alert"
      />

      <div class="page-header">
        <div>
          <h1 class="page-title">
            {{ plan.name }}
            <StatusTag kind="PlanStatus" :value="plan.status" class="title-tag" />
          </h1>
          <p class="page-subtitle">
            周期 {{ plan.startYear }}–{{ plan.endYear }} · 基线 {{ plan.baselineYear }}
            <template v-if="plan.settleDeadline"> · 核算截止 {{ plan.settleDeadline }}</template>
          </p>
        </div>
      </div>
      <div v-if="canManage && !isArchived" class="app-action-row header-actions">
        <el-button v-if="plan.status === 'DRAFT'" type="primary" @click="handleActivate">
          启动执行
        </el-button>
        <el-button type="danger" plain @click="handleArchive">归档</el-button>
      </div>

      <el-card v-if="plan.years && plan.years.length > 0" shadow="never" class="info-card">
        <template #header>
          <div class="card-header">
            <span class="card-title">年度核算管理</span>
            <el-tooltip
              v-if="canSettle && !isArchived && !allYearsLocked"
              content="全部年度核算锁定后开放"
              placement="left"
            >
              <span>
                <el-button size="small" disabled>五年核算</el-button>
              </span>
            </el-tooltip>
            <el-button
              v-else-if="canSettle && !isArchived"
              size="small"
              type="primary"
              :loading="finalSettling"
              @click="handleFinalSettle"
            >
              五年核算
            </el-button>
          </div>
        </template>
        <el-steps align-center :direction="stepDirection" class="year-steps">
          <el-step
            v-for="year in plan.years"
            :key="year.year"
            :title="`${year.year} 年${year.status === 'LOCKED' ? ' 🔒' : ''}`"
            :status="year.status === 'LOCKED' ? 'finish' : year.status === 'ACTIVE' ? 'process' : 'wait'"
          >
            <template #description>
              <StatusTag kind="PlanYearStatus" :value="year.status" />
              <div v-if="year.status === 'LOCKED' && year.settledAt" class="year-settled-at">
                核算于 {{ year.settledAt.slice(0, 10) }}
              </div>
              <div v-if="canSettle && !isArchived" class="year-actions">
                <el-button
                  v-if="year.status === 'NOT_ACTIVATED'"
                  type="primary"
                  size="small"
                  plain
                  :loading="activatingYear === year.year"
                  @click="handleActivateYear(year)"
                >
                  激活年度
                </el-button>
                <el-button
                  v-if="year.status === 'ACTIVE'"
                  type="warning"
                  size="small"
                  plain
                  :loading="settlingYear === year.year"
                  @click="handleSettleYear(year)"
                >
                  年度核算
                </el-button>
                <el-button
                  v-if="year.status === 'LOCKED'"
                  type="danger"
                  size="small"
                  plain
                  @click="openUnlockDialog(year)"
                >
                  解锁
                </el-button>
              </div>
            </template>
          </el-step>
        </el-steps>
      </el-card>

      <el-card v-if="lockedYears.length > 0 || settlement" shadow="never" class="info-card">
        <template #header>
          <div class="card-header">
            <span class="card-title">年度核算结果</span>
            <el-select
              v-model="settlementYear"
              size="small"
              style="width: 140px"
              placeholder="选择年度"
              @change="loadSettlement"
            >
              <el-option v-for="y in lockedYears" :key="y" :label="`${y} 年`" :value="y" />
            </el-select>
          </div>
        </template>
        <div v-loading="settlementLoading">
          <template v-if="settlement">
            <div class="settle-summary">
              <span>核算时间：{{ settlement.settledAt ?? '—' }}</span>
              <span class="settle-tag"><StatusTag kind="TaskYearStatus" value="ACHIEVED" /> {{ settlementSummary.achieved }}</span>
              <span class="settle-tag"><StatusTag kind="TaskYearStatus" value="NOT_ACHIEVED" /> {{ settlementSummary.notAchieved }}</span>
              <span class="settle-tag"><StatusTag kind="TaskYearStatus" value="MAKEUP_PENDING" /> {{ settlementSummary.makeupPending }}</span>
              <span class="settle-tag"><StatusTag kind="TaskYearStatus" value="MADE_UP" /> {{ settlementSummary.madeUp }}</span>
              <span class="settle-total">共 {{ settlementSummary.total }} 条任务</span>
            </div>
            <el-table :data="settlement.items" border size="small" max-height="360">
              <el-table-column prop="taskCode" label="任务编号" width="130" />
              <el-table-column label="指标" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">{{ row.indicator?.name ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="执行单元" width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ row.unit?.name ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="完成值" width="110" align="right">
                <template #default="{ row }">{{ row.actualValue ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="完成率" width="100" align="right">
                <template #default="{ row }">{{ formatPercent(row.completionRate) }}</template>
              </el-table-column>
              <el-table-column label="年度状态" width="110" align="center">
                <template #default="{ row }">
                  <StatusTag kind="TaskYearStatus" :value="row.yearStatus" />
                </template>
              </el-table-column>
              <el-table-column label="补做" width="130" align="center">
                <template #default="{ row }">
                  <el-button
                    v-if="row.makeupStatus === 'PENDING' && canCompleteMakeup"
                    link
                    type="primary"
                    :loading="completingMakeupId === row.makeupId"
                    @click="handleCompleteMakeup(row)"
                  >
                    确认补做完成
                  </el-button>
                  <span v-else-if="row.makeupStatus === 'PENDING'">待补做 {{ row.makeupGap ?? '—' }}</span>
                  <span v-else-if="row.makeupStatus === 'DONE'">已补做</span>
                  <span v-else>—</span>
                </template>
              </el-table-column>
            </el-table>
          </template>
          <el-empty v-else-if="!settlementLoading" description="选择已核算年度查看结果" :image-size="60" />
        </div>
      </el-card>

      <el-card shadow="never" class="info-card">
        <template #header><span class="card-title">里程碑管理</span></template>
        <MilestonePanel :plan-id="planId" :years="planYears" :readonly="isArchived" />
      </el-card>
    </template>
  </div>

  <ReasonDialog
    v-model="unlockDialogVisible"
    :title="`解锁 ${unlockTargetYear ?? ''} 年度`"
    :target="`${plan?.name ?? ''} · ${unlockTargetYear ?? ''} 年度`"
    confirm-text="确认解锁"
    :loading="unlockingYear !== null"
    @confirm="submitUnlock"
  />
</template>

<style scoped>
.overview-page {
  min-width: 0;
  max-width: 100%;
}

.page-alert {
  margin-bottom: var(--space-4);
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.page-header > div {
  min-width: 0;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  overflow-wrap: anywhere;
}

.page-subtitle {
  margin: var(--space-1) 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.header-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.info-card {
  min-width: 0;
  margin-bottom: var(--space-4);
  border-radius: var(--radius-card);
}

.card-title {
  font-size: 16px;
  font-weight: 600;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.year-steps {
  min-width: 0;
}

.year-steps :deep(.el-step__main),
.year-steps :deep(.el-step__description) {
  min-width: 0;
  white-space: normal;
}

.year-settled-at {
  margin-top: var(--space-1);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.year-actions {
  margin-top: var(--space-2);
}

.settle-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
  font-variant-numeric: tabular-nums;
}

.settle-tag {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}

.settle-total {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

@media (max-width: 768px) {
  .page-header {
    align-items: stretch;
    flex-direction: column;
  }

  .header-actions {
    width: 100%;
  }

  .header-actions > .el-button {
    flex: 1 1 120px;
    margin-left: 0;
  }

  .year-steps :deep(.el-step__description) {
    padding-right: 0;
  }

  .settle-total {
    width: 100%;
    margin-left: 0;
  }
}

</style>
