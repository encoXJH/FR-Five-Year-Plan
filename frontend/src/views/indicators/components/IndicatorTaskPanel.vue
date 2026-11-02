<script setup lang="ts">
import { computed } from 'vue'
import StatusTag from '@/components/StatusTag.vue'
import type { IndicatorTreeNode } from '@/types/indicator'
import type { StatsTreeNode } from '@/types/stats'
import type { TaskListItem } from '@/types/task'
import { formatPercent } from '@/utils/format'
import {
  completionLevel,
  completionLevelClass,
  formatTargetValue,
  yearTargetMap,
  type CompletionLevel,
} from '@/utils/indicator'
import { primaryTaskIndicator, progressPercentage } from '@/utils/task'

const props = defineProps<{
  indicator: IndicatorTreeNode
  tasks: TaskListItem[] | undefined
  loading: boolean
  loadError: boolean
  years: number[]
  statsByTask: Map<string, StatsTreeNode>
  canAllocate: boolean
}>()

const emit = defineEmits<{
  retry: []
  detail: [task: TaskListItem]
  materials: [task: TaskListItem, year: number]
}>()

const taskViews = computed(() => (props.tasks ?? []).map((task) => {
  const stats = props.statsByTask.get(task.id)
  const primaryIndicator = primaryTaskIndicator(task)
  const caliber = primaryIndicator?.statCaliber ?? props.indicator.statCaliber ?? null
  const totalTarget = task.totalTarget
  const hasTarget = typeof totalTarget === 'boolean' ? totalTarget : typeof totalTarget === 'number' && totalTarget > 0

  return {
    task,
    parent: primaryIndicator ?? props.indicator,
    caliber,
    hasTarget,
    yearTargets: yearTargetMap(task.yearTargets),
    actualValue: stats?.actualValue ?? task.actualValue ?? null,
    completionRate: stats?.completionRate ?? task.completionRate ?? null,
    cumulativeRate: stats?.cumulativeRate ?? task.cumulativeRate ?? null,
    yearStatus: stats?.yearStatus ?? task.yearStatus ?? null,
  }
}))

function rateLevel(rate: number | null | undefined, hasTarget: boolean): CompletionLevel | null {
  const percent = progressPercentage(rate)
  return completionLevel(percent, hasTarget || percent > 0)
}

function rateClass(rate: number | null | undefined, hasTarget: boolean): string {
  return completionLevelClass(rateLevel(rate, hasTarget))
}

function rateColor(rate: number | null | undefined, hasTarget: boolean): string {
  const level = rateLevel(rate, hasTarget)
  if (level === 'none') return '#F56C6C'
  if (level === 'partial') return '#E6A23C'
  if (level === 'complete') return '#67C23A'
  return '#2B5BC0'
}
</script>

