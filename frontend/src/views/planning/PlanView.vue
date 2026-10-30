<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { errorMessage, planningApi } from '@/api'
import type { ConstructionPlan, Directory, ID, UserAccount } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import { statusLabel } from '@/utils/dictionaries'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import RemoteState from '@/components/RemoteState.vue'

interface PlanForm { plan_code: string; directory_id: ID; plan_name: string; construction_scope: string; start_year: number; end_year: number; school_owner_id: ID; copy_mode: 'STRUCTURE_ONLY' | 'STRUCTURE_AND_TARGET' }
const router = useRouter()
const auth = useAuthStore()
const app = useAppStore()
const canEdit = computed(() => auth.roles.includes('SCHOOL_OWNER'))
const rows = ref<ConstructionPlan[]>([])
const directories = ref<Directory[]>([])
const owners = ref<UserAccount[]>([])
const loading = ref(false)
const saving = ref(false)
const deletingId = ref<ID>('')
const error = ref('')
const total = ref(0)
const query = reactive({ page: 1, page_size: 20, keyword: '', plan_status: '', directory_id: undefined as ID | undefined })
const dialog = ref(false)
const mode = ref<'CREATE' | 'COPY'>('CREATE')
const sourcePlan = ref<ConstructionPlan>()
const formRef = ref<FormInstance>()
const form = reactive<PlanForm>({ plan_code: '', directory_id: '', plan_name: '', construction_scope: '', start_year: 2026, end_year: 2030, school_owner_id: '', copy_mode: 'STRUCTURE_ONLY' })
const rules: FormRules = { plan_code: [{ required: true, message: '请输入计划代码', trigger: 'blur' }], directory_id: [{ required: true, message: '请选择建设目录', trigger: 'change' }], plan_name: [{ required: true, message: '请输入计划名称', trigger: 'blur' }], construction_scope: [{ required: true, message: '请输入建设范围', trigger: 'blur' }], school_owner_id: [{ required: true, message: '请选择校级项目负责人', trigger: 'change' }] }
async function load() { loading.value = true; error.value = ''; try { const result = await planningApi.plans(query); rows.value = result.items; total.value = result.total } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
function resetForm() { Object.assign(form, { plan_code: '', directory_id: '', plan_name: '', construction_scope: '', start_year: 2026, end_year: 2030, school_owner_id: auth.user?.id || '', copy_mode: 'STRUCTURE_ONLY' }) }
function openCreate() { mode.value = 'CREATE'; sourcePlan.value = undefined; resetForm(); dialog.value = true }
function openCopy(row: ConstructionPlan) { mode.value = 'COPY'; sourcePlan.value = row; resetForm(); Object.assign(form, { plan_code: `${row.plan_code}-COPY`, directory_id: row.directory_id, plan_name: `${row.plan_name}（副本）`, construction_scope: row.construction_scope, start_year: row.start_year, end_year: row.end_year, school_owner_id: row.school_owner_id }); dialog.value = true }
async function save() {
  if (!await formRef.value?.validate().catch(() => false)) return
  if (form.end_year < form.start_year) { ElMessage.warning('结束年度不得早于开始年度'); return }
  saving.value = true
  try {
    const result = mode.value === 'COPY' && sourcePlan.value ? await planningApi.copyPlan(sourcePlan.value.id, form) : await planningApi.createPlan(form)
    ElMessage.success(mode.value === 'COPY' ? '已生成独立计划副本，未复制执行历史' : '计划草稿已创建'); dialog.value = false; window.dispatchEvent(new Event('plans-changed')); await load(); router.push(`/planning/indicators?plan_id=${result.id}`)
  } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { saving.value = false }
}
async function changeStatus(row: ConstructionPlan, action: 'publish' | 'start' | 'close') {
  const labels = { publish: '发布', start: '开始执行', close: '结项' }
  try {
    await ElMessageBox.confirm(action === 'close' ? `结项后计划保留完整历史且不能继续业务操作，确认将“${row.plan_name}”结项？` : `确认${labels[action]}“${row.plan_name}”？发布前请确认必填量化及校验已完成。`, `${labels[action]}计划`, { type: 'warning', confirmButtonText: `确认${labels[action]}` })
    await planningApi[`${action}Plan`](row.id, row.lock_version)
    ElMessage.success(`计划已${labels[action]}`)
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
onMounted(async () => { await load(); if (canEdit.value) { if (auth.user) owners.value = [{ ...auth.user, primary_org_id: auth.user.primary_org_id || '', status: 'ENABLED' }]; try { directories.value = (await planningApi.directories({ page_size: 100 })).items } catch { /* 保存时由后端再次校验 */ } } })
</script>
<template>
  <div class="page-container">
    <PageHeader title="建设计划" description="新建空白计划或复用模板。复制仅包含结构及可选目标值，不带入完成值、材料、审核、待办、调整和日志。"><template #actions><el-button v-if="canEdit" type="primary" @click="openCreate"><el-icon><Plus /></el-icon>新建计划</el-button></template></PageHeader>
    <div class="filter-bar"><el-input v-model="query.keyword" placeholder="计划名称／代码" clearable style="width:240px" @keyup.enter="load"><template #prefix><el-icon><Search /></el-icon></template></el-input><el-select v-model="query.plan_status" placeholder="计划状态" clearable style="width:130px"><el-option label="草稿" value="DRAFT" /><el-option label="已发布" value="PUBLISHED" /><el-option label="执行中" value="IN_PROGRESS" /><el-option label="已结项" value="CLOSED" /></el-select><el-button type="primary" plain @click="load">查询</el-button></div>
    <RemoteState :loading="loading" :error="error" :empty="!rows.length" empty-text="暂无建设计划" @retry="load">
      <el-table :data="rows" row-key="id">
        <el-table-column label="建设计划" min-width="270"><template #default="{ row }"><button class="link-button" style="font-weight:550;text-align:left" @click="router.push(`/planning/indicators?plan_id=${row.id}`)">{{ row.plan_name }}</button><div class="mono subtle" style="margin-top:4px">{{ row.plan_code }}</div></template></el-table-column>
        <el-table-column prop="directory_name" label="所属目录" min-width="160" show-overflow-tooltip />
        <el-table-column label="建设周期" width="125" align="center"><template #default="{ row }">{{ row.start_year }}—{{ row.end_year }}</template></el-table-column>
        <el-table-column prop="school_owner_name" label="校级负责人" width="120" />
        <el-table-column label="结构规模" width="145" align="center"><template #default="{ row }"><span class="small">{{ row.direction_count ?? '—' }} 方向 / {{ row.indicator_count ?? '—' }} 指标 / {{ row.task_count ?? '—' }} 任务</span></template></el-table-column>
        <el-table-column label="状态／版本" width="130"><template #default="{ row }"><StatusTag :status="row.plan_status" /><span class="version-tag" style="margin-left:5px">V{{ row.current_version_no }}</span></template></el-table-column>
        <el-table-column label="操作" width="275" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="router.push(`/planning/indicators?plan_id=${row.id}`)">进入</el-button><el-button v-if="canEdit" link type="primary" @click="openCopy(row)">复制</el-button><el-button v-if="canEdit && row.plan_status === 'DRAFT'" link type="success" @click="changeStatus(row, 'publish')">发布</el-button><el-button v-if="canEdit && row.plan_status === 'PUBLISHED'" link type="success" @click="changeStatus(row, 'start')">执行</el-button><el-button v-if="canEdit && row.plan_status === 'IN_PROGRESS'" link type="warning" @click="changeStatus(row, 'close')">结项</el-button><el-tooltip v-if="canEdit" content="删除计划及其学院任务、填报和调整数据" placement="top"><span><el-button link type="danger" :loading="deletingId === row.id" :disabled="Boolean(deletingId)" @click="removePlan(row)">删除</el-button></span></el-tooltip></template></el-table-column>
      </el-table>
      <div class="table-footer"><el-pagination v-model:current-page="query.page" v-model:page-size="query.page_size" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div>
    </RemoteState>
    <el-dialog v-model="dialog" :title="mode === 'COPY' ? '从已有计划复制' : '新建建设计划'" width="660px" destroy-on-close>
      <el-alert v-if="mode === 'COPY'" :title="`来源：${sourcePlan?.plan_name}`" description="源指标若存在未完成校验，会保留“待完善”标记；副本不能直接下发。" type="info" :closable="false" style="margin-bottom:16px" />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item v-if="mode === 'COPY'" label="复制方式"><el-radio-group v-model="form.copy_mode"><el-radio value="STRUCTURE_ONLY">仅复制结构</el-radio><el-radio value="STRUCTURE_AND_TARGET">复制结构及目标值</el-radio></el-radio-group></el-form-item>
        <el-form-item label="所属目录" prop="directory_id"><el-select v-model="form.directory_id" filterable style="width:100%"><el-option v-for="item in directories" :key="item.id" :value="item.id" :label="item.directory_name" /></el-select></el-form-item>
        <el-form-item label="计划名称" prop="plan_name"><el-input v-model="form.plan_name" maxlength="200" /></el-form-item><el-form-item label="计划代码" prop="plan_code"><el-input v-model="form.plan_code" placeholder="全局唯一" /></el-form-item>
        <el-form-item label="建设范围" prop="construction_scope"><el-input v-model="form.construction_scope" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="建设周期"><div style="display:flex;align-items:center;gap:10px"><el-input-number v-model="form.start_year" :min="2000" :max="2100" :controls="false" /><span>至</span><el-input-number v-model="form.end_year" :min="2000" :max="2100" :controls="false" /><span class="form-tip">发布后周期不可直接修改</span></div></el-form-item>
        <el-form-item label="校级负责人" prop="school_owner_id"><el-select v-model="form.school_owner_id" filterable style="width:100%"><el-option v-for="user in owners" :key="user.id" :value="user.id" :label="`${user.user_name}（${user.account_name}）`" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">{{ mode === 'COPY' ? '生成独立副本' : '创建草稿' }}</el-button></template>
    </el-dialog>
  </div>
</template>
