<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import BackButton from '@/components/BackButton.vue'
import UserImportDialog from './components/UserImportDialog.vue'
import { createUser, downloadUserImportTemplate, listUsers } from '@/api/system'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { useUserStore } from '@/stores/user'
import { enumLabel } from '@/utils/enumLabels'
import {
  creatableRoles,
  creationNeedsOwnUnits,
  creationUnitIds,
  normalizeCreationRoles,
} from '@/utils/role'
import type { Role } from '@/types/enums'
import type { SystemUser, UserImportConfirmResult } from '@/types/system'
import { triggerFileDownload } from '@/utils/file'

const userStore = useUserStore()
const route = useRoute()
const formRef = ref<FormInstance>()
const saving = ref(false)
const loadingUnits = ref(false)
const loadingOwners = ref(false)
const ownersLoaded = ref(false)
const allUnits = ref<OrgUnit[]>([])
const ownerOptions = ref<SystemUser[]>([])
const successMessage = ref('')
const previousRoles = ref<Role[]>([])
const importVisible = ref(false)
const templateDownloading = ref(false)

const form = reactive({
  name: '',
  username: '',
  password: '',
  phone: '',
  roles: [] as Role[],
  unitIds: [] as string[],
  ownerUserId: '',
})

const rules: FormRules = {
  name: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { max: 64, message: '姓名不超过 64 字', trigger: 'blur' },
  ],
  username: [
    { required: true, message: '请输入工号', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_.-]{1,64}$/,
      message: '工号只允许字母、数字、下划线、点、短横线',
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度须为 8–64 位', trigger: 'blur' },
  ],
  roles: [{ required: true, type: 'array', min: 1, message: '请选择角色', trigger: 'change' }],
}

const staffOnly = computed(() => route.query.role === 'STAFF' && userStore.roles.includes('UNIT_OWNER'))
const roleOptions = computed(() => staffOnly.value ? ['STAFF'] as Role[] : creatableRoles(userStore.roles))
const isStaffCreation = computed(() => form.roles.includes('STAFF'))
const adminStaffCreation = computed(() => isStaffCreation.value && userStore.roles.includes('ADMIN'))
const unitNameById = computed(() => {
  const names = new Map<string, string>()
  for (const unit of userStore.userInfo?.units ?? []) names.set(unit.id, unit.name)
  for (const unit of allUnits.value) names.set(unit.id, unit.name)
  return names
})
const ownUnitIds = computed(() => {
  const ids = userStore.userInfo?.unitIds ?? userStore.dataScope?.unitIds
  if (ids && ids.length > 0) return ids
  const primaryId = userStore.userInfo?.unitId
    ?? userStore.userInfo?.unit?.id
    ?? userStore.dataScope?.unitId
  return primaryId ? [primaryId] : []
})
const ownUnits = computed(() => {
  const byId = new Map<string, OrgUnit>()
  for (const unit of userStore.userInfo?.units ?? []) byId.set(unit.id, unit)
  for (const unit of allUnits.value) byId.set(unit.id, unit)
  return ownUnitIds.value.map((id) => byId.get(id)).filter((unit): unit is OrgUnit => Boolean(unit))
})
const restrictToOwnUnits = computed(() => creationNeedsOwnUnits(userStore.roles, form.roles))
const unitOptions = computed(() => restrictToOwnUnits.value ? ownUnits.value : allUnits.value)
const backFallback = computed(() => route.query.from === 'manage'
  ? '/users'
  : userStore.can('system:manage') ? '/system' : userStore.homePath)

function ownerOptionLabel(owner: SystemUser): string {
  const unitNames = (owner.unitIds ?? [])
    .map((unitId) => unitNameById.value.get(unitId))
    .filter((name): name is string => Boolean(name))
    .join('、')
  return `${owner.name}（${owner.username}） · ${unitNames || '未配置学院'}`
}

async function loadOwnerOptions(): Promise<void> {
  if (loadingOwners.value || ownersLoaded.value) return
  loadingOwners.value = true
  try {
    const items: SystemUser[] = []
    let page = 1
    let total = 0
    do {
      const result = await listUsers({
        role: 'UNIT_OWNER',
        isEnabled: true,
        page,
        pageSize: 100,
      })
      items.push(...result.items)
      total = result.total
      page += 1
      if (result.items.length === 0) break
    } while (items.length < total)
    ownerOptions.value = items.filter((owner) => (owner.unitIds?.length ?? 0) > 0)
    ownersLoaded.value = true
  } catch {
    ownerOptions.value = []
  } finally {
    loadingOwners.value = false
  }
}

