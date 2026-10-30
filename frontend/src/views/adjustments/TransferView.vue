<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { adjustmentApi, errorMessage, executionApi, optionsApi, transferAttachmentUrl } from '@/api'
import type { CollegeTask, ID, Organization, Transfer, TransferAttachment, UserAccount } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import RemoteState from '@/components/RemoteState.vue'
const router = useRouter()
const auth = useAuthStore()
const canManage = computed(() => auth.roles.includes('SCHOOL_OWNER') || auth.roles.includes('ADMIN'))
const rows = ref<Transfer[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const query = reactive({ page: 1, page_size: 20, transfer_status: '', keyword: '' })
const createDialog = ref(false)
const creating = ref(false)
const tasks = ref<CollegeTask[]>([])
const sameIndicatorTasks = ref<CollegeTask[]>([])
const colleges = ref<Organization[]>([])
const owners = ref<UserAccount[]>([])
const sourceTask = ref<CollegeTask>()
const attachments = ref<TransferAttachment[]>([])
const form = reactive({ source_task_id: '' as ID, target_college_org_id: '' as ID, target_task_id: undefined as ID | undefined, target_owner_user_id: undefined as ID | undefined, transfer_year: undefined as number | undefined, transfer_amount: undefined as number | undefined, reason: '' })
const targetTasks = computed(() => sameIndicatorTasks.value.filter((item) => item.college_org_id === form.target_college_org_id))
async function load() { loading.value = true; error.value = ''; try { const result = await adjustmentApi.transfers(query); rows.value = result.items; total.value = result.total } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
async function prepareOptions() {
  try { const [t, c] = await Promise.all([executionApi.tasks({ page_size: 200 }), optionsApi.organizations({ org_type: 'COLLEGE', status: 'ENABLED' })]); tasks.value = t.items; colleges.value = c.items } catch (cause) { ElMessage.error(errorMessage(cause)) }
}
async function selectSource(id: ID) {
  form.target_task_id = undefined; form.transfer_year = undefined
  sourceTask.value = await executionApi.task(id)
  const [taskResult, ownerResult] = await Promise.all([executionApi.tasks({ indicator_id: sourceTask.value.indicator_id, page_size: 200 }), optionsApi.users({ role: 'COLLEGE_OWNER', plan_id: sourceTask.value.plan_id })])
  sameIndicatorTasks.value = taskResult.items.filter((item) => item.indicator_id === sourceTask.value?.indicator_id)
  owners.value = ownerResult.items
}
async function openCreate(copy?: Transfer) {
  Object.assign(form, { source_task_id: copy?.source_task_id || '', target_college_org_id: copy?.target_college_org_id || '', target_task_id: copy?.target_task_id, target_owner_user_id: undefined, transfer_year: copy?.transfer_year, transfer_amount: copy ? Number(copy.transfer_amount) : undefined, reason: copy?.reason || '' }); attachments.value = copy?.attachment_json ? [...copy.attachment_json] : []
  createDialog.value = true; await prepareOptions(); if (form.source_task_id) await selectSource(form.source_task_id); if (copy) { form.target_college_org_id = copy.target_college_org_id; form.target_task_id = copy.target_task_id; form.transfer_year = copy.transfer_year }
}
async function upload(file: UploadFile) { if (!file.raw) return; try { const item = await adjustmentApi.uploadTransferAttachment(file.raw); attachments.value.push(item); ElMessage.success('说明材料已上传') } catch (cause) { ElMessage.error(errorMessage(cause)) } }
async function create() {
  if (!form.source_task_id || !form.target_college_org_id || !form.transfer_amount || !form.reason.trim()) { ElMessage.warning('请完整填写转出任务、转入学院、划转量和原因'); return }
  if (sourceTask.value?.college_org_id === form.target_college_org_id) { ElMessage.warning('转入学院不能与转出学院相同'); return }
  if (!form.target_task_id && !form.target_owner_user_id) { ElMessage.warning('转入新学院任务时必须指定转入学院负责人'); return }
  creating.value = true
  try { const result = await adjustmentApi.createTransfer({ ...form, indicator_id: sourceTask.value?.indicator_id, attachment_json: attachments.value, lock_version: sourceTask.value?.lock_version }); ElMessage.success('划转单已发起，等待转出学院确认'); createDialog.value = false; await load(); router.push(`/adjustments/transfers/${result.id}`) } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { creating.value = false }
}
async function withdraw(row: Transfer) { const result = await ElMessageBox.prompt('校领导审批前可撤回，撤回后释放占用量并留痕。请填写原因。', '撤回跨院划转', { inputValidator: (value) => Boolean(value?.trim()) || '原因必填', type: 'warning' }); try { await adjustmentApi.withdrawTransfer(row.id, result.value, row.lock_version); ElMessage.success('划转单已撤回'); await load() } catch (cause) { ElMessage.error(errorMessage(cause)) } }
function openAttachment(item: TransferAttachment) {
  const url = transferAttachmentUrl(item)
  if (!url) { ElMessage.info('附件预览地址尚未生成，请稍后刷新重试'); return }
  window.open(url, '_blank', 'noopener,noreferrer')
}
onMounted(load)
</script>
<template>
  <div class="page-container"><PageHeader title="跨学院指标划转" description="严格按校级发起 → 转出学院确认 → 转入学院确认 → 校领导终审执行。任一环节退回即终止，终审前发起方可撤回。"><template #actions><el-button v-if="canManage" type="primary" @click="openCreate()"><el-icon><Plus /></el-icon>发起跨院划转</el-button></template></PageHeader>
    <div class="filter-bar"><el-input v-model="query.keyword" placeholder="划转单号／指标" clearable style="width:240px" @keyup.enter="load" /><el-select v-model="query.transfer_status" placeholder="划转状态" clearable style="width:180px"><el-option label="待转出确认" value="PENDING_OUT_CONFIRM" /><el-option label="待转入确认" value="PENDING_IN_CONFIRM" /><el-option label="待校领导审批" value="PENDING_LEADER_APPROVAL" /><el-option label="已生效" value="EFFECTIVE" /><el-option label="已退回" value="RETURNED" /><el-option label="已撤回" value="WITHDRAWN" /></el-select><el-button type="primary" plain @click="load">查询</el-button></div>
    <RemoteState :loading="loading" :error="error" :empty="!rows.length" empty-text="暂无跨院划转单" @retry="load"><el-table :data="rows"><el-table-column prop="transfer_no" label="划转单号" width="165" class-name="mono" /><el-table-column prop="indicator_name" label="关键产出指标" min-width="240" show-overflow-tooltip><template #default="{ row }"><button class="link-button" style="text-align:left" @click="router.push(`/adjustments/transfers/${row.id}`)">{{ row.indicator_name }}</button></template></el-table-column><el-table-column label="转出 → 转入" min-width="220"><template #default="{ row }"><span>{{ row.source_college_org_name }}</span><span class="primary-text" style="margin:0 8px">→</span><span>{{ row.target_college_org_name }}</span></template></el-table-column><el-table-column label="影响年度" width="95" align="center"><template #default="{ row }">{{ row.transfer_year || '五年总目标' }}</template></el-table-column><el-table-column prop="transfer_amount" label="划转量" width="100" align="right" /><el-table-column label="状态" width="140"><template #default="{ row }"><StatusTag :status="row.transfer_status" /></template></el-table-column><el-table-column label="发起时间" width="160"><template #default="{ row }">{{ formatDateTime(row.initiated_at) }}</template></el-table-column><el-table-column label="操作" width="180" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="router.push(`/adjustments/transfers/${row.id}`)">审批链路</el-button><el-button v-if="canManage && ['PENDING_OUT_CONFIRM','PENDING_IN_CONFIRM','PENDING_LEADER_APPROVAL'].includes(row.transfer_status)" link type="warning" @click="withdraw(row)">撤回</el-button><el-button v-if="canManage && ['RETURNED','WITHDRAWN'].includes(row.transfer_status)" link @click="openCreate(row)">复制修订</el-button></template></el-table-column></el-table><div class="table-footer"><el-pagination v-model:current-page="query.page" v-model:page-size="query.page_size" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div></RemoteState>
    <el-dialog v-model="createDialog" title="发起／复制修订跨院划转" width="720px" destroy-on-close><el-alert title="仅允许同一关键产出指标下、单位与核算方式一致的学院任务之间划转；已认定、审核中及进行中调整占用量不可重复划转。" type="warning" :closable="false" style="margin-bottom:16px" /><el-form label-width="115px"><el-form-item label="转出学院任务" required><el-select v-model="form.source_task_id" filterable style="width:100%" @change="selectSource"><el-option v-for="task in tasks" :key="task.id" :value="task.id" :label="`${task.indicator_name}｜${task.college_org_name}｜${task.task_code}`" /></el-select></el-form-item><div class="form-pair"><el-form-item label="转入学院" required><el-select v-model="form.target_college_org_id" filterable style="width:100%" @change="form.target_task_id = undefined; form.target_owner_user_id = undefined"><el-option v-for="org in colleges.filter(item => item.id !== sourceTask?.college_org_id)" :key="org.id" :value="org.id" :label="org.org_name" /></el-select></el-form-item><el-form-item label="转入既有任务"><el-select v-model="form.target_task_id" clearable placeholder="不选则生效时生成新任务" style="width:100%"><el-option v-for="task in targetTasks" :key="task.id" :value="task.id" :label="`${task.task_code}｜${task.execution_scope || '默认范围'}`" /></el-select></el-form-item></div><el-form-item v-if="!form.target_task_id" label="转入学院负责人" required><el-select v-model="form.target_owner_user_id" filterable style="width:100%"><el-option v-for="user in owners.filter(item => item.primary_org_id === form.target_college_org_id)" :key="user.id" :value="user.id" :label="`${user.user_name}（${user.primary_org_name || user.account_name}）`" /></el-select><div class="form-tip">仅显示该项目内属于转入学院的负责人；如未配置，请先由管理员维护项目成员。</div></el-form-item><div class="form-pair"><el-form-item label="影响年度"><el-select v-model="form.transfer_year" clearable placeholder="空白表示五年总目标" style="width:100%"><el-option v-for="item in sourceTask?.milestones || []" :key="item.id" :value="item.target_year" :label="item.target_year" /></el-select></el-form-item><el-form-item label="划转量" required><el-input-number v-model="form.transfer_amount" :min="0" :controls="false" style="width:100%" /><div class="form-tip">单位：{{ sourceTask?.unit_name || sourceTask?.unit_code || '—' }}；可划转上限由服务端重查</div></el-form-item></div><el-form-item label="划转原因" required><el-input v-model="form.reason" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="说明原学院无法完成及调配依据" /></el-form-item><el-form-item label="说明材料"><el-upload :auto-upload="false" :show-file-list="false" :on-change="upload"><el-button><el-icon><Upload /></el-icon>上传说明材料</el-button></el-upload><div v-if="attachments.length" class="attachment-list"><span v-for="(item,index) in attachments" :key="item.object_key" class="attachment-entry"><el-tag closable @close="attachments.splice(index,1)">{{ item.name || item.original_file_name || item.file_name || '附件' }}</el-tag><el-button link type="primary" :disabled="!transferAttachmentUrl(item)" @click="openAttachment(item)">打开</el-button></span></div></el-form-item></el-form><template #footer><el-button @click="createDialog = false">取消</el-button><el-button type="primary" :loading="creating" @click="create">发起转出确认</el-button></template></el-dialog>
  </div>
</template>
<style scoped>.form-pair{display:grid;grid-template-columns:1fr 1fr;gap:12px}.attachment-list{margin-top:8px;display:flex;flex-wrap:wrap;gap:6px}.attachment-entry{display:inline-flex;align-items:center;gap:2px}</style>
