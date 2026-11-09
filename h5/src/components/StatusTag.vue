<script setup lang="ts">
import { computed } from 'vue'
import { useUserStore } from '@/stores/user'
import { enumLabel } from '@/utils/enumLabels'

const props = defineProps<{
  kind: string
  value?: string | null
  plain?: boolean
}>()

const userStore = useUserStore()

const TYPE: Record<string, Record<string, string>> = {
  TaskYearStatus: {
    NOT_ACTIVATED: 'gray',
    IN_PROGRESS: 'brand',
    PENDING_REVIEW: 'warning',
    ADJUSTING: 'gray',
    ACHIEVED: 'success',
    NOT_ACHIEVED: 'danger',
    MAKEUP_PENDING: 'danger',
    MADE_UP: 'success',
  },
  SubmissionStatus: {
    DRAFT: 'gray',
    PENDING_REVIEW: 'warning',
    APPROVED: 'success',
    REJECTED: 'danger',
    WITHDRAWN: 'gray',
  },
  AdjustmentStatus: {
    PENDING_OWNER_REVIEW: 'warning',
    PENDING_RECEIVER_CONFIRM: 'gray',
    PENDING_LEADER_APPROVAL: 'brand',
    APPROVED: 'success',
    REJECTED: 'danger',
    WITHDRAWN: 'gray',
  },
  MilestoneStatus: {
    NOT_STARTED: 'gray',
    IN_PROGRESS: 'brand',
    COMPLETED: 'success',
    OVERDUE: 'danger',
    POSTPONED: 'gray',
  },
  TaskStatus: {
    PENDING_DISTRIBUTION: 'gray',
    DISTRIBUTED: 'warning',
    IN_PROGRESS: 'brand',
    TRANSFERRED_OUT: 'gray',
    CLOSED: 'gray',
  },
  PlanStatus: { DRAFT: 'gray', ACTIVE: 'brand', ARCHIVED: 'gray' },
  IndicatorStatus: { DRAFT: 'gray', FINALIZED: 'brand', DISTRIBUTED: 'success' },
  TemplateStatus: { ENABLED: 'success', DISABLED: 'gray' },
  PlanYearStatus: { NOT_ACTIVATED: 'gray', ACTIVE: 'brand', LOCKED: 'gray' },
  JobStatus: { PENDING: 'gray', RUNNING: 'brand', SUCCEEDED: 'success', FAILED: 'danger' },
  AchieveStatus: {
    NOT_STARTED: 'gray',
    IN_PROGRESS: 'brand',
    ACHIEVED: 'success',
    EXCEEDED: 'success',
    NOT_ACHIEVED: 'danger',
    LAGGING: 'warning',
  },
  IndicatorLevel: { A: 'brand', A_PLUS: 'gray', B: 'brand' },
  IndicatorNature: { CONSTRAINT: 'danger', DEVELOPMENT: 'gray' },
  StatCaliber: {
    INCREMENT: 'gray',
    STOCK: 'gray',
    GROWTH: 'gray',
    RATIO: 'gray',
    DIRECT_PERCENT: 'gray',
    BOOLEAN: 'gray',
  },
  TaskMaterialStatus: {
    NOT_UPLOADED: 'gray',
    NOT_SUBMITTED: 'warning',
    PENDING_REVIEW: 'warning',
    APPROVED: 'success',
  },
}

const tone = computed(() => (props.value ? (TYPE[props.kind]?.[props.value] ?? 'gray') : 'gray'))
const label = computed(() => {
  if (!props.value) return '—'
  return userStore.enums[props.kind]?.[props.value] ?? enumLabel(props.kind, props.value)
})
</script>

<template>
  <span
    class="status-tag"
    :class="[`status-tag--${tone}`, { 'status-tag--plain': plain }]"
  >{{ label }}</span>
</template>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 11px;
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
}
.status-tag--brand {
  background: #eaf0fc;
  color: #2b5bc0;
}
.status-tag--success {
  background: #e8f7f0;
  color: #1fa971;
}
.status-tag--warning {
  background: #fdf3e5;
  color: #c07d1e;
}
.status-tag--danger {
  background: #fdecec;
  color: #d64b4b;
}
.status-tag--gray {
  background: #f1f3f7;
  color: #6b7a90;
}
.status-tag--plain {
  background: transparent;
  border: 1px solid currentColor;
}
</style>
