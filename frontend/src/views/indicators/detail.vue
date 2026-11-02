<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { LocationQueryRaw } from 'vue-router'
import StatusTag from '@/components/StatusTag.vue'
import BackButton from '@/components/BackButton.vue'
import GanttExplorer from './components/GanttExplorer.vue'
import IndicatorEditDrawer from './components/IndicatorEditDrawer.vue'
import { getIndicator } from '@/api/indicator'
import { getPlan } from '@/api/plan'
import { useUserStore } from '@/stores/user'
import { hasQuantitativeFields } from '@/utils/indicator'
import type { IndicatorDetail } from '@/types/indicator'
import type { PlanDetail } from '@/types/plan'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const indicatorId = computed(() => route.params.id as string)
const loading = ref(false)
const indicator = ref<IndicatorDetail | null>(null)
const plan = ref<PlanDetail | null>(null)

const quantitative = computed(() => {
  const detail = indicator.value
  return !!detail && hasQuantitativeFields(detail.level, detail.isQuantifiable)
})
const hasDistributedScope = computed(() => {
  const detail = indicator.value
  if (!detail) return false
  return detail.status === 'DISTRIBUTED'
    || detail.children.some((child) => child.status === 'DISTRIBUTED')
    || (detail.bSummaries ?? []).some((task) => task.isHistorical || task.status !== 'PENDING_DISTRIBUTION')
})
const isArchived = computed(() => plan.value?.status === 'ARCHIVED')

const years = computed<number[]>(() => {
  if (!plan.value) return []
  const list: number[] = []
  for (let year = plan.value.startYear; year <= plan.value.endYear; year += 1) list.push(year)
  return list
})

const canEdit = computed(() =>
  quantitative.value || indicator.value?.level === 'A'
    ? userStore.can('indicator:edit') && (indicator.value?.editable ?? true)
      && !hasDistributedScope.value && !isArchived.value
    : false,
)
const canApplyAdjustment = computed(() =>
  quantitative.value && userStore.can('adjustment:apply') && !isArchived.value,
)
const primaryAction = computed<'edit' | 'adjust' | null>(() => {
  if (!indicator.value || isArchived.value) return null
  if (hasDistributedScope.value) {
    return canApplyAdjustment.value ? 'adjust' : null
  }
  return canEdit.value ? 'edit' : null
})
const drawerVisible = ref(false)

function runPrimaryAction(): void {
  if (primaryAction.value === 'edit') {
    drawerVisible.value = true
    return
  }
  if (primaryAction.value !== 'adjust' || !indicator.value) return
  router.push({
    path: '/adjustments/apply',
    query: typeof route.query.taskId === 'string'
      ? { taskId: route.query.taskId }
      : { indicatorId: indicator.value.id },
  })
}

let loadSequence = 0

