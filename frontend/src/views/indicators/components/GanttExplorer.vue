<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import IndicatorGantt from './IndicatorGantt.vue'
import { getPlanGantt } from '@/api/gantt'
import { updateMilestone } from '@/api/milestone'
import { ApiError } from '@/utils/request'
import { useUserStore } from '@/stores/user'
import { formatPercent } from '@/utils/format'
import { useAutoRefresh } from '@/utils/autoRefresh'
import type { GanttMilestone, GanttNode, PlanGantt } from '@/types/gantt'

const props = defineProps<{ planId: string; indicatorId?: string }>()
const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const data = ref<PlanGantt | null>(null)
const error = ref<{ message: string; code?: number; requestId?: string } | null>(null)
const keyword = ref('')
const selected = ref<GanttNode | null>(null)
const saving = ref(false)
let sequence = 0

const canEdit = computed(() => userStore.can('allocation:edit'))
const filteredNodes = computed(() => {
  const nodes = data.value?.nodes ?? []
  const value = keyword.value.trim().toLowerCase()
  if (!value) return nodes
  const matched = new Set(nodes.filter((node) => `${node.code} ${node.name} ${node.unitName ?? ''}`.toLowerCase().includes(value)).map((node) => node.id))
  for (const node of nodes) if (node.parentId && matched.has(node.id)) matched.add(node.parentId)
  return nodes.filter((node) => matched.has(node.id))
})

async function load(silent = false) {
  const current = ++sequence
  if (!silent) loading.value = true
  error.value = null
  try {
    const result = await getPlanGantt(props.planId, props.indicatorId)
    if (current !== sequence) return
    data.value = result
    selected.value = null
  } catch (cause) {
    if (current !== sequence) return
    if (silent) return
    data.value = null
    error.value = cause instanceof ApiError
      ? { message: cause.message, code: cause.code, requestId: cause.requestId }
      : { message: cause instanceof Error ? cause.message : '甘特图加载失败，请重试' }
  } finally {
    if (!silent && current === sequence) loading.value = false
  }
}

function segmentDrag(payload: { node: GanttNode; fromYear: number; toYear: number }) {
  if (!canEdit.value || payload.node.isHistorical) return
  if (!['INCREMENT', 'BOOLEAN'].includes(payload.node.caliber ?? '')) {
    ElMessage.warning('该统计口径不支持延期拖拽，请使用目标调整')
    return
  }
  if (payload.toYear <= payload.fromYear) {
    ElMessage.warning('延期目标年份必须晚于原年度')
    return
  }
  void router.push({ path: '/adjustments/apply', query: { taskId: payload.node.id, type: 'POSTPONE', fromYear: payload.fromYear, toYear: payload.toYear } })
}

async function milestoneDrag(payload: { milestone: GanttMilestone; toYear: number }) {
  if (!canEdit.value) return
  try {
    await ElMessageBox.confirm(`将“${payload.milestone.name}”关联年度调整为 ${payload.toYear} 年？`, '调整里程碑', { confirmButtonText: '确认调整', cancelButtonText: '取消' })
    saving.value = true
    await updateMilestone(payload.milestone.id, { relatedYear: payload.toYear, version: payload.milestone.version })
    ElMessage.success('里程碑年度已调整')
    await load()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error('里程碑调整失败，已恢复原位置')
  } finally {
    saving.value = false
  }
}

watch(() => [props.planId, props.indicatorId], () => void load(), { immediate: true })

useAutoRefresh(() => load(true), {
  intervalMs: 60_000,
  skip: () => saving.value || selected.value !== null,
})
</script>

<template>
  <section class="gantt-explorer" v-loading="loading || saving">
    <div class="explorer-tools">
      <el-input v-model="keyword" clearable placeholder="搜索指标、任务或学院" class="gantt-search" />
      <el-tag v-if="data?.graph.truncated" type="warning" effect="plain">当前为角色数据范围视图</el-tag>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <el-result v-if="error" icon="error" title="甘特图加载失败">
      <template #extra><el-button type="primary" @click="load">重新加载</el-button></template>
      <template #sub-title>{{ error.message }}<template v-if="error.requestId">（请求编号：{{ error.requestId }}）</template></template>
    </el-result>
    <template v-else-if="data">
      <el-alert v-if="data.graph.message && data.dependencies.length > 0" :title="data.graph.message" type="warning" :closable="false" class="graph-message" />
      <IndicatorGantt
        :years="data.years"
        :nodes="filteredNodes"
        :milestones="data.milestones"
        :dependencies="data.dependencies"
        :critical-task-ids="data.criticalPath.taskIds"
        :critical-dependency-ids="data.criticalPath.dependencyIds"
        :readonly="!canEdit"
        @select="selected = $event"
        @segment-drag="segmentDrag"
        @milestone-drag="milestoneDrag"
      />
      <div v-if="selected" class="node-detail">
        <div><strong class="mono">{{ selected.code }}</strong><span>{{ selected.name }}</span><el-button link type="primary" @click="selected = null">关闭</el-button></div>
        <p>{{ selected.unitName || '校级指标' }} · {{ selected.ownerName || '未指定负责人' }} · 累计进度 {{ formatPercent(selected.cumulativeRate) }}</p>
      </div>
    </template>
  </section>
</template>

<style scoped>
.gantt-explorer { min-height:240px; }
.explorer-tools { display:flex; align-items:center; gap:10px; margin-bottom:14px; }
.gantt-search { width:280px; }
.graph-message { margin-bottom:12px; }
.node-detail { margin-top:14px; padding:12px 16px; border-left:3px solid #2B5BC0; background:#F6F8FC; }
.node-detail > div:first-child { display:flex; align-items:center; gap:10px; }
.node-detail > div:first-child .el-button { margin-left:auto; }
.node-detail p { margin:6px 0; color:#606266; font-size:13px; }
@media(max-width:768px) { .explorer-tools { flex-wrap:wrap; } .gantt-search { width:100%; } }
</style>
