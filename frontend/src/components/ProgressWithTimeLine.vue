<script setup lang="ts">
import { computed } from 'vue'
import { formatPercent } from '@/utils/format'
import { progressPercentage } from '@/utils/task'

/**
 * 完成率进度条（界面设计规范 7.13）：
 * - 颜色按达成状态（可显式传 color，默认 <100% 主色、≥100% 成功色）
 * - 超过 100% 填满并显示「超额」标签
 */
const props = defineProps<{
  /** 完成率（小数，如 0.62 = 62%）；null 显示 — */
  rate: number | null | undefined
  /** 滞后预警：为 true 时追加 ⚠ 标签 */
  lagging?: boolean
}>()

const hasRate = computed(() => typeof props.rate === 'number' && Number.isFinite(props.rate))
const percentage = computed(() => progressPercentage(props.rate))
const barColor = computed(() => ((props.rate ?? 0) >= 1 ? '#67C23A' : '#2B5BC0'))
const exceeded = computed(() => (props.rate ?? 0) > 1)
</script>

<template>
  <span v-if="!hasRate" class="empty-text">—</span>
  <div v-else class="progress-with-time">
    <div class="bar-wrap">
      <el-progress :percentage="percentage" :stroke-width="8" :color="barColor">
        <span class="rate-text">{{ formatPercent(rate) }}</span>
      </el-progress>
    </div>
    <el-tag v-if="exceeded" type="success" effect="dark" size="small" class="flag-tag">超额</el-tag>
    <el-tag v-else-if="lagging" type="warning" effect="dark" size="small" class="flag-tag">⚠ 滞后</el-tag>
  </div>
</template>

<style scoped>
.progress-with-time {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.bar-wrap {
  position: relative;
  flex: 1;
  min-width: 0;
}

.bar-wrap :deep(.el-progress) {
  width: 100%;
}

.rate-text {
  font-variant-numeric: tabular-nums;
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.flag-tag {
  flex-shrink: 0;
}

.empty-text {
  color: var(--el-text-color-placeholder);
}
</style>