<template>
  <section
    class="indicator-task-panel"
    :aria-label="`${indicator.code} 的 学院任务`"
    :aria-busy="loading"
  >
    <div v-if="loading" class="task-loading-state" role="status" aria-live="polite">
      <el-skeleton :rows="2" animated />
      <p>学院任务加载中…</p>
    </div>

    <div v-else-if="loadError" class="task-error-state" role="alert">
      <span>学院任务加载失败</span>
      <el-button type="primary" plain @click="emit('retry')">重试</el-button>
    </div>

    <div
      v-else-if="tasks !== undefined && tasks.length === 0"
      class="task-empty-state"
      role="status"
    >
      {{ canAllocate ? '暂无学院任务，可通过「任务分解」创建学院分工' : '该指标暂无学院任务' }}
    </div>

    <div v-else class="indicator-task-list">
      <article
        v-for="view in taskViews"
        :key="view.task.id"
        class="indicator-task-card"
        :aria-label="`学院任务 ${view.task.code}`"
      >
        <header class="task-card-header">
          <div class="task-card-heading">
            <span class="task-card-kicker">学院 B</span>
            <h3 class="task-card-code">{{ view.task.code }}</h3>
          </div>
          <div class="task-card-statuses">
            <div class="task-card-status">
              <span>任务状态</span>
              <StatusTag kind="TaskStatus" :value="view.task.status" />
            </div>
            <div class="task-card-status">
              <span>年度状态</span>
              <StatusTag v-if="view.yearStatus" kind="TaskYearStatus" :value="view.yearStatus" />
              <span v-else class="task-value-empty">—</span>
            </div>
            <el-tag v-if="view.task.isHistorical" type="info" size="small" effect="plain">
              历史只读
            </el-tag>
          </div>
        </header>

        <div class="task-meta-grid">
          <div class="task-meta-item task-meta-item--wide">
            <span class="task-meta-label">所属 A</span>
            <strong>{{ view.parent.code }} {{ view.parent.name }}</strong>
          </div>
          <div class="task-meta-item">
            <span class="task-meta-label">执行单元</span>
            <strong>{{ view.task.unit ? `${view.task.unit.code} ${view.task.unit.name}` : '—' }}</strong>
          </div>
          <div class="task-meta-item">
            <span class="task-meta-label">学院负责人</span>
            <strong>{{ view.task.owner?.name ?? '—' }}</strong>
          </div>
          <div class="task-meta-item">
            <span class="task-meta-label">统计口径</span>
            <StatusTag v-if="view.caliber" kind="StatCaliber" :value="view.caliber" />
            <strong v-else class="task-value-empty">—</strong>
          </div>
          <div class="task-meta-item">
            <span class="task-meta-label">五年目标</span>
            <strong class="task-number">{{ formatTargetValue(view.task.totalTarget ?? null, view.caliber) }}</strong>
          </div>
        </div>

        <section class="indicator-progress-section" aria-label="完成进度">
          <h4>完成进度</h4>
          <div class="indicator-progress-grid">
            <div class="indicator-progress-item">
              <span>当前完成值</span>
              <strong :class="rateClass(view.completionRate, view.hasTarget)">{{ formatTargetValue(view.actualValue, view.caliber) }}</strong>
            </div>
            <div class="indicator-progress-item">
              <span>当年完成率</span>
              <strong :class="rateClass(view.completionRate, view.hasTarget)">{{ formatPercent(view.completionRate) }}</strong>
              <el-progress
                v-if="view.completionRate !== null"
                :percentage="progressPercentage(view.completionRate)"
                :stroke-width="5"
                :show-text="false"
                :color="rateColor(view.completionRate, view.hasTarget)"
              />
            </div>
            <div class="indicator-progress-item">
              <span>五年累计完成率</span>
              <strong :class="rateClass(view.cumulativeRate, view.hasTarget)">{{ formatPercent(view.cumulativeRate) }}</strong>
              <el-progress
                v-if="view.cumulativeRate !== null"
                :percentage="progressPercentage(view.cumulativeRate)"
                :stroke-width="5"
                :show-text="false"
                :color="rateColor(view.cumulativeRate, view.hasTarget)"
              />
            </div>
          </div>
        </section>

        <section class="indicator-year-section" aria-label="逐年目标">
          <h4>逐年目标</h4>
          <div class="indicator-year-grid">
            <button
              v-for="year in years"
              :key="year"
              type="button"
              class="indicator-year-target indicator-year-target--link"
              :title="`查看 ${view.task.code} ${year} 年已提交的材料`"
              :aria-label="`查看 ${view.task.code} ${year} 年已提交的材料`"
              @click="emit('materials', view.task, year)"
            >
              <span>{{ year }} 年</span>
              <strong>{{ formatTargetValue(view.yearTargets.get(year) ?? null, view.caliber) }}</strong>
              <span class="year-material-hint">查看材料</span>
            </button>
          </div>
        </section>

        <footer class="task-card-actions">
          <el-button type="primary" plain @click="emit('detail', view.task)">详情</el-button>
        </footer>
      </article>
    </div>
  </section>
</template>

<style scoped>
.indicator-task-panel,
.indicator-task-panel *,
.indicator-task-panel *::before,
.indicator-task-panel *::after {
  box-sizing: border-box;
}

.indicator-task-panel {
  width: 100%;
  min-width: 0;
  max-width: 100%;
  padding: 14px 0 0;
  overflow: hidden;
  color: #17233c;
  background: transparent;
  overflow-wrap: anywhere;
}

.indicator-task-list {
  display: grid;
  min-width: 0;
  gap: 12px;
}

.indicator-task-card {
  min-width: 0;
  max-width: 100%;
  padding: 16px;
  background: var(--el-bg-color);
  border: 1px solid #e4e9f0;
  border-left: 3px solid var(--el-color-primary-light-7);
  border-radius: 6px;
}

.task-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  min-width: 0;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.task-card-heading {
  min-width: 0;
}

.task-card-kicker,
.task-meta-label,
.indicator-progress-item > span,
.indicator-year-target > span {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
  line-height: 1.5;
}

.task-card-code {
  margin: 2px 0 0;
  color: var(--el-color-primary);
  font-family: var(--app-font-mono, ui-monospace, monospace);
  font-size: 16px;
  font-weight: 600;
  line-height: 1.5;
}

