<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage, governanceApi, optionsApi } from '@/api'
import type { ID, Organization, RoleCode, UserAccount } from '@/types/api'
import { roleLabels } from '@/utils/dictionaries'
import { normalizeRole } from '@/utils/permissions'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import RemoteState from '@/components/RemoteState.vue'
const rows = ref<UserAccount[]>([])
const orgs = ref<Organization[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const query = reactive({ page: 1, page_size: 20, keyword: '', status: '', role: '' })
const dialog = ref(false)
const saving = ref(false)
const editingId = ref<ID>()
const form = reactive({ account_name: '', user_name: '', primary_org_id: '' as ID, status: 'ENABLED', password: '', roles: [] as RoleCode[], lock_version: undefined as number | undefined })
async function load() { loading.value = true; error.value = ''; try { const result = await governanceApi.users(query); rows.value = result.items; total.value = result.total } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
function open(row?: UserAccount) { editingId.value = row?.id; Object.assign(form, row ? { ...row, password: '', roles: (row.roles || []).map(normalizeRole).filter((role): role is RoleCode => Boolean(role)) } : { account_name: '', user_name: '', primary_org_id: '', status: 'ENABLED', password: '', roles: [], lock_version: undefined }); dialog.value = true }
async function save() {
  if (!form.account_name || !form.user_name || !form.primary_org_id) { ElMessage.warning('账号、姓名和主属组织必填'); return }
  if (!editingId.value && !form.password) { ElMessage.warning('新建账号必须设置初始密码'); return }
  saving.value = true
  try {
    if (editingId.value) await governanceApi.assignRoles(editingId.value, form.roles); else await governanceApi.createUser(form)
    ElMessage.success('用户及角色授权已保存'); dialog.value = false; await load()
  } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { saving.value = false }
}
async function toggle(row: UserAccount) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  let reason: string | undefined
  if (next === 'DISABLED') {
    const result = await ElMessageBox.prompt('停用后账号不可继续操作。请填写停用原因；如仍有职责或待办，后端会要求先完成交接。', '停用账号', { type: 'warning', inputValidator: (value) => Boolean(value?.trim()) || '停用原因必填' })
    reason = result.value
  }
  try { await governanceApi.updateUser(row.id, { status: next, reason, lock_version: row.lock_version }); ElMessage.success(next === 'ENABLED' ? '账号已启用' : '账号已停用'); await load() } catch (cause) { ElMessage.error(errorMessage(cause)) }
}
onMounted(async () => { await load(); try { orgs.value = (await optionsApi.organizations({ page_size: 200 })).items } catch (cause) { ElMessage.error(errorMessage(cause)) } })
</script>
<template>
  <div class="page-container"><PageHeader title="用户与角色" description="维护四类角色及主属组织。角色授权不替代项目／学院数据范围，管理员身份也不自动获得业务审核或终审权限。"><template #actions><el-button type="primary" @click="open()"><el-icon><Plus /></el-icon>新建用户</el-button></template></PageHeader>
    <div class="filter-bar"><el-input v-model="query.keyword" placeholder="账号／姓名" clearable style="width:220px" @keyup.enter="load" /><el-select v-model="query.role" placeholder="角色" clearable style="width:170px"><el-option v-for="(label,value) in roleLabels" :key="value" :value="value" :label="label" /></el-select><el-select v-model="query.status" placeholder="账号状态" clearable style="width:130px"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select><el-button type="primary" plain @click="load">查询</el-button></div>
    <RemoteState :loading="loading" :error="error" :empty="!rows.length" @retry="load"><el-table :data="rows"><el-table-column prop="account_name" label="登录账号" width="140" class-name="mono" /><el-table-column prop="user_name" label="用户姓名" width="120" /><el-table-column prop="primary_org_name" label="主属组织" min-width="190"><template #default="{ row }">{{ row.primary_org_name || orgs.find(item => item.id === row.primary_org_id)?.org_name || '—' }}</template></el-table-column><el-table-column label="角色" min-width="270"><template #default="{ row }"><el-tag v-for="role in row.roles" :key="role" size="small" effect="plain" style="margin-right:5px">{{ roleLabels[role] || role }}</el-tag><span v-if="!row.roles?.length" class="value-empty">未分配角色</span></template></el-table-column><el-table-column label="最后登录" width="165"><template #default="{ row }">{{ formatDateTime(row.last_login_at) }}</template></el-table-column><el-table-column label="状态" width="85"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column><el-table-column label="操作" width="135" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="open(row)">编辑授权</el-button><el-button v-if="row.status === 'ENABLED'" link type="danger" @click="toggle(row)">停用</el-button></template></el-table-column></el-table><div class="table-footer"><el-pagination v-model:current-page="query.page" v-model:page-size="query.page_size" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div></RemoteState>
    <el-dialog v-model="dialog" :title="editingId ? '编辑用户与角色' : '新建用户'" width="600px"><el-form label-width="100px"><el-form-item label="登录账号" required><el-input v-model="form.account_name" :disabled="Boolean(editingId)" autocomplete="off" /></el-form-item><el-form-item label="用户姓名" required><el-input v-model="form.user_name" :disabled="Boolean(editingId)" /></el-form-item><el-form-item v-if="!editingId" label="初始密码" required><el-input v-model="form.password" type="password" show-password autocomplete="new-password" /></el-form-item><el-form-item label="主属组织" required><el-select v-model="form.primary_org_id" filterable :disabled="Boolean(editingId)" style="width:100%"><el-option v-for="org in orgs" :key="org.id" :value="org.id" :label="org.org_name" /></el-select></el-form-item><el-form-item label="平台角色"><el-checkbox-group v-model="form.roles"><el-checkbox v-for="(label,value) in roleLabels" :key="value" :value="value">{{ label }}</el-checkbox></el-checkbox-group><div class="form-tip">多角色数据范围由后端逐项校验；请勿为演示之外账号随意授予校领导终审权。</div></el-form-item><el-form-item label="账号状态"><el-radio-group v-model="form.status" :disabled="Boolean(editingId)"><el-radio value="ENABLED">启用</el-radio><el-radio value="DISABLED">停用</el-radio></el-radio-group></el-form-item></el-form><template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存用户</el-button></template></el-dialog>
  </div>
</template>
