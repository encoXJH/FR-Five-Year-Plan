<script setup lang="ts">
import { computed } from 'vue'
import { useUserStore } from '@/stores/user'

interface TagStyle {
  type: 'primary' | 'success' | 'info' | 'warning' | 'danger'
  effect?: 'light' | 'dark' | 'plain'
  cls?: 'is-adjust' | 'is-teal'
  round?: boolean
}

/** 状态 → 标签样式映射（界面设计规范 2.5） */
const STATUS_TAG: Record<string, Record<string, TagStyle>> = {
  TaskYearStatus: {
    NOT_ACTIVATED: { type: 'info' },
    IN_PROGRESS: { type: 'primary' },
    PENDING_REVIEW: { type: 'warning' },
    ADJUSTING: { type: 'info', cls: 'is-adjust' },
    ACHIEVED: { type: 'success' },
    NOT_ACHIEVED: { type: 'danger' },
    MAKEUP_PENDING: { type: 'danger', effect: 'plain' },
    MADE_UP: { type: 'success', effect: 'plain' },
  },
  SubmissionStatus: {
    DRAFT: { type: 'info', effect: 'plain' },
    PENDING_REVIEW: { type: 'warning' },
    APPROVED: { type: 'success' },
    REJECTED: { type: 'danger' },
    WITHDRAWN: { type: 'info' },
  },
  AdjustmentStatus: {
    PENDING_OWNER_REVIEW: { type: 'warning' },
    PENDING_RECEIVER_CONFIRM: { type: 'info', cls: 'is-adjust' },
    PENDING_LEADER_APPROVAL: { type: 'primary' },
    APPROVED: { type: 'success' },
    REJECTED: { type: 'danger' },
    WITHDRAWN: { type: 'info' },
  },
  MilestoneStatus: {
    NOT_STARTED: { type: 'info' },
    IN_PROGRESS: { type: 'primary' },
    COMPLETED: { type: 'success' },
    OVERDUE: { type: 'danger' },
    POSTPONED: { type: 'info', cls: 'is-adjust' },
  },
  TaskStatus: {
    PENDING_DISTRIBUTION: { type: 'info', effect: 'plain' },
    DISTRIBUTED: { type: 'warning' },
    IN_PROGRESS: { type: 'primary' },
    TRANSFERRED_OUT: { type: 'info', cls: 'is-adjust', effect: 'plain' },
    CLOSED: { type: 'info' },
  },
  PlanStatus: {
    DRAFT: { type: 'info' },
    ACTIVE: { type: 'primary' },
    ARCHIVED: { type: 'info', effect: 'dark' },
  },
  IndicatorStatus: {
    DRAFT: { type: 'info' },
    FINALIZED: { type: 'primary', effect: 'plain' },
    DISTRIBUTED: { type: 'success' },
  },
  TemplateStatus: {
    ENABLED: { type: 'success' },
    DISABLED: { type: 'info' },
  },
  PlanYearStatus: {
    NOT_ACTIVATED: { type: 'info' },
    ACTIVE: { type: 'primary' },
    LOCKED: { type: 'info', effect: 'dark' },
  },
  JobStatus: {
    PENDING: { type: 'info' },
    RUNNING: { type: 'primary' },
    SUCCEEDED: { type: 'success' },
    FAILED: { type: 'danger' },
  },
  AchieveStatus: {
    NOT_STARTED: { type: 'info' },
    IN_PROGRESS: { type: 'primary' },
    ACHIEVED: { type: 'success' },
    EXCEEDED: { type: 'success', effect: 'dark' },
    NOT_ACHIEVED: { type: 'danger' },
    LAGGING: { type: 'warning', effect: 'dark' },
  },
  IndicatorLevel: {
    A: { type: 'primary', effect: 'dark', round: true },
    A_PLUS: { type: 'primary', round: true },
    B: { type: 'primary', effect: 'plain', round: true },
  },
  IndicatorNature: {
    CONSTRAINT: { type: 'danger', effect: 'plain', round: true },
    DEVELOPMENT: { type: 'info', cls: 'is-teal', effect: 'plain', round: true },
  },
  StatCaliber: {
    INCREMENT: { type: 'info', effect: 'plain', round: true },
    STOCK: { type: 'info', effect: 'plain', round: true },
    GROWTH: { type: 'info', effect: 'plain', round: true },
    RATIO: { type: 'info', effect: 'plain', round: true },
    DIRECT_PERCENT: { type: 'info', effect: 'plain', round: true },
    BOOLEAN: { type: 'info', effect: 'plain', round: true },
  },
}

