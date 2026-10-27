<script setup lang="ts">
import type { RollupMode } from '@/types/enums'

/** A 行汇总方式标记（UI 规范 7.1：AUTO_SUM 显示 Σ，MANUAL 显示「认定」角标） */
defineProps<{
  mode: RollupMode
}>()
</script>

<template>
  <el-tooltip v-if="mode === 'AUTO_SUM'" content="自动汇总 = A+ 之和" placement="top">
    <span class="rollup-sigma">Σ</span>
  </el-tooltip>
  <el-tag v-else size="small" effect="plain" class="rollup-manual">认定</el-tag>
</template>

<style scoped>
.rollup-sigma {
  margin-left: var(--space-1);
  color: var(--el-color-primary);
  font-weight: 600;
  cursor: default;
}

.rollup-manual {
  margin-left: var(--space-1);
}
</style>
