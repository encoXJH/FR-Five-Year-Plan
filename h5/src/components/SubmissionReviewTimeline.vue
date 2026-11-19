<script setup lang="ts">
import StatusTag from '@/components/StatusTag.vue'
import { formatDateTime } from '@/utils/format'
import { formatMetricValue } from '@/utils/submission'
import type { StatCaliber } from '@/types/enums'
import type { ReviewRecord } from '@/types/submission'

defineProps<{
  reviews: ReviewRecord[]
  caliber: StatCaliber
  unitLabel?: string | null
}>()
</script>

<template>
  <div class="review-timeline">
    <div v-for="review in reviews" :key="review.id" class="review-entry">
      <div class="review-entry__head">
        <StatusTag
          v-if="review.reviewNode"
          kind="ReviewNode"
          :value="review.reviewNode"
        />
        <span v-else class="review-legacy">历史审核</span>
        <span class="chip" :class="review.result === 'APPROVED' ? 'chip--success' : 'chip--danger'">
          {{ review.result === 'APPROVED' ? '通过' : '驳回' }}
        </span>
        <span v-if="review.isProxy" class="chip chip--warning">代</span>
      </div>
      <div class="small muted review-entry__meta">
        {{ review.reviewer?.name ?? '审核人' }} · {{ formatDateTime(review.createdAt) }}
      </div>
      <div v-if="review.comment" class="small review-comment">“{{ review.comment }}”</div>
      <div v-if="review.approvedValue" class="small review-approved">
        认定值：{{ formatMetricValue(review.approvedValue, caliber, unitLabel) }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.review-timeline {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  margin-top: 8px;
}
.review-entry {
  min-width: 0;
  padding: 8px 10px;
  border-left: 2px solid var(--brand-light);
  border-radius: 0 8px 8px 0;
  background: #f8fafd;
  overflow-wrap: anywhere;
}
.review-entry__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.review-entry__meta {
  margin-top: 4px;
}
.review-comment {
  margin-top: 4px;
  line-height: 18px;
}
.review-approved {
  margin-top: 4px;
  color: var(--brand);
}
.review-legacy {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
}
</style>
