<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage, executionApi, optionsApi } from '@/api'
import type { CollegeTask, ID, Organization } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { canTaskAction } from '@/utils/permissions'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import PlanSelect from '@/components/PlanSelect.vue'
import StatusTag from '@/components/StatusTag.vue'
import ValueDisplay from '@/components/ValueDisplay.vue'
import RemoteState from '@/components/RemoteState.vue'
const router = useRouter()
const auth = useAuthStore()
const role = computed(() => auth.roles.includes('ADMIN') ? 'ADMIN' : auth.roles.includes('COLLEGE_OWNER') ? 'COLLEGE_OWNER' : auth.roles.includes('SCHOOL_OWNER') ? 'SCHOOL_OWNER' : 'SCHOOL_LEADER')
const rows = ref<CollegeTask[]>([])
const colleges = ref<Organization[]>([])
const loading = ref(false)
const error = ref('')
const total = ref(0)
const query = reactive({ page: 1, page_size: 20, keyword: '', plan_id: undefined as ID | undefined, task_status: '', college_org_id: undefined as ID | undefined, target_year: undefined as number | undefined })
async function load() { loading.value = true; error.value = ''; try { const result = await executionApi.tasks(query); rows.value = result.items; total.value = result.total } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
async function receive(row: CollegeTask) { await ElMessageBox.confirm(`确认接收“${row.indicator_name}”本学院任务？接收后将按现有目标执行，不可由校级直接撤销。`, '接收学院任务', { type: 'warning' }); try { await executionApi.receiveTask(row.id, row.lock_version); ElMessage.success('任务已接收，进入执行中'); await load() } catch (cause) { ElMessage.error(errorMessage(cause)) } }
async function revoke(row: CollegeTask) { const result = await ElMessageBox.prompt('接收前可撤销下发，撤销后任务回到草稿并保留日志。请填写撤销原因。', '撤销任务下发', { type: 'warning', inputValidator: (value) => Boolean(value?.trim()) || '撤销原因必填' }); try { await executionApi.revokeTask(row.id, result.value, row.lock_version); ElMessage.success('任务下发已撤销'); await load() } catch (cause) { ElMessage.error(errorMessage(cause)) } }
function clearFilters() { Object.assign(query, { page: 1, keyword: '', plan_id: undefined, task_status: '', college_org_id: undefined, target_year: undefined }); load() }
function progress(row: CollegeTask) {
  const value = Number(row.progress_percent ?? 0)
  return Number.isFinite(value) ? Math.min(Math.max(value, 0), 100) : 0
}
function progressLabel(row: CollegeTask) {
  if (!row.milestone_count) return '无年度里程碑'
  if (row.task_status === 'PENDING_RECEIVE') return '待接收'
  if (row.task_status === 'COMPLETED' || progress(row) >= 100) return '已完成'
  if (row.task_status === 'REVOKED') return '已撤销'
  return '执行中'
}
function progressClass(row: CollegeTask) {
  return { completed: row.task_status === 'COMPLETED' || progress(row) >= 100, pending: row.task_status === 'PENDING_RECEIVE' }
}
onMounted(async () => { await load(); if (role.value !== 'COLLEGE_OWNER') { try { colleges.value = (await optionsApi.organizations({ org_type: 'COLLEGE' })).items } catch { colleges.value = [] } } })
</script>
<template>
  <div class="page-container">
    <PageHeader title="学院任务" description="学院仅处理本学院被下发任务；校级可查看本项目全部任务。接收、填报、审核和调整均遵循后端数据权限与状态机。" />
    <div class="filter-bar"><el-input v-model="query.keyword" placeholder="任务代码／指标名称" clearable style="width:230px" @keyup.enter="load"><template #prefix><el-icon><Search /></el-icon></template></el-input><div style="width:280px"><PlanSelect v-model="query.plan_id" placeholder="全部可见计划" /></div><el-select v-model="query.task_status" placeholder="任务状态" clearable style="width:130px"><el-option label="草稿" value="DRAFT" /><el-option label="待接收" value="PENDING_RECEIVE" /><el-option label="执行中" value="IN_PROGRESS" /><el-option label="已完成" value="COMPLETED" /><el-option label="已撤销" value="REVOKED" /></el-select><el-select v-if="role !== 'COLLEGE_OWNER'" v-model="query.college_org_id" placeholder="执行学院" clearable filterable style="width:180px"><el-option v-for="org in colleges" :key="org.id" :value="org.id" :label="org.org_name" /></el-select><el-select v-model="query.target_year" placeholder="建设年度" clearable style="width:120px"><el-option v-for="year in [2026,2027,2028,2029,2030]" :key="year" :value="year" :label="year" /></el-select><el-button type="primary" plain @click="load">查询</el-button><el-button @click="clearFilters">清除</el-button></div>
    <RemoteState :loading="loading" :error="error" :empty="!rows.length" empty-text="当前筛选下无学院任务" empty-description="空列表不会生成伪造进度；可清除筛选后重试。" show-clear @retry="load" @clear="clearFilters"><el-table :data="rows"><el-table-column label="任务／指标" min-width="280"><template #default="{ row }"><button class="link-button" style="text-align:left;font-weight:550" @click="router.push(`/execution/tasks/${row.id}`)">{{ row.indicator_name }}</button><div class="mono subtle" style="margin-top:4px">{{ row.task_code }}</div></template></el-table-column><el-table-column label="任务进度" width="170"><template #default="{ row }"><div class="task-progress"><div class="task-progress-head"><span>{{ progressLabel(row) }}</span><span>{{ Math.round(progress(row)) }}%</span></div><div class="task-progress-track"><div class="task-progress-fill" :class="progressClass(row)" :style="{ width: `${progress(row)}%` }" /></div><div class="task-progress-meta">{{ row.milestone_count ?? 0 }} 个年度 · 已完成 {{ row.completed_milestone_count ?? 0 }} 个</div></div></template></el-table-column><el-table-column prop="plan_name" label="所属计划" min-width="170" show-overflow-tooltip /><el-table-column prop="college_org_name" label="执行学院" width="155" /><el-table-column prop="owner_user_name" label="学院负责人" width="110" /><el-table-column label="五年任务目标" width="125"><template #default="{ row }"><ValueDisplay :value="row.five_year_target_value" :text="row.five_year_target_text" :bool-value="row.five_year_target_bool" :value-kind="row.value_kind" :unit="row.unit_name || row.unit_code" /></template></el-table-column><el-table-column label="状态" width="100"><template #default="{ row }"><StatusTag :status="row.task_status" /></template></el-table-column><el-table-column label="下发／接收时间" width="170"><template #default="{ row }"><div class="small">下发 {{ formatDateTime(row.assigned_at) }}</div><div class="subtle small">接收 {{ formatDateTime(row.received_at) }}</div></template></el-table-column><el-table-column label="操作" width="175" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="router.push(`/execution/tasks/${row.id}`)">任务详情</el-button><el-button v-if="canTaskAction(row, 'receive', role)" link type="success" @click="receive(row)">接收</el-button><el-button v-if="canTaskAction(row, 'revoke', role)" link type="warning" @click="revoke(row)">撤销</el-button></template></el-table-column></el-table><div class="table-footer"><el-pagination v-model:current-page="query.page" v-model:page-size="query.page_size" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div></RemoteState>
  </div>
</template>
<style scoped>
.task-progress-head { display: flex; align-items: center; justify-content: space-between; gap: 6px; color: #68788f; font-size: 11px; }
.task-progress-track { height: 6px; margin-top: 5px; overflow: hidden; background: #e6ebf3; border-radius: 3px; }
.task-progress-fill { height: 100%; background: #4c7be0; border-radius: 3px; transition: width .25s ease; }
.task-progress-fill.completed { background: #2b9b73; }
.task-progress-fill.pending { background: #c3ccda; }
.task-progress-meta { margin-top: 4px; color: #8a97a8; font-size: 10px; }
</style>
