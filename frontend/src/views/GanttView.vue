<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorMessage, executionApi, optionsApi, planningApi } from '@/api'
import type { ConstructionPlan, Direction, GanttNode, ID, Milestone, Organization, Submission, UserAccount } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { statusLabel } from '@/utils/dictionaries'
import { formatDateTime, remainingGap } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import PlanSelect from '@/components/PlanSelect.vue'
import StatusTag from '@/components/StatusTag.vue'
import ValueDisplay from '@/components/ValueDisplay.vue'
import MaterialPanel from '@/components/MaterialPanel.vue'

const auth = useAuthStore()
const route = useRoute()
const nodes = ref<GanttNode[]>([])
const plan = ref<ConstructionPlan>()
const directions = ref<Direction[]>([])
const colleges = ref<Organization[]>([])
const owners = ref<UserAccount[]>([])
const loading = ref(false)
const error = ref('')
const query = reactive({ plan_id: '' as ID, direction_id: undefined as ID | undefined, college_org_id: undefined as ID | undefined, owner_user_id: undefined as ID | undefined, indicator_type_code: '', target_year: undefined as number | undefined, milestone_status: '' })
const years = computed(() => {
  if (query.target_year) return [query.target_year]
  if (!plan.value) return [2026, 2027, 2028, 2029, 2030]
  return Array.from({ length: plan.value.end_year - plan.value.start_year + 1 }, (_, index) => plan.value!.start_year + index)
})
const drawer = ref(false)
const drawerNode = ref<GanttNode>()
const drawerMilestone = ref<Milestone>()
const detail = ref<Milestone & { submissions?: Submission[]; adjustments?: Record<string, unknown>[] }>()
const detailLoading = ref(false)
const latestSubmission = computed(() => detail.value?.submissions?.[0] || drawerMilestone.value?.latest_submission)
async function load() {
  if (!query.plan_id) return
  loading.value = true; error.value = ''
  try {
    const [p, data] = await Promise.all([planningApi.plan(query.plan_id), executionApi.gantt(query)])
    plan.value = p
    nodes.value = Array.isArray(data) ? data : (data as unknown as { items?: GanttNode[] }).items || []
  } catch (cause) { error.value = errorMessage(cause); nodes.value = [] } finally { loading.value = false }
}
async function changePlan() {
  query.direction_id = undefined
  if (query.plan_id) {
    try { directions.value = (await planningApi.directions({ plan_id: query.plan_id, page_size: 100 })).items } catch { directions.value = [] }
  }
  await load()
}
function milestone(node: GanttNode, year: number) { return node.milestones?.find((item) => item.target_year === year) }
function collectTasks(node: GanttNode): GanttNode[] { return node.node_type === 'TASK' ? [node] : (node.children || []).flatMap(collectTasks) }
function statusCounts(node: GanttNode, year: number) {
  const counts: Record<string, number> = {}
  collectTasks(node).forEach((task) => { const item = milestone(task, year); if (item) counts[item.milestone_status] = (counts[item.milestone_status] || 0) + 1 })
  return counts
}
function rate(item: Milestone) {
  if (item.completion_rate !== null && item.completion_rate !== undefined) return Number(item.completion_rate)
  if (item.current_target_value === null || item.current_target_value === undefined || Number(item.current_target_value) === 0 || item.recognized_value === null || item.recognized_value === undefined) return null
  return Number(item.recognized_value) / Number(item.current_target_value) * 100
}
function progress(item: Milestone) {
  if (item.milestone_status === 'COMPLETED') return 100
  const value = rate(item)
  return value === null ? 0 : Math.min(Math.max(value, 0), 100)
}
function progressOf(node: GanttNode, year: number) { const item = milestone(node, year); return item ? progress(item) : 0 }
function progressText(node: GanttNode, year: number) {
  const item = milestone(node, year)
  if (!item) return ''
  if (item.milestone_status === 'COMPLETED') return '已完成'
  if (item.milestone_status === 'UNDER_REVIEW') return '审核中'
  if (item.milestone_status === 'RETURNED') return '已退回'
  if (item.milestone_status === 'RECOGNIZED_UNMET') return '未达标'
  if (item.milestone_status === 'PENDING_REPORT') return '待填报'
  if (item.milestone_status === 'DRAFT') return '填报草稿'
  return progress(item) > 0 ? '进行中' : '未开始'
}
function taskProgress(node: GanttNode) {
  const items = node.milestones || []
  if (!items.length) return 0
  const numericItems = items.filter((item) => item.current_target_value !== null && item.current_target_value !== undefined)
  if (['CUMULATIVE', 'ANNUAL_QUOTA', 'FINAL'].includes(node.calc_method || '') && numericItems.length) {
    const target = numericItems.reduce((sum, item) => sum + Number(item.current_target_value), 0)
    const actual = numericItems.reduce((sum, item) => sum + Number(item.recognized_value ?? 0), 0)
    if (target > 0) return Math.min(Math.max(actual / target * 100, 0), 100)
  }
  return Math.min(items.filter((item) => item.milestone_status === 'COMPLETED').length / items.length * 100, 100)
}
function ganttClass(item: Milestone, node: GanttNode) { return { completed: item.milestone_status === 'COMPLETED', review: item.milestone_status === 'UNDER_REVIEW', returned: item.milestone_status === 'RETURNED', unmet: item.milestone_status === 'RECOGNIZED_UNMET', adjusted: Boolean(item.has_adjustment), 'boolean-node': ['BOOLEAN', 'FINAL'].includes(node.calc_method || '') } }
async function openNode(node: GanttNode, item: Milestone) {
  drawerNode.value = node; drawerMilestone.value = item; drawer.value = true; detailLoading.value = true; detail.value = undefined
  try { detail.value = await executionApi.milestone(item.id) } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { detailLoading.value = false }
}
function clearFilters() { const planId = query.plan_id; Object.assign(query, { plan_id: planId, direction_id: undefined, college_org_id: undefined, owner_user_id: undefined, indicator_type_code: '', target_year: undefined, milestone_status: '' }); load() }
function rowKey(node: GanttNode) { return String(node.node_key || `${node.node_type}-${node.id}`) }
onMounted(async () => {
  if (route.query.plan_id) { query.plan_id = String(route.query.plan_id); await changePlan() }
  if (!auth.roles.includes('COLLEGE_OWNER')) {
    try { const [c, u] = await Promise.all([optionsApi.organizations({ org_type: 'COLLEGE' }), optionsApi.users({ role: 'COLLEGE_OWNER' })]); colleges.value = c.items; owners.value = u.items } catch { /* 服务端仍执行范围过滤 */ }
  }
})
</script>
<template>
  <div class="page-container">
    <PageHeader title="分层甘特图" description="主要建设方向 → 关键产出指标 → 学院任务树与年度时间轴。任务行显示总体进度条，每个年度显示该年度进度与百分比；跨指标仅统计任务及状态数量，不对不同单位做简单平均或加总。" />
    <div class="filter-bar" style="margin-bottom:14px;border-radius:8px"><div style="width:290px"><PlanSelect v-model="query.plan_id" placeholder="选择计划（必选）" @change="changePlan" /></div><el-select v-model="query.direction_id" placeholder="建设方向" clearable filterable style="width:175px"><el-option v-for="item in directions" :key="item.id" :value="item.id" :label="item.direction_name" /></el-select><el-select v-if="!auth.roles.includes('COLLEGE_OWNER')" v-model="query.college_org_id" placeholder="学院" clearable style="width:160px"><el-option v-for="item in colleges" :key="item.id" :value="item.id" :label="item.org_name" /></el-select><el-select v-if="!auth.roles.includes('COLLEGE_OWNER')" v-model="query.owner_user_id" placeholder="负责人" clearable filterable style="width:130px"><el-option v-for="item in owners" :key="item.id" :value="item.id" :label="item.user_name" /></el-select><el-select v-model="query.indicator_type_code" placeholder="指标类型" clearable style="width:115px"><el-option label="约束性" value="CONSTRAINED" /><el-option label="发展性" value="DEVELOPMENT" /><el-option label="特色" value="FEATURED" /></el-select><el-select v-model="query.target_year" placeholder="全部年度" clearable style="width:110px"><el-option v-for="year in plan ? Array.from({length:plan.end_year-plan.start_year+1},(_,i)=>plan!.start_year+i) : [2026,2027,2028,2029,2030]" :key="year" :value="year" :label="year" /></el-select><el-select v-model="query.milestone_status" placeholder="里程碑状态" clearable style="width:145px"><el-option v-for="status in ['NOT_STARTED','PENDING_REPORT','DRAFT','UNDER_REVIEW','COMPLETED','RECOGNIZED_UNMET','RETURNED']" :key="status" :value="status" :label="statusLabel(status)" /></el-select><el-button type="primary" plain :disabled="!query.plan_id" @click="load"><el-icon><Search /></el-icon>查询</el-button><el-button @click="clearFilters">清除筛选</el-button></div>
    <div v-if="error" class="state-block"><h3>甘特数据加载失败</h3><p>{{ error }}</p><el-button type="primary" @click="load">重新加载</el-button></div>
    <div v-else-if="!query.plan_id" class="state-block"><h3>请选择建设计划</h3><p>甘特数据与当前用户的数据权限一致，不会展示未授权节点。</p></div>
    <div v-else-if="!loading && !nodes.length" class="state-block"><h3>当前筛选下无任务</h3><p>筛选：{{ query.direction_id ? '指定方向 · ' : '' }}{{ query.college_org_id ? '指定学院 · ' : '' }}{{ query.target_year ? `${query.target_year} 年 · ` : '' }}{{ query.milestone_status ? statusLabel(query.milestone_status) : '全部状态' }}</p><el-button @click="clearFilters">清除筛选</el-button></div>
    <section v-else class="panel gantt-scroll" v-loading="loading"><el-table :data="nodes" :row-key="rowKey" default-expand-all :tree-props="{ children: 'children' }" class="gantt-table" :indent="18"><el-table-column label="方向 / 指标 / 学院任务" fixed="left" min-width="290"><template #default="{ row }"><div class="gantt-name"><el-tag size="small" effect="plain">{{ row.node_type === 'DIRECTION' ? '方向' : row.node_type === 'INDICATOR' ? '指标' : '任务' }}</el-tag><div><div class="gantt-label" :title="row.name">{{ row.name }}</div><div v-if="row.code" class="mono subtle">{{ row.code }}</div><div v-if="row.node_type === 'TASK'" class="task-progress"><div class="task-progress-track"><div class="task-progress-fill" :class="{ done: taskProgress(row) >= 100 }" :style="{ width: `${taskProgress(row)}%` }" /></div><span class="task-progress-text">总进度 {{ Math.round(taskProgress(row)) }}%</span></div></div></div></template></el-table-column><el-table-column v-for="year in years" :key="year" :label="`${year} 年`" min-width="155" align="center"><template #default="{ row }"><template v-if="row.node_type === 'TASK'"><div v-if="milestone(row, year)" class="gantt-node-card" :class="ganttClass(milestone(row, year)!, row)" @click="openNode(row, milestone(row, year)!)"><div class="gantt-values"><span>目标 <ValueDisplay :value="milestone(row, year)!.current_target_value" :text="milestone(row, year)!.current_target_text" :bool-value="milestone(row, year)!.current_target_bool" :unit="row.unit_name" /></span><span>认定 <ValueDisplay :value="milestone(row, year)!.recognized_value" :text="milestone(row, year)!.recognized_text" :bool-value="milestone(row, year)!.recognized_bool" :unit="row.unit_name" empty-text="—" /></span></div><div class="gantt-status"><span>{{ statusLabel(milestone(row, year)!.milestone_status) }}</span><span v-if="rate(milestone(row, year)!) !== null && rate(milestone(row, year)!)! > 100">超额 +{{ (rate(milestone(row, year)!)! - 100).toFixed(1) }}%</span></div><div class="gantt-progress-label"><span>{{ progressText(row, year) }}</span><span>{{ Math.round(progressOf(row, year)) }}%</span></div><div class="gantt-progress-track"><div class="gantt-progress-fill" :style="{ width: `${progressOf(row, year)}%` }" /></div></div><span v-else class="value-empty small">未设置年度目标</span></template><div v-else class="status-counts"><span v-for="(count, status) in statusCounts(row, year)" :key="status" class="status-count">{{ statusLabel(String(status)) }} {{ count }}</span><span v-if="!Object.keys(statusCounts(row, year)).length" class="value-empty small">无里程碑</span></div></template></el-table-column></el-table></section>
    <el-drawer v-model="drawer" size="510px" :title="`${drawerMilestone?.target_year} 年里程碑详情`"><div class="drawer-loading-body" v-loading="detailLoading"><template v-if="drawerNode && drawerMilestone"><h3 class="section-title">{{ drawerNode.name }}</h3><div class="inline-meta" style="margin-bottom:16px"><StatusTag :status="drawerMilestone.milestone_status" /><el-tag v-if="drawerMilestone.has_adjustment" type="warning" size="small">已调整 {{ drawerMilestone.adjustment_label || '' }}</el-tag><StatusTag :status="drawerNode.calc_method" /></div><dl class="description-grid two-cols"><div class="description-item"><dt>原年度目标</dt><dd><ValueDisplay :value="drawerMilestone.original_target_value" :text="drawerMilestone.original_target_text" :bool-value="drawerMilestone.original_target_bool" :unit="drawerNode.unit_name" /></dd></div><div class="description-item"><dt>调整后目标</dt><dd><ValueDisplay :value="drawerMilestone.current_target_value" :text="drawerMilestone.current_target_text" :bool-value="drawerMilestone.current_target_bool" :unit="drawerNode.unit_name" /></dd></div><div class="description-item"><dt>认定完成值</dt><dd><ValueDisplay :value="drawerMilestone.recognized_value" :text="drawerMilestone.recognized_text" :bool-value="drawerMilestone.recognized_bool" :unit="drawerNode.unit_name" empty-text="尚未认定" /></dd></div><div class="description-item"><dt>未弥补缺口</dt><dd>{{ remainingGap(drawerMilestone.current_target_value, drawerMilestone.recognized_value, drawerMilestone.covered_value) ?? '不适用' }}</dd></div><div class="description-item"><dt>已补位量</dt><dd>{{ drawerMilestone.covered_value ?? 0 }}</dd></div><div class="description-item"><dt>目标版本</dt><dd>V{{ drawerMilestone.target_version_no }}</dd></div></dl><section class="drawer-section"><h4 class="section-title">最近填报与佐证</h4><template v-if="latestSubmission"><div class="inline-meta" style="margin-bottom:10px"><span>V{{ latestSubmission.version_no }}</span><StatusTag :status="latestSubmission.submission_status" /><span>{{ formatDateTime(latestSubmission.submitted_at) }}</span></div><p class="long-text small">{{ latestSubmission.completion_desc }}</p><MaterialPanel :submission-id="latestSubmission.id" :materials="latestSubmission.materials || []" readonly /></template><el-empty v-else description="尚无填报版本" :image-size="60" /></section><section v-if="detail?.adjustments?.length" class="drawer-section"><h4 class="section-title">调整关联链</h4><pre v-for="(adjustment, index) in detail.adjustments" :key="index" class="snapshot-box">{{ JSON.stringify(adjustment, null, 2) }}</pre></section></template></div></el-drawer>
  </div>
</template>
<style scoped>.gantt-name{display:flex;align-items:flex-start;gap:8px}.gantt-name>div{min-width:0}.gantt-label{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:500}.task-progress{display:flex;align-items:center;gap:7px;max-width:210px;margin-top:5px}.task-progress-track{flex:1;height:5px;overflow:hidden;background:#e6ebf3;border-radius:3px}.task-progress-fill{height:100%;background:#4c7be0;border-radius:3px;transition:width .25s ease}.task-progress-fill.done{background:#2b9b73}.task-progress-text{color:#758297;font-size:10px;white-space:nowrap}.two-cols{grid-template-columns:1fr 1fr}.drawer-loading-body{min-height:180px}.drawer-section{margin-top:20px;padding-top:18px;border-top:1px solid #e7ebf1}</style>
