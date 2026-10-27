<script setup lang="ts">
import { computed } from 'vue'
import { diffCell, groupSnapshot, groupTotal } from '@/utils/adjustment'
import type { SnapshotGroup } from '@/utils/adjustment'
import { formatTargetValue } from '@/utils/indicator'
import { EMPTY_DISPLAY, formatNumber } from '@/utils/format'
import type { AdjustmentSnapshot } from '@/types/adjustment'
import type { StatCaliber } from '@/types/enums'

interface CompareRow {
  key: string
  group: SnapshotGroup
  phase: 'before' | 'after'
}

const props = withDefaults(
  defineProps<{
    /** 调整前后快照（试算结果或详情快照） */
    snapshot: AdjustmentSnapshot | null
    /** 规划期年度列 */
    years: number[]
    caliber?: StatCaliber
    /** 标注来源：试算 / 快照（UI 规范 10.2） */
    caption?: string
  }>(),
  { caliber: 'INCREMENT', caption: '' },
)

const groups = computed(() =>
  props.snapshot ? groupSnapshot(props.snapshot.before, props.snapshot.after) : [],
)

const rows = computed<CompareRow[]>(() =>
  groups.value.flatMap((group) => [
    { key: `${group.taskCode}-before`, group, phase: 'before' as const },
    { key: `${group.taskCode}-after`, group, phase: 'after' as const },
  ]),
)

/** 任务列两行合并 */
function spanMethod({ row, columnIndex }: { row: CompareRow; columnIndex: number }) {
  if (columnIndex === 0) {
    return row.phase === 'before' ? [2, 1] : [0, 0]
  }
  return [1, 1]
}

function cellValue(row: CompareRow, year: number): number | boolean | null {
  const source = row.phase === 'before' ? row.group.before : row.group.after
  return source[year] ?? null
}

function cellDiff(row: CompareRow, year: number) {
  return diffCell(row.group.before[year] ?? null, row.group.after[year] ?? null)
}

function cellTotal(row: CompareRow): number | null {
  const total = row.phase === 'before' ? row.group.totalBefore : row.group.totalAfter
  return total ?? groupTotal(row.phase === 'before' ? row.group.before : row.group.after)
}

function cellClass(diff: { changed: boolean }): string {
  return diff.changed ? 'cell-changed' : ''
}

interface ConservationLine {
  taskCode: string
  before: number | null
  after: number | null
  balanced: boolean
}

/** 底部守恒校验行（UI 规范 10.2：五年总量 before → after；优先总量快照条目） */
const conservationLines = computed<ConservationLine[]>(() =>
  groups.value.map((group) => {
    const before = group.totalBefore ?? groupTotal(group.before)
    const after = group.totalAfter ?? groupTotal(group.after)
    return {
      taskCode: group.taskCode,
      before,
      after,
      balanced: before !== null && after !== null && before === after,
    }
  }),
)

function totalText(value: number | null): string {
  return value === null ? EMPTY_DISPLAY : formatNumber(value)
}
</script>

<template>
  <div class="compare-table">
    <div v-if="caption" class="compare-caption">{{ caption }}</div>
    <el-table :data="rows" border size="small" :span-method="spanMethod">
      <el-table-column label="任务" min-width="150">
        <template #default="{ row }">
          <span class="mono task-code">{{ row.group.taskCode }}</span>
          <el-tag v-if="row.group.isNew" type="success" size="small" effect="plain" class="new-tag">
            新建
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="阶段" width="52" align="center">
        <template #default="{ row }">
          {{ row.phase === 'before' ? '前' : '后' }}
        </template>
      </el-table-column>
      <el-table-column v-for="year in years" :key="year" :label="`${year}`" width="70" align="right">
        <template #default="{ row }">
          <div class="year-cell" :class="cellClass(cellDiff(row, year))">
            <span :class="['cell-value', row.phase]">
              {{ formatTargetValue(cellValue(row, year), caliber) }}
            </span>
            <template v-if="row.phase === 'after' && cellDiff(row, year).changed">
              <span v-if="cellDiff(row, year).increased" class="arrow up">↑</span>
              <span v-else-if="cellDiff(row, year).decreased" class="arrow down">↓</span>
            </template>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="五年" width="80" align="right">
        <template #default="{ row }">
          <div
            class="year-cell"
            :class="cellClass(diffCell(
              row.group.totalBefore ?? groupTotal(row.group.before),
              row.group.totalAfter ?? groupTotal(row.group.after),
            ))"
          >
            <span :class="['cell-value', row.phase]">{{ totalText(cellTotal(row)) }}</span>
          </div>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :image-size="60" description="暂无对比数据" />
      </template>
    </el-table>

    <div v-if="conservationLines.length > 0" class="conservation">
      <div
        v-for="line in conservationLines"
        :key="line.taskCode"
        :class="['conservation-line', line.balanced ? 'is-ok' : 'is-bad']"
      >
        <span class="mono">{{ line.taskCode }}</span>
        五年总量：{{ totalText(line.before) }} → {{ totalText(line.after) }}
        <template v-if="line.balanced"> ✓ 守恒</template>
        <template v-else> ✗ 不守恒（70002）</template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.compare-caption {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: var(--space-2);
}

.task-code {
  font-weight: 600;
  font-size: 13px;
}

.new-tag {
  margin-left: var(--space-1);
}

.mono {
  font-family: var(--app-font-mono);
}

.year-cell {
  font-variant-numeric: tabular-nums;
}

/* 变化单元格（UI 规范 10.2）：浅橙底，前值灰色删除线，后值加粗 + ↑↓ */
.year-cell.cell-changed {
  background-color: #fdf6ec;
  margin: -4px -8px;
  padding: 4px 8px;
}

.cell-value.before {
  color: var(--el-text-color-secondary);
}

.year-cell.cell-changed .cell-value.before {
  text-decoration: line-through;
}

.cell-value.after {
  font-weight: 400;
}

.year-cell.cell-changed .cell-value.after {
  font-weight: 700;
}

.arrow {
  margin-left: 2px;
  font-weight: 700;
}

.arrow.up {
  color: var(--el-color-success);
}

.arrow.down {
  color: var(--el-color-danger);
}

.conservation {
  margin-top: var(--space-2);
  font-size: 12px;
}

.conservation-line.is-ok {
  color: var(--el-color-success);
}

.conservation-line.is-bad {
  color: var(--el-color-danger);
}
</style>
