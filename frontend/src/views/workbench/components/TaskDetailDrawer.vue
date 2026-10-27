<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import StatusTag from '@/components/StatusTag.vue'
import { getTask } from '@/api/task'
import { listTaskAdjustments } from '@/api/adjustment'
import { useUserStore } from '@/stores/user'
import { formatDateTime, formatPercent } from '@/utils/format'
import { formatTargetValue } from '@/utils/indicator'
import { canWriteTask, progressPercentage } from '@/utils/task'
import { canApplyForTask, summarizeItems } from '@/utils/adjustment'
import { enumLabel } from '@/utils/enumLabels'
import type { TaskDetail } from '@/types/task'
import type { AdjustmentListItem } from '@/types/adjustment'
import type { StatCaliber } from '@/types/enums'

const props = defineProps<{
  modelValue: boolean
  taskId: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 填报入口：年度进行中的任务跳 P08 */
  submit: [payload: { taskId: string; year: number | null }]
  /** 发起调整入口：跳 P10 并带入任务 */
  apply: [payload: { taskId: string }]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const loading = ref(false)
const task = ref<TaskDetail | null>(null)
const activeTab = ref('info')

const userStore = useUserStore()

const caliber = computed<StatCaliber>(() => task.value?.indicator?.statCaliber ?? 'INCREMENT')

/** 进行中年度（填报入口，取第一个 IN_PROGRESS 年份） */
const activeYear = computed<number | null>(() => {
  const hit = (task.value?.years ?? []).find((item) => item.yearStatus === 'IN_PROGRESS')
  return hit?.year ?? null
})

const canSubmit = computed(
  () =>
    canWriteTask(task.value) &&
    activeYear.value !== null &&
    userStore.can('submission:submit') &&
    props.taskId !== null,
)

/** 发起调整入口：INCREMENT / BOOLEAN 口径 + adjustment:apply 权限，任务未终结 */
const canApply = computed(
  () =>
    canWriteTask(task.value) &&
    task.value !== null &&
    userStore.can('adjustment:apply') &&
    canApplyForTask(task.value.status, task.value.indicator?.statCaliber, task.value.isHistorical),
)

function goSubmit(): void {
  if (!props.taskId || !canSubmit.value) return
  emit('submit', { taskId: props.taskId, year: activeYear.value })
  visible.value = false
}

function goApply(): void {
  if (!props.taskId || !canApply.value) return
  emit('apply', { taskId: props.taskId })
  visible.value = false
}

// ---------- 调整历史 ----------
const adjustments = ref<AdjustmentListItem[]>([])
const adjustmentsLoaded = ref(false)

function adjustmentSummary(item: AdjustmentListItem): string {
  return summarizeItems(item.items ?? [], task.value?.indicator?.unitLabel)
}

function transferLine(item: AdjustmentListItem): string {
  const from = item.fromUnit?.name ?? task.value?.unit?.name ?? ''
  const to = item.toUnit?.name ?? ''
  return to ? `${from} → ${to}` : ''
}

async function load(): Promise<void> {
  if (!props.taskId) return
  loading.value = true
  try {
    const [detail, history] = await Promise.all([
      getTask(props.taskId),
      listTaskAdjustments(props.taskId).catch(() => [] as AdjustmentListItem[]),
    ])
    task.value = detail
    adjustments.value = history
    adjustmentsLoaded.value = true
  } finally {
    loading.value = false
  }
}

watch(visible, (value) => {
  if (value) {
    task.value = null
    adjustments.value = []
    adjustmentsLoaded.value = false
    activeTab.value = 'info'
    load()
  }
})
</script>

<template>
  <el-drawer
    v-model="visible"
    direction="rtl"
    size="min(560px, 100vw)"
    class="task-detail-drawer"
    :title="task ? `任务详情 · ${task.code}` : '任务详情'"
  >
    <div v-loading="loading">
      <template v-if="task">
        <el-alert
          v-if="task.isHistorical"
          type="info"
          :closable="false"
          title="历史任务只读，可查看年度目标、完成值和调整记录"
          class="history-alert"
        />
        <el-tabs v-model="activeTab">
          <el-tab-pane label="任务信息" name="info">
            <el-descriptions :column="2" border size="small" class="detail-desc">
              <el-descriptions-item label="任务编号">{{ task.code }}</el-descriptions-item>
              <el-descriptions-item label="任务状态">
                <StatusTag kind="TaskStatus" :value="task.status" />
              </el-descriptions-item>
              <el-descriptions-item label="关键产出指标" :span="2">
                {{ task.indicator ? `${task.indicator.code} ${task.indicator.name}` : '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="执行单元">
                {{ task.unit ? `${task.unit.code} ${task.unit.name}` : '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="学院负责人">{{ task.owner?.name ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="统计口径">
                <StatusTag v-if="task.indicator?.statCaliber" kind="StatCaliber" :value="task.indicator.statCaliber" />
                <template v-else>—</template>
              </el-descriptions-item>
              <el-descriptions-item label="五年目标">
                {{ formatTargetValue(task.totalTarget ?? null, caliber) }}{{ task.indicator?.unitLabel ? ` ${task.indicator.unitLabel}` : '' }}
              </el-descriptions-item>
            </el-descriptions>

            <h3 class="section-title">年度目标与完成</h3>
            <el-table :data="task.years ?? []" border size="small">
              <el-table-column prop="year" label="年度" width="70" align="center" />
              <el-table-column label="目标" width="100" align="right">
                <template #default="{ row }">{{ formatTargetValue(row.target, caliber) }}</template>
              </el-table-column>
              <el-table-column label="已完成（审核通过）" width="120" align="right">
                <template #default="{ row }">{{ formatTargetValue(row.actualValue, caliber) }}</template>
              </el-table-column>
              <el-table-column label="完成率" min-width="140">
                <template #default="{ row }">
                  <template v-if="row.completionRate !== null && row.completionRate !== undefined">
                    <el-progress
                      :percentage="progressPercentage(row.completionRate)"
                      :stroke-width="8"
                      :color="row.completionRate >= 1 ? '#67C23A' : '#2B5BC0'"
                    >
                      <span class="rate-text">{{ formatPercent(row.completionRate) }}</span>
                    </el-progress>
                  </template>
                  <span v-else class="empty-text">—</span>
                </template>
              </el-table-column>
              <el-table-column label="年度状态" width="100" align="center">
                <template #default="{ row }">
                  <StatusTag kind="TaskYearStatus" :value="row.yearStatus" />
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="调整历史" name="adjustments">
            <el-timeline v-if="adjustments.length > 0" class="history-timeline">
              <el-timeline-item
                v-for="item in adjustments"
                :key="item.id"
                :timestamp="formatDateTime(item.createdAt)"
                placement="top"
                :type="item.status === 'APPROVED' ? 'success' : item.status === 'REJECTED' || item.status === 'WITHDRAWN' ? 'info' : 'primary'"
              >
                <div class="history-head">
                  <span class="history-type">
                    {{ enumLabel('AdjustmentType', item.type, userStore.enums) }}
                  </span>
                  <StatusTag kind="AdjustmentStatus" :value="item.status" />
                </div>
                <div class="history-line">{{ adjustmentSummary(item) }}</div>
                <div v-if="transferLine(item)" class="history-line">{{ transferLine(item) }}</div>
                <div class="history-meta">
                  申请人：{{ item.applicant?.name ?? '—' }}
                  <template v-if="item.reason"> · {{ item.reason }}</template>
                </div>
              </el-timeline-item>
            </el-timeline>
            <el-empty
              v-else-if="adjustmentsLoaded"
              :image-size="80"
              description="该任务暂无调整记录"
            />
          </el-tab-pane>
        </el-tabs>
      </template>
      <el-empty v-else-if="!loading" :image-size="80" description="加载失败或任务不存在" />
    </div>
    <template v-if="canSubmit || canApply" #footer>
      <div class="drawer-footer">
        <el-button v-if="canApply" type="warning" plain @click="goApply">发起调整</el-button>
        <el-button v-if="canSubmit" type="primary" @click="goSubmit">填报</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.history-alert {
  margin-bottom: var(--space-3);
}

.detail-desc {
  margin-bottom: var(--space-4);
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  margin: 0 0 var(--space-3);
}

.rate-text {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.empty-text {
  color: var(--el-text-color-placeholder);
}

.history-timeline {
  padding-left: var(--space-1);
}

.history-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.history-type {
  font-weight: 600;
  font-size: 13px;
}

.history-line {
  margin-top: 2px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.history-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}
</style>
