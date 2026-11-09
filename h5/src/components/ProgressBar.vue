<script setup lang="ts">
import { computed } from 'vue'
import { completionLevel } from '@/utils/indicator'

const props = withDefaults(
  defineProps<{
    /** 完成率小数 0.62 */
    rate?: number | null
    /** 是否展示百分比文字 */
    showText?: boolean
    height?: number
    /** 无目标时是否展示 — */
    showEmpty?: boolean
  }>(),
  { showText: false, height: 8, showEmpty: true },
)

const percent = computed(() => {
  if (typeof props.rate !== 'number' || !Number.isFinite(props.rate)) return 0
  return Math.max(0, Math.min(100, Math.round(props.rate * 1000) / 10))
})

const level = computed(() =>
  completionLevel(props.rate ?? null, props.rate !== null && props.rate !== undefined),
)

const text = computed(() => {
  if (props.rate === null || props.rate === undefined) return '—'
  return `${percent.value.toFixed(1)}%`
})
</script>

<template>
  <div class="progress-wrap">
    <div class="progress" :style="{ height: `${height}px` }">
      <div
        class="progress__bar"
        :class="level ? `is-${level}` : ''"
        :style="{ width: `${percent}%` }"
      />
    </div>
    <span v-if="showText" class="progress-text">{{ showEmpty || rate !== null ? text : '—' }}</span>
  </div>
</template>

<style scoped>
.progress-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.progress-text {
  flex: 0 0 auto;
  min-width: 44px;
  color: var(--text-secondary);
  font-size: 12px;
  text-align: right;
}
</style>
