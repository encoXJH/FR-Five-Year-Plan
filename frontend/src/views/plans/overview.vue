<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BackButton from '@/components/BackButton.vue'
import StatusTag from '@/components/StatusTag.vue'
import { getPlan, listPlans } from '@/api/plan'
import { getStatsByIndicator, getStatsByUnit, getStatsOverview } from '@/api/stats'
import { useAutoRefresh } from '@/utils/autoRefresh'
import { formatPercent } from '@/utils/format'
import { getCurrentPlan, setCurrentPlan } from '@/utils/currentPlan'
import type { PlanDetail } from '@/types/plan'
import type { IndicatorStat, StatsOverview, UnitStatItem } from '@/types/stats'

const route = useRoute()
const router = useRouter()
const planId = computed(() => typeof route.params.planId === 'string' ? route.params.planId : '')
const loading = ref(false)
const selectionLoaded = ref(false)
const previewLoading = ref(false)
const loadFailed = ref(false)
const plan = ref<PlanDetail | null>(null)
const selectedYear = ref<number>()
const overview = ref<StatsOverview | null>(null)
const indicators = ref<IndicatorStat[]>([])
const units = ref<UnitStatItem[]>([])

const isArchived = computed(() => plan.value?.status === 'ARCHIVED')
const yearOptions = computed(() => plan.value?.years.map((item) => item.year) ?? [])

async function loadPreview(silent = false): Promise<void> {
  if (!plan.value || selectedYear.value == null) return
  if (!silent) {
    previewLoading.value = true
    loadFailed.value = false
  }
  try {
    const [overviewResult, indicatorResult, unitResult] = await Promise.all([
      getStatsOverview(planId.value, selectedYear.value),
      getStatsByIndicator(planId.value, selectedYear.value),
      getStatsByUnit(planId.value, selectedYear.value),
    ])
    overview.value = overviewResult
    indicators.value = indicatorResult
    units.value = unitResult
    loadFailed.value = false
  } catch {
    loadFailed.value = true
  } finally {
    if (!silent) previewLoading.value = false
  }
}

async function loadPlan(silent = false): Promise<void> {
  if (!silent) loading.value = true
  try {
    const result = await getPlan(planId.value)
    plan.value = result
    setCurrentPlan(result)
    const years = result.years.map((item) => item.year)
    const currentYear = new Date().getFullYear()
    if (selectedYear.value == null || !years.includes(selectedYear.value)) {
      selectedYear.value = years.includes(currentYear) ? currentYear : years[0]
    }
    await loadPreview(silent)
  } finally {
    if (!silent) loading.value = false
  }
}

async function loadDefaultPlan(): Promise<void> {
  loading.value = true
  plan.value = null
  overview.value = null
  indicators.value = []
  units.value = []
  selectedYear.value = undefined
  try {
    const plans = (await listPlans()).filter((item) => item.planType !== 'TRAINING')
    const current = getCurrentPlan()
    const target = plans.find((item) => item.id === current?.id)
      ?? plans.find((item) => item.status === 'ACTIVE')
      ?? plans[0]
    if (target) await router.replace(`/plans/${target.id}`)
    else selectionLoaded.value = true
  } catch {
    selectionLoaded.value = true
  } finally {
    loading.value = false
  }
}

watch(planId, () => {
  if (planId.value) void loadPlan()
  else void loadDefaultPlan()
}, { immediate: true })

useAutoRefresh(() => planId.value ? loadPlan(true) : Promise.resolve())
</script>

