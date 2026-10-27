<script setup lang="ts">
import { CircleCheck, CircleClose } from '@element-plus/icons-vue'
import StatusTag from '@/components/StatusTag.vue'
import { formatDateTime } from '@/utils/format'
import { formatMetricValue } from '@/utils/submission'
import type { StatCaliber } from '@/types/enums'
import type { SubmissionListItem } from '@/types/submission'

/** 填报记录 / 审核意见时间线（UI 规范 7.3、7.8：谁、何时、做了什么、意见；通过 ✓ / 驳回 ✕ 节点图标） */
defineProps<{
  submissions: SubmissionListItem[]
  caliber: StatCaliber
  unitLabel?: string | null
}>()

function timelineType(status: string): 'success' | 'danger' | 'warning' | 'info' | 'primary' {
  switch (status) {
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'PENDING_REVIEW':
      return 'warning'
    default:
      return 'info'
  }
}

/** 节点图标（7.8）：通过 ✓ / 驳回 ✕，其余状态用默认圆点 */
function timelineIcon(status: string) {
  if (status === 'APPROVED') return CircleCheck
  if (status === 'REJECTED') return CircleClose
  return undefined
}
</script>

<template>
  <el-timeline v-if="submissions.length > 0" class="submission-timeline">
    <el-timeline-item
      v-for="item in submissions"
      :key="item.id"
      :type="timelineType(item.status)"
      :icon="timelineIcon(item.status)"
      :timestamp="formatDateTime(item.submittedAt ?? item.createdAt)"
      placement="top"
    >
      <div class="line">
        <span class="value">{{ formatMetricValue(item.value, caliber, unitLabel) }}</span>
        <StatusTag kind="SubmissionStatus" :value="item.status" />
        <el-tooltip v-if="item.isProxy" :content="item.proxyReason ?? '管理员代填'" placement="top">
          <el-tag type="warning" effect="plain" size="small">代</el-tag>
        </el-tooltip>
      </div>
      <div v-if="item.status === 'APPROVED' && item.approvedValue" class="sub-line">
        认定 {{ formatMetricValue(item.approvedValue, caliber, unitLabel) }}
      </div>
      <div v-for="review in item.reviews ?? []" :key="review.id" class="review-comment">
        <span class="reviewer">{{ review.reviewer?.name ?? '审核人' }}</span>
        <el-tag size="small" :type="review.result === 'APPROVED' ? 'success' : 'danger'" effect="plain">
          {{ review.result === 'APPROVED' ? '通过' : '驳回' }}
        </el-tag>
        <el-tag v-if="review.isProxy" type="warning" effect="plain" size="small">代</el-tag>
        <span v-if="review.comment" class="comment">“{{ review.comment }}”</span>
      </div>
    </el-timeline-item>
  </el-timeline>
  <el-empty v-else :image-size="80" description="本年度还没有填报记录" />
</template>

<style scoped>
.submission-timeline {
  padding-left: var(--space-1);
}

.line {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.value {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.sub-line {
  margin-top: var(--space-1);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.review-comment {
  margin-top: var(--space-1);
  font-size: 12px;
  color: var(--el-text-color-regular);
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex-wrap: wrap;
}

.reviewer {
  color: var(--el-text-color-secondary);
}

.comment {
  color: var(--el-text-color-secondary);
}
</style>