watch(adminStaffCreation, (required) => {
  if (required) {
    void loadOwnerOptions()
  } else {
    form.ownerUserId = ''
  }
}, { immediate: true })

function handleRolesChange(selected: Role[]): void {
  if (staffOnly.value) {
    form.roles = ['STAFF']
    previousRoles.value = ['STAFF']
    form.unitIds = []
    return
  }
  form.roles = normalizeCreationRoles(selected, previousRoles.value)
  previousRoles.value = [...form.roles]
  if (isStaffCreation.value) form.unitIds = []
}

function clearForm(): void {
  form.name = ''
  form.username = ''
  form.password = ''
  form.phone = ''
  form.roles = staffOnly.value ? ['STAFF'] : []
  form.unitIds = []
  form.ownerUserId = ''
  previousRoles.value = staffOnly.value ? ['STAFF'] : []
  formRef.value?.clearValidate()
}

function resetForm(): void {
  clearForm()
  successMessage.value = ''
}

async function handleDownloadTemplate(): Promise<void> {
  if (templateDownloading.value) return
  templateDownloading.value = true
  try {
    const blob = await downloadUserImportTemplate()
    const url = URL.createObjectURL(blob)
    triggerFileDownload(url, '用户导入模板.xlsx')
    window.setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch {
    // 拦截器已提示
  } finally {
    templateDownloading.value = false
  }
}

function handleImported(result: UserImportConfirmResult): void {
  successMessage.value = staffOnly.value
    ? `批量导入完成，共创建 ${result.createdCount} 个干事账号，新干事尚未分配任务`
    : `批量导入完成，共创建 ${result.createdCount} 个用户`
}

async function submit(): Promise<void> {
  if (saving.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const unitIds = creationUnitIds(userStore.roles, form.roles, form.unitIds, ownUnitIds.value)
  if (userStore.roles.includes('UNIT_OWNER') && form.roles.includes('UNIT_OWNER') && unitIds.length === 0) {
    ElMessage.warning('创建学院项目负责人时至少指定一个本人所属单元')
    return
  }
  saving.value = true
  try {
    const staff = isStaffCreation.value
    await createUser({
      name: form.name.trim(),
      username: form.username.trim(),
      password: form.password,
      phone: form.phone.trim() || undefined,
      unitId: unitIds[0] ?? null,
      unitIds,
      ownerUserId: adminStaffCreation.value ? form.ownerUserId : undefined,
      roles: [...form.roles],
    })
    successMessage.value = staff ? '账号已创建，尚未分配任务' : '账号已创建'
    ElMessage.success(successMessage.value)
    clearForm()
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (staffOnly.value) {
    form.roles = ['STAFF']
    previousRoles.value = ['STAFF']
  }
  loadingUnits.value = true
  try {
    allUnits.value = await listOrgUnits()
  } catch {
    // 拦截器已提示；本人单元仍可使用 /auth/me 返回的引用
  } finally {
    loadingUnits.value = false
  }
})
</script>

<template>
  <div class="user-create-page">
    <BackButton :fallback="backFallback" />
    <div class="page-header">
      <div class="page-heading">
        <h1 class="page-title">用户添加</h1>
        <p class="page-subtitle">按当前账号的可创建角色开通用户，干事须明确学院归属。</p>
      </div>
      <div class="header-actions">
        <el-button :loading="templateDownloading" @click="handleDownloadTemplate">
          下载 Excel 模板
        </el-button>
        <el-button type="primary" @click="importVisible = true">批量导入</el-button>
      </div>
    </div>

    <el-card shadow="never" class="form-card">
      <el-alert
        v-if="successMessage"
        type="success"
        :closable="false"
        :title="successMessage"
        class="form-alert"
        show-icon
      />
      <el-alert
        v-if="isStaffCreation"
        type="info"
        :closable="false"
        :title="adminStaffCreation
          ? '干事继承所选归属负责人的全部学院并由其初审，创建后可在任务详情中分配任务。'
          : '干事自动归属当前负责人及本人学院，创建后可在任务详情中分配任务。'"
        class="form-alert"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" class="create-form">
        <el-form-item label="姓名" prop="name" required>
          <el-input v-model="form.name" maxlength="64" autocomplete="off" />
        </el-form-item>
        <el-form-item label="工号" prop="username" required>
          <el-input v-model="form.username" maxlength="64" autocomplete="off" />
        </el-form-item>
        <el-form-item label="初始密码" prop="password" required>
          <el-input
            v-model="form.password"
            type="password"
            minlength="8"
            maxlength="64"
            show-password
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" maxlength="32" autocomplete="off" />
        </el-form-item>
        <el-form-item label="角色" prop="roles" required>
          <el-select
            v-model="form.roles"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="请选择角色"
            class="full-width"
            :disabled="staffOnly"
            @change="handleRolesChange"
          >
            <el-option
              v-for="role in roleOptions"
              :key="role"
              :label="enumLabel('Role', role, userStore.enums)"
              :value="role"
            />
          </el-select>
          <div class="field-tip">干事不能与其他角色同时创建，且不能由纯干事账号创建。</div>
        </el-form-item>
        <el-form-item
          v-if="adminStaffCreation"
          label="归属负责人"
          prop="ownerUserId"
          required
          :rules="[{ required: true, message: '请选择归属负责人', trigger: 'change' }]"
        >
          <el-select
            v-model="form.ownerUserId"
            filterable
            :loading="loadingOwners"
            placeholder="请选择启用的学院项目负责人"
            class="full-width"
          >
            <el-option
              v-for="owner in ownerOptions"
              :key="owner.id"
              :label="ownerOptionLabel(owner)"
              :value="owner.id"
            />
          </el-select>
          <div class="field-tip">干事继承该负责人的全部学院，提交后由该负责人进行学院初审。</div>
        </el-form-item>
        <el-form-item v-if="!isStaffCreation" label="所属单元" prop="unitIds">
          <el-select
            v-model="form.unitIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            :loading="loadingUnits"
            :placeholder="restrictToOwnUnits ? '仅可选择本人所属单元' : '请选择所属单元（可选）'"
            class="full-width"
          >
            <el-option
              v-for="unit in unitOptions"
              :key="unit.id"
              :label="`${unit.code} ${unit.name}`"
              :value="unit.id"
            />
          </el-select>
          <div v-if="restrictToOwnUnits" class="field-tip">创建学院项目负责人时至少选择一个本人所属单元。</div>
        </el-form-item>

        <div class="form-actions">
          <el-button :disabled="saving" @click="resetForm">重置</el-button>
          <el-button type="primary" :loading="saving" @click="submit">创建账号</el-button>
        </div>
      </el-form>
    </el-card>

    <UserImportDialog
      v-model="importVisible"
      :staff-context="staffOnly"
      @imported="handleImported"
    />
  </div>
</template>

<style scoped>
.user-create-page {
  min-width: 0;
  max-width: 100%;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.page-heading {
  min-width: 0;
}

.header-actions {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.header-actions :deep(.el-button) {
  margin-left: 0;
}

.page-title {
  margin: 0;
  font-size: 20px;
  line-height: 28px;
  font-weight: 600;
}

.page-subtitle {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.form-card {
  max-width: 760px;
}

.form-alert {
  margin-bottom: var(--space-4);
}

.create-form {
  max-width: 640px;
}

.full-width {
  width: 100%;
}

.field-tip {
  width: 100%;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 20px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  padding-top: var(--space-2);
}

@media (max-width: 600px) {
  .page-header {
    align-items: stretch;
    flex-direction: column;
  }

  .header-actions {
    width: 100%;
  }

  .header-actions :deep(.el-button) {
    flex: 1 1 0;
    min-width: 0;
    min-height: 44px;
  }

  .create-form :deep(.el-form-item) {
    display: block;
  }

  .create-form :deep(.el-form-item__label) {
    justify-content: flex-start;
    width: 100% !important;
    height: auto;
    margin-bottom: 4px;
    line-height: 22px;
  }

  .create-form :deep(.el-form-item__content) {
    margin-left: 0 !important;
  }

  .form-actions :deep(.el-button) {
    min-height: 44px;
  }
}
</style>
