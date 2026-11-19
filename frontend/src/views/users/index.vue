<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ResponsiveRecordView from '@/components/ResponsiveRecordView.vue'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import {
  listStaffUsers,
  resetStaffUserPassword,
  updateStaffUser,
} from '@/api/system'
import { useUserStore } from '@/stores/user'
import { EMPTY_DISPLAY, formatDateTime } from '@/utils/format'
import type { StaffUser } from '@/types/system'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const rows = ref<StaffUser[]>([])
const total = ref(0)
const allUnits = ref<OrgUnit[]>([])
const filters = reactive({
  keyword: '',
  unitId: '',
  isEnabled: '' as '' | 'true' | 'false',
  page: 1,
  pageSize: 20,
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

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listStaffUsers({
      keyword: filters.keyword.trim() || undefined,
      unitId: filters.unitId || undefined,
      isEnabled: filters.isEnabled === '' ? undefined : filters.isEnabled === 'true',
      page: filters.page,
      pageSize: filters.pageSize,
    })
    rows.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function search(): void {
  filters.page = 1
  void load()
}

function resetFilters(): void {
  filters.keyword = ''
  filters.unitId = ''
  filters.isEnabled = ''
  search()
}

function openCreate(): void {
  void router.push({ path: '/users/create', query: { role: 'STAFF', from: 'manage' } })
}

const editVisible = ref(false)
const saving = ref(false)
const editing = ref<StaffUser | null>(null)
const editForm = reactive({ name: '', phone: '', unitId: '' })

function openEdit(row: StaffUser): void {
  editing.value = row
  editForm.name = row.name
  editForm.phone = ''
  editForm.unitId = row.unitId ?? ''
  editVisible.value = true
}

async function submitEdit(): Promise<void> {
  if (!editing.value) return
  if (!editForm.name.trim() || !editForm.unitId) {
    ElMessage.warning('请填写姓名并选择主学院')
    return
  }
  saving.value = true
  try {
    await updateStaffUser(editing.value.id, {
      name: editForm.name.trim(),
      unitId: editForm.unitId,
      ...(editForm.phone.trim() ? { phone: editForm.phone.trim() } : {}),
      version: editing.value.version,
    })
    ElMessage.success('干事信息已更新')
    editVisible.value = false
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(row: StaffUser): Promise<void> {
  if (row.isEnabled) {
    try {
      await ElMessageBox.confirm(
        `确认停用干事「${row.name}」？停用后无法登录，已有任务分配关系保留。`,
        '停用干事',
        { confirmButtonText: '确认停用', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }
  try {
    await updateStaffUser(row.id, { isEnabled: !row.isEnabled, version: row.version })
    ElMessage.success(row.isEnabled ? '已停用' : '已启用')
    await load()
  } catch {
    // 拦截器已提示
  }
}

async function resetPassword(row: StaffUser): Promise<void> {
  let password: string
  try {
    const result = await ElMessageBox.prompt(
      `为「${row.name}」设置新密码（8–64 位），重置后该干事需重新登录。`,
      '重置密码',
      {
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
        inputType: 'password',
        inputPlaceholder: '请输入新密码',
        inputValidator: (value: string) =>
          (value && value.length >= 8 && value.length <= 64) || '密码长度须为 8–64 位',
      },
    )
    password = result.value
  } catch {
    return
  }
  try {
    await resetStaffUserPassword(row.id, { password, version: row.version })
    ElMessage.success('密码已重置，该干事需重新登录')
    await load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(() => {
  void load()
  listOrgUnits().then((list) => {
    allUnits.value = list
  }).catch(() => undefined)
})
</script>

<template>
  <div class="staff-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">用户管理</h1>
        <p class="page-subtitle">管理本学院全部纯干事账号，包括其他负责人创建的干事；角色和干事归属不可修改。</p>
      </div>
      <el-button type="primary" @click="openCreate">新增干事</el-button>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="关键字">
          <el-input
            v-model="filters.keyword"
            clearable
            placeholder="姓名 / 工号"
            style="width: 200px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item label="学院">
          <el-select v-model="filters.unitId" clearable placeholder="全部本学院" style="width: 220px">
            <el-option
              v-for="unit in ownUnits"
              :key="unit.id"
              :label="`${unit.code} ${unit.name}`"
              :value="unit.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.isEnabled" clearable placeholder="全部状态" style="width: 130px">
            <el-option label="启用" value="true" />
            <el-option label="停用" value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="list-card" v-loading="loading">
      <ResponsiveRecordView>
        <template #table>
          <el-table :data="rows" row-key="id">
            <el-table-column prop="name" label="姓名" min-width="120" />
            <el-table-column prop="username" label="工号" min-width="130" />
            <el-table-column prop="unitName" label="主学院" min-width="190" />
            <el-table-column label="手机号" min-width="130">
              <template #default="{ row }">{{ row.phone || EMPTY_DISPLAY }}</template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.isEnabled ? 'success' : 'info'" effect="light">
                  {{ row.isEnabled ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最近登录" min-width="150">
              <template #default="{ row }">{{ row.lastLoginAt ? formatDateTime(row.lastLoginAt) : EMPTY_DISPLAY }}</template>
            </el-table-column>
            <el-table-column label="操作" width="250" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link :type="row.isEnabled ? 'danger' : 'primary'" @click="toggleEnabled(row)">
                  {{ row.isEnabled ? '停用' : '启用' }}
                </el-button>
                <el-button link type="primary" @click="resetPassword(row)">重置密码</el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <template #cards>
          <div v-if="rows.length > 0" class="staff-cards">
            <article v-for="row in rows" :key="row.id" class="staff-card">
              <div class="staff-card__head">
                <div>
                  <strong>{{ row.name }}</strong>
                  <div class="staff-card__meta">{{ row.username }}</div>
                </div>
                <el-tag :type="row.isEnabled ? 'success' : 'info'" effect="light">
                  {{ row.isEnabled ? '启用' : '停用' }}
                </el-tag>
              </div>
              <div class="staff-card__line">主学院：{{ row.unitName || EMPTY_DISPLAY }}</div>
              <div class="staff-card__line">手机号：{{ row.phone || EMPTY_DISPLAY }}</div>
              <div class="staff-card__line">最近登录：{{ row.lastLoginAt ? formatDateTime(row.lastLoginAt) : EMPTY_DISPLAY }}</div>
              <div class="staff-card__actions">
                <el-button size="small" @click="openEdit(row)">编辑</el-button>
                <el-button size="small" :type="row.isEnabled ? 'danger' : 'primary'" plain @click="toggleEnabled(row)">
                  {{ row.isEnabled ? '停用' : '启用' }}
                </el-button>
                <el-button size="small" type="primary" plain @click="resetPassword(row)">重置密码</el-button>
              </div>
            </article>
          </div>
        </template>
      </ResponsiveRecordView>
      <el-empty v-if="!loading && rows.length === 0" description="本学院暂无符合条件的干事账号" />
      <div class="pager">
        <el-pagination
          v-model:current-page="filters.page"
          v-model:page-size="filters.pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </el-card>

    <el-dialog v-model="editVisible" title="编辑干事" width="480px" class="staff-edit-dialog">
      <el-alert
        v-if="editing"
        type="info"
        :closable="false"
        :title="`工号：${editing.username}；当前手机号：${editing.phone || '未填写'}`"
        class="edit-alert"
      />
      <el-form label-width="90px">
        <el-form-item label="姓名" required>
          <el-input v-model="editForm.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="新手机号">
          <el-input v-model="editForm.phone" maxlength="32" placeholder="留空表示不修改手机号" />
        </el-form-item>
        <el-form-item label="主学院" required>
          <el-select v-model="editForm.unitId" class="full-width">
            <el-option
              v-for="unit in ownUnits"
              :key="unit.id"
              :label="`${unit.code} ${unit.name}`"
              :value="unit.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.staff-page {
  min-width: 0;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: var(--space-4);
}

.page-title {
  margin: 0;
  font-size: 20px;
  line-height: 28px;
}

.page-subtitle {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.filter-card {
  margin-bottom: var(--space-4);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
  overflow-x: auto;
}

.staff-cards {
  display: grid;
  gap: 12px;
}

.staff-card {
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.staff-card__head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.staff-card__meta,
.staff-card__line {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 24px;
  overflow-wrap: anywhere;
}

.staff-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.staff-card__actions .el-button {
  margin-left: 0;
}

.full-width {
  width: 100%;
}

.edit-alert {
  margin-bottom: 16px;
}

@media (max-width: 768px) {
  .page-header {
    align-items: stretch;
    flex-direction: column;
  }

  .filter-card :deep(.el-form-item) {
    display: flex;
    margin-right: 0;
  }

  .filter-card :deep(.el-form-item__content),
  .filter-card :deep(.el-input),
  .filter-card :deep(.el-select) {
    width: 100% !important;
  }

  .pager {
    justify-content: flex-start;
  }
}
</style>
