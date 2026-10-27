<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createUser,
  listUsers,
  resetUserPassword,
  setUserRoles,
  updateUser,
} from '@/api/system'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { useUserStore } from '@/stores/user'
import { enumLabel, enumOptions } from '@/utils/enumLabels'
import { EMPTY_DISPLAY, formatDateTime } from '@/utils/format'
import ReasonDialog from '@/components/ReasonDialog.vue'
import type { Role } from '@/types/enums'
import type { SystemUser } from '@/types/system'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref<SystemUser[]>([])
const total = ref(0)
const units = ref<OrgUnit[]>([])

const filters = reactive({
  keyword: '',
  unitId: '' as string,
  page: 1,
  pageSize: 20,
})

const roleOptions = computed(() => enumOptions('Role', userStore.enums))

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listUsers({
      keyword: filters.keyword.trim() || undefined,
      unitId: filters.unitId || undefined,
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
  load()
}

// ---------- 新增 / 编辑 ----------
const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref<SystemUser | null>(null)
const form = reactive({
  name: '',
  username: '',
  password: '',
  phone: '',
  unitId: '' as string,
  roles: [] as Role[],
  isEnabled: true,
})

function openCreate(): void {
  editing.value = null
  form.name = ''
  form.username = ''
  form.password = ''
  form.phone = ''
  form.unitId = ''
  form.roles = []
  form.isEnabled = true
  dialogVisible.value = true
}

function openEdit(row: SystemUser): void {
  editing.value = row
  form.name = row.name
  form.username = row.username
  form.password = ''
  form.phone = ''
  form.unitId = row.unitId ?? ''
  form.roles = [...row.roles]
  form.isEnabled = row.isEnabled
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.name.trim() || !form.username.trim()) {
    ElMessage.warning('请填写姓名和工号')
    return
  }
  if (!editing.value && !form.password) {
    ElMessage.warning('请填写初始密码')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateUser(editing.value.id, {
        name: form.name.trim(),
        unitId: form.unitId || null,
        isEnabled: form.isEnabled,
        version: editing.value.version,
      })
      ElMessage.success('用户已更新')
    } else {
      await createUser({
        name: form.name.trim(),
        username: form.username.trim(),
        password: form.password,
        phone: form.phone.trim() || undefined,
        unitId: form.unitId || undefined,
        roles: form.roles,
      })
      ElMessage.success('用户已新增')
    }
    dialogVisible.value = false
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

// ---------- 停用 / 启用 ----------
async function toggleEnabled(row: SystemUser): Promise<void> {
  if (row.isEnabled) {
    try {
      await ElMessageBox.confirm(`确认停用用户「${row.name}」？停用后无法登录。`, '停用用户', {
        confirmButtonText: '确认停用',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  try {
    await updateUser(row.id, { isEnabled: !row.isEnabled, version: row.version })
    ElMessage.success(row.isEnabled ? '已停用' : '已启用')
    await load()
  } catch {
    // 拦截器已提示
  }
}

// ---------- 重置密码（管理员直接设置新密码；重置后该用户全部 refreshToken 被吊销） ----------
async function handleResetPassword(row: SystemUser): Promise<void> {
  let password: string
  try {
    const result = await ElMessageBox.prompt(
      `为「${row.name}」设置新密码（8–64 位），重置后该用户需重新登录。`,
      '重置密码',
      {
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
        inputType: 'password',
        inputPlaceholder: '请输入新密码',
        inputValidator: (v: string) =>
          (v && v.length >= 8 && v.length <= 64) || '密码长度须为 8–64 位',
      },
    )
    password = result.value
  } catch {
    return
  }
  try {
    await resetUserPassword(row.id, password)
    ElMessage.success('密码已重置，该用户需重新登录')
  } catch {
    // 拦截器已提示
  }
}

// ---------- 设置角色（敏感操作：先选角色，再用 ReasonDialog 填原因，UI 规范 7.5） ----------
const rolesVisible = ref(false)
const rolesSaving = ref(false)
const rolesTarget = ref<SystemUser | null>(null)
const rolesForm = reactive({ roles: [] as Role[] })
const reasonVisible = ref(false)

function openSetRoles(row: SystemUser): void {
  rolesTarget.value = row
  rolesForm.roles = [...row.roles]
  rolesVisible.value = true
}

function proceedSetRoles(): void {
  if (rolesForm.roles.length === 0) {
    ElMessage.warning('请至少选择一个角色')
    return
  }
  rolesVisible.value = false
  reasonVisible.value = true
}

async function submitRoles(reason: string): Promise<void> {
  if (!rolesTarget.value) return
  rolesSaving.value = true
  try {
    await setUserRoles(rolesTarget.value.id, {
      roles: rolesForm.roles,
      reason,
      version: rolesTarget.value.version,
    })
    ElMessage.success('角色已更新')
    reasonVisible.value = false
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    rolesSaving.value = false
  }
}

onMounted(() => {
  load()
  listOrgUnits()
    .then((list) => {
      units.value = list
    })
    .catch(() => undefined)
})
</script>

<template>
  <div>
    <div class="panel-toolbar">
      <el-input
        v-model="filters.keyword"
        placeholder="姓名 / 工号"
        clearable
        style="width: 200px"
        @keyup.enter="search"
      />
      <el-select v-model="filters.unitId" clearable placeholder="所属单元" style="width: 180px">
        <el-option v-for="u in units" :key="u.id" :label="`${u.code} ${u.name}`" :value="u.id" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <div class="toolbar-spacer" />
      <el-button type="primary" @click="openCreate">新增用户</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="name" label="姓名" width="110" />
      <el-table-column prop="username" label="工号" width="120" />
      <el-table-column label="手机号" width="130">
        <template #default="{ row }">{{ row.phone ?? EMPTY_DISPLAY }}</template>
      </el-table-column>
      <el-table-column label="所属单元" min-width="140">
        <template #default="{ row }">{{ row.unitName ?? EMPTY_DISPLAY }}</template>
      </el-table-column>
      <el-table-column label="角色" min-width="200">
        <template #default="{ row }">
          <el-tag
            v-for="r in row.roles"
            :key="r"
            size="small"
            effect="plain"
            class="role-tag"
          >
            {{ enumLabel('Role', r, userStore.enums) }}
          </el-tag>
          <span v-if="row.roles.length === 0">{{ EMPTY_DISPLAY }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.isEnabled ? 'success' : 'info'">
            {{ row.isEnabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近登录" width="150">
        <template #default="{ row }">{{ formatDateTime(row.lastLoginAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" size="small" @click="openSetRoles(row)">设置角色</el-button>
          <el-button link type="warning" size="small" @click="handleResetPassword(row)">
            重置密码
          </el-button>
          <el-button
            link
            :type="row.isEnabled ? 'danger' : 'success'"
            size="small"
            @click="toggleEnabled(row)"
          >
            {{ row.isEnabled ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="pagination-bar">
      <el-pagination
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="total"
        :current-page="filters.page"
        :page-size="filters.pageSize"
        :page-sizes="[20, 50, 100]"
        @size-change="
          (s: number) => {
            filters.pageSize = s
            filters.page = 1
            load()
          }
        "
        @current-change="
          (p: number) => {
            filters.page = p
            load()
          }
        "
      />
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑用户' : '新增用户'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="姓名" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="工号" required>
          <el-input v-model="form.username" :disabled="!!editing" />
        </el-form-item>
        <el-form-item v-if="!editing" label="初始密码" required>
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-form-item v-if="!editing" label="手机号">
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="所属单元">
          <el-select v-model="form.unitId" clearable style="width: 100%">
            <el-option
              v-for="u in units"
              :key="u.id"
              :label="`${u.code} ${u.name}`"
              :value="u.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!editing" label="角色">
          <el-select v-model="form.roles" multiple style="width: 100%">
            <el-option v-for="o in roleOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editing" label="状态">
          <el-switch v-model="form.isEnabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rolesVisible" title="设置角色" width="480px">
      <el-form label-width="90px">
        <el-form-item label="用户">
          <span>{{ rolesTarget?.name }}（{{ rolesTarget?.username }}）</span>
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="rolesForm.roles" multiple style="width: 100%">
            <el-option v-for="o in roleOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rolesVisible = false">取消</el-button>
        <el-button type="primary" @click="proceedSetRoles">确定</el-button>
      </template>
    </el-dialog>

    <ReasonDialog
      v-model="reasonVisible"
      title="修改用户角色"
      :target="rolesTarget ? `${rolesTarget.name}（${rolesTarget.username}）` : ''"
      confirm-text="确认修改"
      :loading="rolesSaving"
      @confirm="submitRoles"
    />
  </div>
</template>

<style scoped>
.panel-toolbar {
  display: flex;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.toolbar-spacer {
  flex: 1;
}

.role-tag {
  margin-right: 4px;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}
</style>