.task-card-statuses {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: flex-end;
  gap: 8px 14px;
}

.task-card-status {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.task-card-status > span:first-child {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1;
}

.task-meta-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) repeat(4, minmax(0, 1fr));
  min-width: 0;
  gap: 1px;
  margin-top: 12px;
  overflow: hidden;
  background: #e4e9f0;
  border: 1px solid #e4e9f0;
  border-radius: 6px;
}

.task-meta-item {
  min-width: 0;
  padding: 10px 12px;
  background: var(--el-bg-color);
}

.task-meta-item > strong {
  display: block;
  margin-top: 4px;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 500;
  line-height: 1.55;
}

.task-meta-item > .el-tag {
  margin-top: 4px;
}

.task-meta-item .task-number,
.indicator-progress-item > strong,
.indicator-year-target > strong {
  font-variant-numeric: tabular-nums;
}

.task-value-empty {
  color: var(--el-text-color-placeholder) !important;
}

.indicator-progress-section,
.indicator-year-section {
  min-width: 0;
  margin-top: 14px;
}

.indicator-progress-section h4,
.indicator-year-section h4 {
  margin: 0 0 8px;
  color: var(--el-text-color-regular);
  font-size: 12px;
  font-weight: 500;
  line-height: 1.5;
}

.indicator-progress-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  min-width: 0;
  gap: 8px;
}

.indicator-progress-item,
.indicator-year-target {
  min-width: 0;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px solid #e4e9f0;
  border-radius: 6px;
}

.indicator-year-target--link {
  width: 100%;
  font: inherit;
  text-align: left;
  cursor: pointer;
  appearance: none;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.indicator-year-target--link:hover,
.indicator-year-target--link:focus-visible {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary);
  outline: none;
}

.indicator-year-target .year-material-hint {
  margin-top: 2px;
  color: var(--el-color-primary);
  font-size: 11px;
  line-height: 1.4;
}

.indicator-progress-item > strong,
.indicator-year-target > strong {
  display: block;
  margin: 3px 0 6px;
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 600;
  line-height: 1.45;
}

.indicator-progress-item > strong.completion-value--none { color: var(--el-color-danger); }
.indicator-progress-item > strong.completion-value--partial { color: var(--el-color-warning); }
.indicator-progress-item > strong.completion-value--complete { color: var(--el-color-success); }

.indicator-progress-item:first-child > strong,
.indicator-year-target > strong {
  margin-bottom: 0;
}

.indicator-progress-item :deep(.el-progress),
.indicator-progress-item :deep(.el-progress-bar) {
  width: 100%;
  line-height: 1;
}

.indicator-progress-item :deep(.el-progress-bar) {
  padding-right: 0;
}

.indicator-year-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(96px, 100%), 1fr));
  min-width: 0;
  gap: 8px;
}

.task-card-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.task-card-actions :deep(.el-button),
.task-error-state :deep(.el-button) {
  min-width: 88px;
  min-height: 40px;
  margin: 0;
}

.task-card-actions :deep(.el-button:focus-visible),
.task-error-state :deep(.el-button:focus-visible) {
  outline: 2px solid #2b5bc0;
  outline-offset: 2px;
}

.task-loading-state,
.task-empty-state,
.task-error-state {
  min-width: 0;
  padding: 18px;
  background: var(--el-bg-color);
  border: 1px solid #e4e9f0;
  border-radius: 6px;
}

.task-loading-state p {
  margin: 10px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  text-align: center;
}

.task-empty-state {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.7;
  text-align: center;
  border-style: dashed;
}

.task-error-state {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 12px 18px;
  color: var(--el-color-danger);
  font-size: 13px;
  background: var(--el-color-danger-light-9);
  border-color: var(--el-color-danger-light-7);
}

@media (max-width: 1024px) {
  .task-meta-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .task-meta-item--wide {
    grid-column: 1 / -1;
  }
}

@media (max-width: 640px) {
  .indicator-task-panel {
    padding: 12px 0 0;
  }

  .indicator-task-card {
    padding: 12px;
  }

  .task-card-header {
    flex-direction: column;
  }

  .task-card-statuses {
    justify-content: flex-start;
  }

  .indicator-progress-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .task-card-actions :deep(.el-button),
  .task-error-state :deep(.el-button) {
    min-height: 44px;
  }
}

@media (max-width: 420px) {
  .task-meta-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .task-meta-item--wide {
    grid-column: auto;
  }

  .task-card-actions :deep(.el-button) {
    width: 100%;
  }
}
</style>