<template>
  <div v-loading="loading" class="overview-page">
    <BackButton />
    <el-empty v-if="selectionLoaded && !plan && !loading" description="暂无可见的指标计划" />
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
            周期 {{ plan.startYear }}–{{ plan.endYear }} · 基线 {{ plan.baselineYear }} · 全部年度已激活
          </p>
        </div>
      </div>

      <el-alert
        type="info"
        :closable="false"
        title="计划数据只读预览，可查看各年度指标与学院完成情况；填报和审核请前往对应工作页面。"
        class="page-alert"
      />
      <el-alert
        v-if="loadFailed"
        type="error"
        :closable="false"
        title="计划统计数据加载失败，请稍后重试"
        class="page-alert"
      >
        <el-button link type="primary" @click="loadPreview()">重新加载</el-button>
      </el-alert>

      <el-card shadow="never" class="info-card" v-loading="previewLoading">
        <template #header>
          <div class="card-header">
            <span class="card-title">计划数据预览</span>
            <el-select
              v-model="selectedYear"
              size="small"
              style="width: 140px"
              aria-label="选择预览年度"
              @change="loadPreview()"
            >
              <el-option v-for="year in yearOptions" :key="year" :label="`${year} 年`" :value="year" />
            </el-select>
          </div>
        </template>
        <div class="kpi-grid">
          <div class="kpi-item">
            <span class="kpi-label">指标</span>
            <strong>{{ indicators.length }}</strong>
            <span class="kpi-note">当前可见指标</span>
          </div>
          <div class="kpi-item">
            <span class="kpi-label">学院任务</span>
            <strong>{{ overview?.taskCount ?? '—' }}</strong>
            <span class="kpi-note">{{ selectedYear }} 年任务</span>
          </div>
          <div class="kpi-item">
            <span class="kpi-label">年度完成率</span>
            <strong>{{ formatPercent(overview?.overallCompletionRate ?? null) }}</strong>
            <span class="kpi-note">达标任务 ÷ 有正目标任务</span>
          </div>
          <div class="kpi-item">
            <span class="kpi-label">五年累计完成率</span>
            <strong>{{ formatPercent(overview?.cumulativeCompletionRate ?? null) }}</strong>
            <span class="kpi-note">有效 A 指标累计完成率</span>
          </div>
        </div>
        <div class="status-summary">
          <span>已达标 {{ overview?.achievedCount ?? 0 }}</span>
          <span>未达标 {{ overview?.notAchievedCount ?? 0 }}</span>
          <span>进行中 {{ overview?.inProgressCount ?? 0 }}</span>
          <span>滞后预警 {{ overview?.warningCount ?? 0 }}</span>
        </div>
      </el-card>

      <el-card shadow="never" class="info-card" v-loading="previewLoading">
        <template #header>
          <div class="card-header">
            <span class="card-title">指标完成情况</span>
            <span class="card-note">{{ selectedYear }} 年</span>
          </div>
        </template>
        <el-table :data="indicators" border size="small" max-height="520" empty-text="暂无指标数据">
          <el-table-column prop="code" label="指标编号" width="110" />
          <el-table-column prop="name" label="关键产出指标" min-width="260" show-overflow-tooltip />
          <el-table-column prop="taskCount" label="学院任务" width="100" align="right" />
          <el-table-column prop="achievedCount" label="已达标" width="90" align="right" />
          <el-table-column label="年度完成率" width="130" align="right">
            <template #default="{ row }">{{ formatPercent(row.completionRate) }}</template>
          </el-table-column>
          <el-table-column label="五年累计" width="120" align="right">
            <template #default="{ row }">{{ formatPercent(row.cumulativeRate) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="info-card" v-loading="previewLoading">
        <template #header>
          <div class="card-header">
            <span class="card-title">学院完成情况</span>
            <span class="card-note">按年度完成率排序</span>
          </div>
        </template>
        <el-table :data="units" border size="small" max-height="480" empty-text="暂无学院数据">
          <el-table-column prop="rank" label="排名" width="70" align="center" />
          <el-table-column label="执行单元" min-width="210" show-overflow-tooltip>
            <template #default="{ row }">{{ row.unit?.name ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="taskCount" label="任务数" width="100" align="right" />
          <el-table-column prop="achievedCount" label="已达标" width="90" align="right" />
          <el-table-column label="年度完成率" width="130" align="right">
            <template #default="{ row }">{{ formatPercent(row.completionRate) }}</template>
          </el-table-column>
          <el-table-column label="五年累计" width="120" align="right">
            <template #default="{ row }">{{ formatPercent(row.cumulativeRate) }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.overview-page {
  min-width: 0;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: 22px;
  line-height: 30px;
}

.title-tag {
  margin-left: 8px;
  vertical-align: middle;
}

.page-subtitle {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.page-alert,
.info-card {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-title {
  font-weight: 600;
}

.card-note {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.kpi-item {
  display: flex;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  flex-direction: column;
  gap: 6px;
}

.kpi-item strong {
  color: var(--el-color-primary);
  font-size: 26px;
  font-variant-numeric: tabular-nums;
  line-height: 32px;
}

.kpi-label {
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.kpi-note {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 18px;
}

.status-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 22px;
  margin-top: 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

@media (max-width: 900px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 480px) {
  .page-title {
    font-size: 19px;
    line-height: 27px;
  }

  .kpi-grid {
    grid-template-columns: 1fr;
  }

  .card-header {
    align-items: flex-start;
  }
}
</style>
