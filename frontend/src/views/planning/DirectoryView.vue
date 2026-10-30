<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { errorMessage, optionsApi, planningApi } from '@/api'
import type { Directory, ID, UserAccount } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import RemoteState from '@/components/RemoteState.vue'

const auth = useAuthStore()
const canEdit = computed(() => auth.roles.includes('ADMIN'))
const rows = ref<Directory[]>([])
const leaders = ref<UserAccount[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const query = reactive({ page: 1, page_size: 20, keyword: '', status: '' })
const dialog = ref(false)
const saving = ref(false)
const deletingId = ref<ID>('')
const formRef = ref<FormInstance>()
const editingId = ref<ID>()
const form = reactive({ directory_code: '', directory_name: '', description: '', leader_user_id: '' as ID, status: 'DRAFT', sort_no: 1, lock_version: undefined as number | undefined })
const rules: FormRules = { directory_code: [{ required: true, message: '请输入目录代码', trigger: 'blur' }], directory_name: [{ required: true, message: '请输入目录名称', trigger: 'blur' }], leader_user_id: [{ required: true, message: '请选择校领导总负责人', trigger: 'change' }] }
async function load() {
  loading.value = true; error.value = ''
  try { const result = await planningApi.directories(query); rows.value = result.items; total.value = result.total } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
function openCreate() { editingId.value = undefined; Object.assign(form, { directory_code: '', directory_name: '', description: '', leader_user_id: '', status: 'DRAFT', sort_no: rows.value.length + 1, lock_version: undefined }); dialog.value = true }
function openEdit(row: Directory) { editingId.value = row.id; Object.assign(form, { ...row, leader_user_id: row.leader_user_id }); dialog.value = true }
async function save() {
  if (!await formRef.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    if (editingId.value) await planningApi.updateDirectory(editingId.value, form)
    else await planningApi.createDirectory(form)
    ElMessage.success(editingId.value ? '目录已更新' : '目录已创建'); dialog.value = false; await load()
  } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { saving.value = false }
}
async function toggleStatus(row: Directory) {
  const next = row.status === 'ENABLED' ? 'ARCHIVED' : 'ENABLED'
  await ElMessageBox.confirm(`确认将“${row.directory_name}”调整为${next === 'ENABLED' ? '启用' : '归档'}状态？`, '目录状态变更', { type: 'warning' })
  try { await planningApi.updateDirectory(row.id, { status: next, lock_version: row.lock_version }); ElMessage.success('状态已更新'); await load() } catch (cause) { ElMessage.error(errorMessage(cause)) }
}
async function removeDirectory(row: Directory) {
  try {
    await ElMessageBox.confirm(`确认永久删除建设目录“${row.directory_name}”？仅无建设计划的空目录可删除，操作日志保留。`, '删除建设目录', { type: 'warning', confirmButtonText: '确认删除', confirmButtonClass: 'el-button--danger' })
    deletingId.value = row.id
    await planningApi.deleteDirectory(row.id, row.lock_version, '平台管理员在建设目录列表确认删除空目录')
    ElMessage.success('建设目录已删除，操作日志已保留')
    await load()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(errorMessage(cause))
  } finally {
    deletingId.value = ''
  }
}
onMounted(async () => { await load(); try { leaders.value = (await optionsApi.users({ role: 'SCHOOL_LEADER', status: 'ENABLED' })).items } catch { leaders.value = [] } })
</script>
<template>
  <div class="page-container">
    <PageHeader title="建设目录" description="目录承载一套或多套建设计划，可指定 1 名校领导为总负责人；目录本身不参与指标数值汇总。空目录可删除，目录下存在建设计划时需先删除计划。"><template #actions><el-button v-if="canEdit" type="primary" @click="openCreate"><el-icon><Plus /></el-icon>新建目录</el-button></template></PageHeader>
    <div class="filter-bar"><el-input v-model="query.keyword" placeholder="目录名称／代码" clearable style="width:240px" @keyup.enter="load"><template #prefix><el-icon><Search /></el-icon></template></el-input><el-select v-model="query.status" placeholder="全部状态" clearable style="width:130px"><el-option label="草稿" value="DRAFT" /><el-option label="启用" value="ENABLED" /><el-option label="已归档" value="ARCHIVED" /></el-select><el-button type="primary" plain @click="load">查询</el-button></div>
    <RemoteState :loading="loading" :error="error" :empty="!rows.length" empty-text="暂无建设目录" @retry="load">
      <el-table :data="rows" row-key="id">
        <el-table-column prop="directory_code" label="目录代码" width="145" class-name="mono" />
        <el-table-column prop="directory_name" label="目录名称" min-width="230" show-overflow-tooltip><template #default="{ row }"><span style="font-weight:550">{{ row.directory_name }}</span></template></el-table-column>
        <el-table-column prop="leader_user_name" label="校领导总负责人" width="150"><template #default="{ row }">{{ row.leader_user_name || '待指定' }}</template></el-table-column>
        <el-table-column prop="plan_count" label="计划数" width="90" align="center"><template #default="{ row }">{{ row.plan_count ?? '—' }}</template></el-table-column>
        <el-table-column prop="status" label="状态" width="100"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        <el-table-column prop="updated_at" label="最近更新" width="165"><template #default="{ row }">{{ formatDateTime(row.updated_at || row.created_at) }}</template></el-table-column>
        <el-table-column v-if="canEdit" label="操作" width="200" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openEdit(row)">编辑</el-button><el-button link :type="row.status === 'ENABLED' ? 'warning' : 'success'" @click="toggleStatus(row)">{{ row.status === 'ENABLED' ? '归档' : '启用' }}</el-button><el-tooltip :content="Number(row.plan_count || 0) > 0 ? '目录下存在建设计划，需先删除计划' : '删除目录'" placement="top"><span><el-button link type="danger" :loading="deletingId === row.id" :disabled="Boolean(deletingId) || Number(row.plan_count || 0) > 0" @click="removeDirectory(row)">删除</el-button></span></el-tooltip></template></el-table-column>
      </el-table>
      <div class="table-footer"><el-pagination v-model:current-page="query.page" v-model:page-size="query.page_size" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div>
    </RemoteState>
    <el-dialog v-model="dialog" :title="editingId ? '编辑建设目录' : '新建建设目录'" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="105px"><el-form-item label="目录代码" prop="directory_code"><el-input v-model="form.directory_code" :disabled="Boolean(editingId)" placeholder="稳定且唯一，如 ZNZZ-2026" /></el-form-item><el-form-item label="目录名称" prop="directory_name"><el-input v-model="form.directory_name" maxlength="200" /></el-form-item><el-form-item label="校领导负责人" prop="leader_user_id"><el-select v-model="form.leader_user_id" filterable placeholder="仅显示校领导账号" style="width:100%"><el-option v-for="user in leaders" :key="user.id" :value="user.id" :label="`${user.user_name}（${user.account_name}）`" /></el-select></el-form-item><el-form-item label="目录说明"><el-input v-model="form.description" type="textarea" :rows="3" maxlength="1000" /></el-form-item><el-form-item label="排序号"><el-input-number v-model="form.sort_no" :min="1" /></el-form-item></el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