/** 默认中文名（API 约定第 9 章；线上以 GET /dicts/enums 为准） */
const DEFAULT_LABELS: Record<string, Record<string, string>> = {
  TaskYearStatus: {
    NOT_ACTIVATED: '未激活',
    IN_PROGRESS: '进行中',
    PENDING_REVIEW: '待审核',
    ADJUSTING: '调整审批中',
    ACHIEVED: '已达标',
    NOT_ACHIEVED: '未达标',
    MAKEUP_PENDING: '待补做',
    MADE_UP: '已补做',
  },
  SubmissionStatus: {
    DRAFT: '草稿',
    PENDING_REVIEW: '待审核',
    APPROVED: '已通过',
    REJECTED: '已驳回',
    WITHDRAWN: '已撤回',
  },
  AdjustmentStatus: {
    PENDING_OWNER_REVIEW: '待初审',
    PENDING_RECEIVER_CONFIRM: '待接收方确认',
    PENDING_LEADER_APPROVAL: '待校领导审批',
    APPROVED: '已生效',
    REJECTED: '已驳回',
    WITHDRAWN: '已撤回',
  },
  MilestoneStatus: {
    NOT_STARTED: '未开始',
    IN_PROGRESS: '进行中',
    COMPLETED: '已完成',
    OVERDUE: '逾期',
    POSTPONED: '已延期',
  },
  TaskStatus: {
    PENDING_DISTRIBUTION: '待下发',
    DISTRIBUTED: '已下发',
    IN_PROGRESS: '执行中',
    TRANSFERRED_OUT: '已全部转出',
    CLOSED: '已结束',
  },
  PlanStatus: { DRAFT: '草稿', ACTIVE: '执行中', ARCHIVED: '已归档' },
  IndicatorStatus: { DRAFT: '草稿', FINALIZED: '已定稿', DISTRIBUTED: '已下发' },
  TemplateStatus: { ENABLED: '启用', DISABLED: '停用' },
  PlanYearStatus: { NOT_ACTIVATED: '未激活', ACTIVE: '已激活', LOCKED: '已核算锁定' },
  JobStatus: { PENDING: '排队中', RUNNING: '执行中', SUCCEEDED: '成功', FAILED: '失败' },
  AchieveStatus: {
    NOT_STARTED: '未开始',
    IN_PROGRESS: '进行中',
    ACHIEVED: '已达成',
    EXCEEDED: '超额达成',
    NOT_ACHIEVED: '未达成',
    LAGGING: '进度滞后',
  },
  IndicatorLevel: { A: 'A 类', A_PLUS: 'A+ 类', B: 'B 类' },
  IndicatorNature: { CONSTRAINT: '约束性', DEVELOPMENT: '发展性' },
  StatCaliber: {
    INCREMENT: '增量',
    STOCK: '存量',
    GROWTH: '增长率',
    RATIO: '占比',
    DIRECT_PERCENT: '直填%',
    BOOLEAN: '是否',
  },
}

const props = defineProps<{
  /** 枚举名，如 SubmissionStatus */
  kind: string
  /** 枚举值，如 PENDING_REVIEW；未知值按原值展示 */
  value: string
}>()

const userStore = useUserStore()

const style = computed<TagStyle>(() => STATUS_TAG[props.kind]?.[props.value] ?? { type: 'info' })

const label = computed(
  () =>
    userStore.enums[props.kind]?.[props.value] ??
    DEFAULT_LABELS[props.kind]?.[props.value] ??
    props.value,
)
</script>

<template>
  <el-tag
    size="small"
    :type="style.type"
    :effect="style.effect ?? 'light'"
    :class="style.cls"
    :round="style.round ?? false"
  >
    {{ label }}
  </el-tag>
</template>
