<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Delete, EditPen, Plus, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { errorMessage, planningApi } from '@/api'
import type { ConstructionPlan, ID, UserAccount } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import { statusLabel } from '@/utils/dictionaries'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import RemoteState from '@/components/RemoteState.vue'

interface PlanForm { plan_name: string; construction_scope: string; start_year: number; end_year: number; school_owner_id: ID }
const router = useRouter()
const auth = useAuthStore()
const app = useAppStore()
const canEdit = computed(() => auth.roles.includes('SCHOOL_OWNER') || auth.roles.includes('ADMIN'))
const rows = ref<ConstructionPlan[]>([])
const owners = ref<UserAccount[]>([])
const loading = ref(false)
const saving = ref(false)
const deletingId = ref<ID>('')
const error = ref('')
const total = ref(0)
const query = reactive({ page: 1, page_size: 20, keyword: '', plan_status: '' })
const dialog = ref(false)
const editMode = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<PlanForm>({ plan_name: '', construction_scope: '', start_year: 2026, end_year: 2030, school_owner_id: '' })
const rules: FormRules = { plan_name: [{ required: true, message: '请输入计划名称', trigger: 'blur' }], construction_scope: [{ required: true, message: '请输入建设范围', trigger: 'blur' }], school_owner_id: [{ required: true, message: '请选择校级项目负责人', trigger: 'change' }] }
const editDialog = ref(false)
const editingPlan = ref<ConstructionPlan>()
const editFormRef = ref<FormInstance>()
const editForm = reactive({ plan_name: '', construction_scope: '', start_year: 2026, end_year: 2030 })
const editRules: FormRules = { plan_name: [{ required: true, message: '请输入计划名称', trigger: 'blur' }], construction_scope: [{ required: true, message: '请输入建设范围', trigger: 'blur' }] }
async function load() { loading.value = true; error.value = ''; try { const result = await planningApi.plans(query); rows.value = result.items; total.value = result.total } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
function resetForm() { Object.assign(form, { plan_name: '', construction_scope: '', start_year: 2026, end_year: 2030, school_owner_id: auth.user?.id || '' }) }
function openCreate() { resetForm(); dialog.value = true }
function openPlan(row: ConstructionPlan) { if (editMode.value) { openEdit(row); return } router.push(`/planning/indicators?plan_id=${row.id}`) }
async function save() {
  if (!await formRef.value?.validate().catch(() => false)) return
  if (form.end_year < form.start_year) { ElMessage.warning('结束年度不得早于开始年度'); return }
  saving.value = true
  try {
    const result = await planningApi.createPlan(form)
    ElMessage.success('建设计划已创建并发布'); dialog.value = false; window.dispatchEvent(new Event('plans-changed')); await load(); router.push(`/planning/indicators?plan_id=${result.id}`)
  } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { saving.value = false }
}
function openEdit(row: ConstructionPlan) {
  editingPlan.value = row
  Object.assign(editForm, { plan_name: row.plan_name, construction_scope: row.construction_scope, start_year: row.start_year, end_year: row.end_year })
  editDialog.value = true
}
async function saveEdit() {
  if (!editingPlan.value || !await editFormRef.value?.validate().catch(() => false)) return
  if (editForm.end_year < editForm.start_year) { ElMessage.warning('结束年度不得早于开始年度'); return }
  saving.value = true
  try {
    await planningApi.updatePlan(editingPlan.value.id, { ...editForm, lock_version: editingPlan.value.lock_version })
    ElMessage.success('建设计划已保存')
    editDialog.value = false
    window.dispatchEvent(new Event('plans-changed'))
    await load()
  } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { saving.value = false }
}
async function removeFromEdit() {
  if (editingPlan.value) await removePlan(editingPlan.value)
}
async function changeEditingStatus(action: 'publish' | 'close') {
  if (editingPlan.value) await changeStatus(editingPlan.value, action)
}
async function changeStatus(row: ConstructionPlan, action: 'publish' | 'close') {
  const labels = { publish: '发布', close: '结项' }
  try {
    await ElMessageBox.confirm(action === 'close' ? `结项后计划保留完整历史且不能继续业务操作，确认将“${row.plan_name}”结项？` : `确认${labels[action]}“${row.plan_name}”？发布前请确认必填量化及校验已完成。`, `${labels[action]}计划`, { type: 'warning', confirmButtonText: `确认${labels[action]}` })
    await planningApi[`${action}Plan`](row.id, row.lock_version)
    ElMessage.success(`计划已${labels[action]}`)
    editDialog.value = false
    window.dispatchEvent(new Event('plans-changed'))
    await load()
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    ElMessage.error(errorMessage(cause))
  }
}
async function removePlan(row: ConstructionPlan) {
  try {
    await ElMessageBox.confirm(`确认永久删除“${row.plan_name}”？将同时删除 ${row.direction_count ?? 0} 个方向、${row.indicator_count ?? 0} 项指标、${row.task_count ?? 0} 条学院任务，以及任务下的年度目标、填报、佐证材料、审核、外部认定、调整记录和待办。操作日志保留，删除后不可恢复。`, `删除计划（${statusLabel(row.plan_status)}）`, { type: 'warning', confirmButtonText: '确认删除', confirmButtonClass: 'el-button--danger' })
    deletingId.value = row.id
    await planningApi.deletePlan(row.id, row.lock_version, `用户在建设计划列表确认删除${statusLabel(row.plan_status)}计划`)
    if (String(app.selectedPlanId) === String(row.id)) app.clearPlan()
    editDialog.value = false
    window.dispatchEvent(new Event('plans-changed'))
    ElMessage.success('计划已删除，操作日志已保留')
    if (rows.value.length === 1 && query.page > 1) query.page -= 1
    await load()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(errorMessage(cause))
  } finally {
    deletingId.value = ''
  }
}
onMounted(async () => { await load(); if (canEdit.value && auth.user) owners.value = [{ ...auth.user, primary_org_id: auth.user.primary_org_id || '', status: 'ENABLED' }] })
</script>
<template>
  <div class="page-container">
    <PageHeader title="建设计划" description="新建空白计划并维护计划基本信息；点击计划名称进入指标总表，点击“编辑”进入编辑态可修改或删除计划。"><template #actions><el-button v-if="canEdit" type="primary" @click="openCreate"><el-icon><Plus /></el-icon>新建计划</el-button></template></PageHeader>
    <div class="filter-bar"><el-input v-model="query.keyword" placeholder="计划名称／代码" clearable style="width:240px" @keyup.enter="load"><template #prefix><el-icon><Search /></el-icon></template></el-input><el-select v-model="query.plan_status" placeholder="计划状态" clearable style="width:130px"><el-option label="草稿" value="DRAFT" /><el-option label="已发布" value="PUBLISHED" /><el-option label="执行中" value="IN_PROGRESS" /><el-option label="已结项" value="CLOSED" /></el-select><el-button type="primary" plain @click="load">查询</el-button><div v-if="canEdit" class="filter-actions"><el-button :type="editMode ? 'warning' : 'primary'" :plain="!editMode" @click="editMode = !editMode"><el-icon><EditPen /></el-icon>{{ editMode ? '退出编辑' : '编辑' }}</el-button></div></div>
    <RemoteState :loading="loading" :error="error" :empty="!rows.length" empty-text="暂无建设计划" @retry="load">
      <el-table :data="rows" row-key="id">
        <el-table-column label="建设计划" min-width="300"><template #default="{ row }"><button class="link-button" style="font-weight:550;text-align:left" @click="openPlan(row)">{{ row.plan_name }}</button><div class="mono subtle" style="margin-top:4px">{{ row.plan_code }}</div></template></el-table-column>
        <el-table-column prop="school_owner_name" label="校级负责人" width="140" />
        <el-table-column label="当年总进度" min-width="300">
          <template #default="{ row }">
            <div class="plan-progress">
              <div class="plan-progress-head"><span>{{ row.current_year }} 年 · <template v-if="row.current_year_total">{{ `已完成 ${row.current_year_completed ?? 0} / 未完成 ${row.current_year_uncompleted ?? 0}` }}</template><template v-else>尚未下发学院任务</template></span><strong>{{ Number(row.current_year_percent ?? 0).toFixed(1) }}%</strong></div>
              <div class="plan-progress-track"><div class="plan-progress-fill" :class="{ done: Number(row.current_year_percent ?? 0) >= 100 }" :style="{ width: `${Math.min(Math.max(Number(row.current_year_percent ?? 0), 0), 100)}%` }" /></div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="已下发指标 / 未下发指标" width="196">
          <template #default="{ row }">
            <div class="plan-indicator-count"><span class="assigned">已下发 <strong>{{ row.assigned_indicator_count ?? 0 }}</strong></span><span class="sep">/</span><span class="unassigned">未下发 <strong>{{ row.unassigned_indicator_count ?? 0 }}</strong></span></div>
          </template>
        </el-table-column>
        <el-table-column v-if="editMode" label="删除" width="72" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip content="删除建设计划" placement="top">
              <el-button link type="danger" :loading="deletingId === row.id" :disabled="Boolean(deletingId)" @click.stop="removePlan(row)"><el-icon :size="16"><Delete /></el-icon></el-button>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-footer"><el-pagination v-model:current-page="query.page" v-model:page-size="query.page_size" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div>
    </RemoteState>
    <el-dialog v-model="dialog" title="新建建设计划" width="660px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="计划名称" prop="plan_name"><el-input v-model="form.plan_name" maxlength="200" /></el-form-item>
        <el-form-item label="建设范围" prop="construction_scope"><el-input v-model="form.construction_scope" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="建设周期"><div style="display:flex;align-items:center;gap:10px"><el-input-number v-model="form.start_year" :min="2000" :max="2100" :controls="false" /><span>至</span><el-input-number v-model="form.end_year" :min="2000" :max="2100" :controls="false" /><span class="form-tip">发布后周期不可直接修改</span></div></el-form-item>
        <el-form-item label="校级负责人" prop="school_owner_id"><el-select v-model="form.school_owner_id" filterable style="width:100%"><el-option v-for="user in owners" :key="user.id" :value="user.id" :label="`${user.user_name}（${user.account_name}）`" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">创建草稿</el-button></template>
    </el-dialog>
    <el-dialog v-model="editDialog" title="编辑建设计划" width="640px" destroy-on-close>
      <div v-if="editingPlan" class="edit-plan-head"><StatusTag :status="editingPlan.plan_status" /><span class="mono subtle">{{ editingPlan.plan_code }}</span><span class="subtle">V{{ editingPlan.current_version_no }}</span></div>
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="105px">
        <el-form-item label="计划名称" prop="plan_name"><el-input v-model="editForm.plan_name" maxlength="200" /></el-form-item>
        <el-form-item label="建设范围" prop="construction_scope"><el-input v-model="editForm.construction_scope" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="建设周期"><div style="display:flex;align-items:center;gap:10px"><el-input-number v-model="editForm.start_year" :min="2000" :max="2100" :controls="false" /><span>至</span><el-input-number v-model="editForm.end_year" :min="2000" :max="2100" :controls="false" /><span class="form-tip">已发布计划调整周期会同步影响年度目标展示</span></div></el-form-item>
      </el-form>
      <template #footer>
        <div class="edit-dialog-footer">
          <div class="edit-dialog-danger">
            <el-button type="danger" plain :loading="deletingId === editingPlan?.id" :disabled="Boolean(deletingId)" @click="removeFromEdit">删除计划</el-button>
            <el-button v-if="editingPlan?.plan_status === 'DRAFT'" type="success" plain :disabled="Boolean(deletingId)" @click="changeEditingStatus('publish')">发布</el-button>
            <el-button v-if="['PUBLISHED', 'IN_PROGRESS'].includes(editingPlan?.plan_status || '')" type="warning" plain :disabled="Boolean(deletingId)" @click="changeEditingStatus('close')">结项</el-button>
          </div>
          <div><el-button @click="editDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button></div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.filter-actions{margin-left:auto;display:flex;align-items:center;gap:8px}
.plan-indicator-count{display:flex;align-items:center;gap:6px;color:#65738b;font-size:12px;font-variant-numeric:tabular-nums}
.plan-indicator-count strong{font-size:13px}
.plan-indicator-count .assigned strong{color:#2b9b73}
.plan-indicator-count .unassigned strong{color:#d9822b}
.plan-indicator-count .sep{color:#c3cad6}
.plan-progress{min-width:0}
.plan-progress-head{display:flex;align-items:center;justify-content:space-between;gap:10px;color:#65738b;font-size:11px}
.plan-progress-head strong{color:#22314d;font-size:12px;font-variant-numeric:tabular-nums}
.plan-progress-track{height:8px;margin-top:6px;overflow:hidden;background:#e9eef6;border-radius:4px}
.plan-progress-fill{height:100%;background:#4c7be0;border-radius:4px;transition:width .25s ease}
.plan-progress-fill.done{background:#2b9b73}
.edit-plan-head{display:flex;align-items:center;gap:10px;margin-bottom:16px;color:#7c8799;font-size:12px}
.edit-dialog-footer{display:flex;align-items:center;justify-content:space-between;gap:12px}
.edit-dialog-danger{display:flex;align-items:center;gap:8px}
</style>
