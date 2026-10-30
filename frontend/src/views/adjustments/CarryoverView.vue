<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Link, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adjustmentApi, errorMessage, executionApi } from '@/api'
import type { Carryover, CollegeTask, Compensation, ID } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime, remainingGap } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import RemoteState from '@/components/RemoteState.vue'
const route = useRoute()
const auth = useAuthStore()
const canManage = computed(() => auth.roles.includes('SCHOOL_OWNER') || auth.roles.includes('ADMIN'))
const activeTab = ref('carryovers')
const rows = ref<Carryover[]>([])
const compensations = ref<Compensation[]>([])
const loading = ref(false)
const error = ref('')
const total = ref(0)
const query = reactive({ page: 1, page_size: 20, carryover_status: '', task_id: undefined as ID | undefined })
const createDialog = ref(false)
const creating = ref(false)
const tasks = ref<CollegeTask[]>([])
const selectedTask = ref<CollegeTask>()
const form = reactive({ task_id: '' as ID, source_milestone_id: '' as ID, target_milestone_id: '' as ID, carryover_amount: undefined as number | undefined, reason: '' })
const sourceMilestone = computed(() => selectedTask.value?.milestones?.find((item) => item.id === form.source_milestone_id))
const availableTargets = computed(() => selectedTask.value?.milestones?.filter((item) => item.target_year > (sourceMilestone.value?.target_year || 0)) || [])
const maxAmount = computed(() => remainingGap(sourceMilestone.value?.current_target_value, sourceMilestone.value?.recognized_value, sourceMilestone.value?.covered_value) || 0)
async function load() { loading.value = true; error.value = ''; try { const [c, p] = await Promise.all([adjustmentApi.carryovers(query), adjustmentApi.compensations({ page_size: 100, task_id: query.task_id })]); rows.value = c.items; total.value = c.total; compensations.value = p.items } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
async function openCreate() {
  Object.assign(form, { task_id: route.query.task_id || '', source_milestone_id: route.query.source_milestone_id || '', target_milestone_id: '', carryover_amount: undefined, reason: '' }); createDialog.value = true
  if (!tasks.value.length) { try { tasks.value = (await executionApi.tasks({ page_size: 200, calc_method: 'CUMULATIVE', task_status: 'IN_PROGRESS' })).items } catch (cause) { ElMessage.error(errorMessage(cause)) } }
  if (form.task_id) await selectTask(form.task_id)
}
async function selectTask(id: ID) { form.source_milestone_id = ''; form.target_milestone_id = ''; selectedTask.value = await executionApi.task(id); if (route.query.source_milestone_id && String(route.query.task_id) === String(id)) { form.source_milestone_id = String(route.query.source_milestone_id); form.carryover_amount = maxAmount.value || undefined } }
async function create() {
  if (!form.task_id || !form.source_milestone_id || !form.target_milestone_id || !form.carryover_amount || !form.reason.trim()) { ElMessage.warning('请完整填写缺口年度、接收年度、顺延量和原因'); return }
  if (form.carryover_amount <= 0 || form.carryover_amount > maxAmount.value) { ElMessage.warning(`顺延量须大于 0 且不超过未弥补缺口 ${maxAmount.value}`); return }
  creating.value = true
  try { await adjustmentApi.createCarryover({ ...form, lock_version: selectedTask.value?.lock_version }); ElMessage.success('顺延单已发起，待校级项目负责人审批'); createDialog.value = false; await load() } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { creating.value = false }
}
async function approve(row: Carryover, approved: boolean) { const result = await ElMessageBox.prompt(approved ? '批准后接收年度目标增加顺延量，原年度保留顺延关联且五年总目标不变。可填写审批意见。' : '请填写退回原因，退回不改变任何年度目标。', approved ? '审批年度顺延' : '退回年度顺延', { inputValue: '', inputValidator: (value) => approved || Boolean(value?.trim()) || '退回原因必填', type: 'warning' }); try { await adjustmentApi.approveCarryover(row.id, approved, result.value, row.lock_version); ElMessage.success(approved ? '顺延已审批生效并触发补位核算' : '顺延单已退回'); await load() } catch (cause) { ElMessage.error(errorMessage(cause)) } }
async function withdraw(row: Carryover) { const result = await ElMessageBox.prompt('撤回将释放缺口占用并保留日志，请填写原因。', '撤回顺延单', { inputValidator: (value) => Boolean(value?.trim()) || '原因必填', type: 'warning' }); try { await adjustmentApi.withdrawCarryover(row.id, result.value, row.lock_version); ElMessage.success('顺延单已撤回'); await load() } catch (cause) { ElMessage.error(errorMessage(cause)) } }
onMounted(async () => { await load(); if (route.query.create === '1' && canManage.value) openCreate() })
</script>
<template>
  <div class="page-container"><PageHeader title="年度顺延与后续补位" description="仅累计型指标默认支持。原目标与原认定值不改写；后续超额优先补同一学院任务内最早未弥补缺口，同一余量不可重复消费。"><template #actions><el-button v-if="canManage" type="primary" @click="openCreate"><el-icon><Plus /></el-icon>发起年度顺延</el-button></template></PageHeader>
    <el-tabs v-model="activeTab" class="panel" style="padding:0 15px"><el-tab-pane label="年度顺延单" name="carryovers" /><el-tab-pane label="后续补位关联链" name="compensations" /></el-tabs>
    <template v-if="activeTab === 'carryovers'"><div class="filter-bar"><el-select v-model="query.carryover_status" placeholder="顺延状态" clearable style="width:160px"><el-option label="待审批" value="PENDING_APPROVAL" /><el-option label="已生效" value="EFFECTIVE" /><el-option label="已退回" value="RETURNED" /><el-option label="已撤回" value="WITHDRAWN" /></el-select><el-button type="primary" plain @click="load">查询</el-button></div><RemoteState :loading="loading" :error="error" :empty="!rows.length" empty-text="暂无年度顺延记录" @retry="load"><el-table :data="rows"><el-table-column prop="carryover_no" label="顺延单号" width="165" class-name="mono" /><el-table-column prop="indicator_name" label="指标／任务" min-width="240"><template #default="{ row }"><div>{{ row.indicator_name }}</div><div class="mono subtle">{{ row.task_code }}</div></template></el-table-column><el-table-column label="缺口年度 → 接收年度" width="180" align="center"><template #default="{ row }"><strong>{{ row.source_year }}</strong><span class="primary-text" style="margin:0 9px">→</span><strong>{{ row.target_year }}</strong></template></el-table-column><el-table-column prop="carryover_amount" label="顺延量" width="110" align="right" /><el-table-column prop="reason" label="业务原因" min-width="190" show-overflow-tooltip /><el-table-column label="状态" width="105"><template #default="{ row }"><StatusTag :status="row.carryover_status" /></template></el-table-column><el-table-column label="发起人／时间" width="165"><template #default="{ row }">{{ row.initiator_name || row.initiated_by }}<div class="subtle small">{{ formatDateTime(row.initiated_at) }}</div></template></el-table-column><el-table-column label="操作" width="160" fixed="right"><template #default="{ row }"><template v-if="canManage && row.carryover_status === 'PENDING_APPROVAL'"><el-button link type="success" @click="approve(row, true)">批准生效</el-button><el-button link type="danger" @click="approve(row, false)">退回</el-button><el-button link type="warning" @click="withdraw(row)">撤回</el-button></template></template></el-table-column></el-table><div class="table-footer"><el-pagination v-model:current-page="query.page" v-model:page-size="query.page_size" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div></RemoteState></template>
    <template v-else><RemoteState :loading="loading" :error="error" :empty="!compensations.length" empty-text="暂无补位关联" @retry="load"><el-table :data="compensations"><el-table-column label="补位关联" min-width="260"><template #default="{ row }"><div class="compensation-chain"><div><small>缺口年度</small><strong>{{ row.deficit_year || row.deficit_milestone_id }}</strong></div><el-icon><Link /></el-icon><div><small>后续超额年度</small><strong>{{ row.surplus_year || row.surplus_milestone_id }}</strong></div></div></template></el-table-column><el-table-column prop="compensation_amount" label="已弥补量" width="130" align="right" /><el-table-column label="状态" width="100"><template #default="{ row }"><StatusTag :status="row.compensation_status" /></template></el-table-column><el-table-column prop="reversed_reason" label="冲销原因" min-width="200"><template #default="{ row }">{{ row.reversed_reason || '—' }}</template></el-table-column><el-table-column label="说明" min-width="260"><template #default>只增加缺口年度“已弥补量”，不修改任一年度原目标或原认定值。</template></el-table-column></el-table></RemoteState></template>
    <el-dialog v-model="createDialog" title="发起年度顺延" width="650px" destroy-on-close><el-alert title="同一缺口存在审核中填报或待审批调整时不可重复顺延；接收年度必须晚于缺口年度且尚未结束。" type="warning" :closable="false" style="margin-bottom:16px" /><el-form label-width="105px"><el-form-item label="学院任务" required><el-select v-model="form.task_id" filterable style="width:100%" @change="selectTask"><el-option v-for="task in tasks" :key="task.id" :value="task.id" :label="`${task.indicator_name}｜${task.college_org_name}｜${task.task_code}`" /></el-select></el-form-item><div class="form-pair"><el-form-item label="缺口年度" required><el-select v-model="form.source_milestone_id" style="width:100%" @change="form.target_milestone_id = ''; form.carryover_amount = maxAmount || undefined"><el-option v-for="item in selectedTask?.milestones?.filter(m => (remainingGap(m.current_target_value,m.recognized_value,m.covered_value) || 0) > 0)" :key="item.id" :value="item.id" :label="`${item.target_year} 年（缺口 ${remainingGap(item.current_target_value,item.recognized_value,item.covered_value)}）`" /></el-select></el-form-item><el-form-item label="接收年度" required><el-select v-model="form.target_milestone_id" style="width:100%"><el-option v-for="item in availableTargets" :key="item.id" :value="item.id" :label="`${item.target_year} 年`" /></el-select></el-form-item></div><el-form-item label="顺延量" required><el-input-number v-model="form.carryover_amount" :min="0" :max="maxAmount" :controls="false" style="width:220px" /><span class="form-tip" style="margin-left:10px">不超过未弥补缺口 {{ maxAmount }}</span></el-form-item><el-form-item label="顺延原因" required><el-input v-model="form.reason" type="textarea" :rows="4" maxlength="2000" show-word-limit /></el-form-item></el-form><template #footer><el-button @click="createDialog = false">取消</el-button><el-button type="primary" :loading="creating" @click="create">提交审批</el-button></template></el-dialog>
  </div>
</template>
<style scoped>.form-pair{display:grid;grid-template-columns:1fr 1fr;gap:12px}.compensation-chain{display:flex;align-items:center;gap:22px}.compensation-chain>div{display:flex;flex-direction:column}.compensation-chain small{color:#8b97a9;font-size:10px}.compensation-chain strong{margin-top:4px;font-size:17px}.compensation-chain .el-icon{color:#4e78d2}</style>