async function loadAll(): Promise<void> {
  const sequence = ++loadSequence
  loading.value = true
  indicator.value = null
  plan.value = null
  try {
    const detail = await getIndicator(indicatorId.value)
    if (sequence !== loadSequence) return
    if (detail.level === 'A_PLUS') {
      const parentId = detail.parent?.id ?? detail.parentId
      if (parentId) {
        const query: LocationQueryRaw = { ...route.query, mode: 'units', focusAPlus: detail.id }
        delete query.tab
        await router.replace({ path: `/indicators/${parentId}`, query })
        return
      }
    }
    indicator.value = detail
    const planDetail = await getPlan(detail.planId)
    if (sequence !== loadSequence) return
    plan.value = planDetail
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

watch(indicatorId, (id) => {
  if (id) void loadAll()
}, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="indicator-detail-page">
    <BackButton :fallback="indicator ? `/plans/${indicator.planId}/indicators` : undefined" />
    <template v-if="indicator">
      <div class="page-header">
        <div class="page-heading">
          <h1 class="page-title">
            <StatusTag kind="IndicatorLevel" :value="indicator.level" />
            <span class="mono">{{ indicator.code }}</span>
            <span>{{ indicator.name }}</span>
            <StatusTag kind="IndicatorNature" :value="indicator.nature" />
            <StatusTag v-if="indicator.statCaliber" kind="StatCaliber" :value="indicator.statCaliber" />
          </h1>
          <p class="page-subtitle">{{ plan?.name ?? '' }}</p>
        </div>
      </div>
      <div v-if="primaryAction" class="app-action-row header-actions">
        <el-button :type="primaryAction === 'edit' ? 'primary' : 'warning'" @click="runPrimaryAction">
          {{ primaryAction === 'edit' ? '编辑' : '发起调整' }}
        </el-button>
      </div>

      <el-alert
        v-if="indicator.status === 'DISTRIBUTED' && !isArchived"
        type="info"
        :closable="false"
        title="分工已下发，当前为只读视图；如需变更请发起调整"
        class="page-alert"
      />

      <el-card v-if="quantitative" shadow="never" class="gantt-card">
        <template #header>
          <div class="card-header"><strong>指标任务甘特图</strong><span class="card-note">完整展示该指标全部可见学院任务、进度与依赖</span></div>
        </template>
        <GanttExplorer :plan-id="indicator.planId" :indicator-id="indicator.id" />
      </el-card>

      <el-card v-else shadow="never">
        <el-empty :image-size="80" description="非量化指标仅维护基础信息，不参与年度执行与统计" />
      </el-card>

      <IndicatorEditDrawer
        v-model="drawerVisible"
        :plan-id="indicator.planId"
        :indicator="indicator"
        :years="years"
        @saved="loadAll"
      />

    </template>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.page-heading {
  min-width: 0;
}

.page-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
}

.page-subtitle {
  margin: var(--space-1) 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.header-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-start;
  gap: var(--space-2);
}

.header-actions .el-button {
  margin: 0;
}

.mono {
  font-family: var(--app-font-mono);
}

.page-alert {
  margin-bottom: var(--space-4);
}

.gantt-card {
  border-radius: var(--radius-card);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.card-note {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.focus-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-3);
  padding: var(--space-2) var(--space-3);
  background: var(--el-color-primary-light-9);
  border-left: 3px solid var(--el-color-primary);
  font-size: 13px;
}

.unit-layout {
  display: grid;
  grid-template-columns: minmax(280px, 340px) minmax(0, 1fr);
  gap: var(--space-4);
  align-items: start;
}

.task-list {
  max-height: 640px;
  overflow-y: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--radius-card);
}

.task-entry {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  min-height: 94px;
  padding: var(--space-2);
  border-bottom: 1px solid var(--el-border-color-lighter);
  border-left: 3px solid transparent;
}

.task-entry:last-child {
  border-bottom: 0;
}

.task-entry.is-focused {
  background: var(--el-color-primary-light-9);
  border-left-color: var(--el-color-primary);
}

.task-entry.is-selected {
  box-shadow: inset 0 0 0 1px var(--el-color-primary);
}

.task-select {
  flex: 1;
  min-width: 0;
  height: auto;
  margin: 0;
  padding: var(--space-1);
  white-space: normal;
}

.task-select :deep(span) {
  justify-content: flex-start;
  width: 100%;
}

.task-main {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  min-width: 0;
  text-align: left;
}

.task-code {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.task-unit {
  color: var(--el-text-color-regular);
  line-height: 20px;
}

.task-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.task-side {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-1);
}

.task-side .el-button {
  margin: 0;
}

.task-gantt {
  min-width: 0;
  min-height: 220px;
}

.selected-task-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.selected-task-header > div {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

@media (max-width: 900px) {
  .unit-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .task-list {
    max-height: 360px;
  }
}

@media (max-width: 768px) {
  .page-header {
    flex-direction: column;
  }

  .header-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    width: 100%;
  }

  .header-actions .el-button {
    min-height: 44px;
    white-space: normal;
  }

  .card-header,
  .selected-task-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .task-entry {
    min-height: 104px;
  }

  .task-side .el-button {
    min-height: 36px;
  }
}
</style>
