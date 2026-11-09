<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { showConfirmDialog, showToast } from 'vant'
import PageState from '@/components/PageState.vue'
import ReasonDialog from '@/components/ReasonDialog.vue'
import {
  createUser,
  listOrgUnitsAdmin,
  listUsers,
  resetUserPassword,
  setUserRoles,
  updateUser,
} from '@/api/system'
import type { OrgUnitAdmin, SystemUser } from '@/types/system'
import type { Role } from '@/types/enums'
import { enumLabel, enumOptions } from '@/utils/enumLabels'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'

const userStore = useUserStore()
const loading = ref(false)
const rows = ref<SystemUser[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const keyword = ref('')
const units = ref<OrgUnitAdmin[]>([])
const roleOptions = computed(() => enumOptions('Role', userStore.enums))

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listUsers({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      pageSize,
    })
    rows.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

function changePage(next: number): void {
  page.value = next
  void load()
}

// 新增/编辑
const formVisible = ref(false)
const saving = ref(false)
const editing = ref<SystemUser | null>(null)
const unitPickerVisible = ref(false)
const form = reactive({
  name: '',
  username: '',
  password: '',
  phone: '',
  unitId: '',
  roles: [] as Role[],
  isEnabled: true,
})
const unitLabel = computed(() => units.value.find((item) => item.id === form.unitId)?.name ?? '')

function openCreate(): void {
  editing.value = null
  form.name = ''
  form.username = ''
  form.password = ''
  form.phone = ''
  form.unitId = ''
  form.roles = []
  form.isEnabled = true
  formVisible.value = true
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
  formVisible.value = true
}

function toggleRole(role: string): void {
  const value = role as Role
  form.roles = form.roles.includes(value)
    ? form.roles.filter((item) => item !== value)
    : [...form.roles, value]
}

async function submit(): Promise<void> {
  if (!form.name.trim() || !form.username.trim()) {
    showToast('请填写姓名和工号')
    return
  }
  if (!editing.value && !form.password) {
    showToast('请填写初始密码')
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
      showToast('用户已更新')
    } else {
      await createUser({
        name: form.name.trim(),
        username: form.username.trim(),
        password: form.password,
        phone: form.phone.trim() || undefined,
        unitId: form.unitId || undefined,
        roles: form.roles,
      })
      showToast('用户已新增')
    }
    formVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(row: SystemUser): Promise<void> {
  if (row.isEnabled) {
    try {
      await showConfirmDialog({
        title: '停用用户',
        message: `确认停用用户「${row.name}」？停用后无法登录。`,
        confirmButtonText: '确认停用',
      })
    } catch {
      return
    }
  }
  await updateUser(row.id, { isEnabled: !row.isEnabled, version: row.version })
  showToast(row.isEnabled ? '已停用' : '已启用')
  await load()
}

// 重置密码
const passwordVisible = ref(false)
const passwordTarget = ref<SystemUser | null>(null)
const passwordValue = ref('')
const resetting = ref(false)
function openResetPassword(row: SystemUser): void {
  passwordTarget.value = row
  passwordValue.value = ''
  passwordVisible.value = true
}
async function submitResetPassword(): Promise<void> {
  if (!passwordTarget.value) return
  if (passwordValue.value.length < 8 || passwordValue.value.length > 64) {
    showToast('密码长度须为 8–64 位')
    return
  }
  resetting.value = true
  try {
    await resetUserPassword(passwordTarget.value.id, passwordValue.value)
    showToast('密码已重置，该用户需重新登录')
    passwordVisible.value = false
  } finally {
    resetting.value = false
  }
}

// 角色
const rolesVisible = ref(false)
const rolesTarget = ref<SystemUser | null>(null)
const rolesDraft = ref<Role[]>([])
const reasonVisible = ref(false)
const rolesSaving = ref(false)
function openRoles(row: SystemUser): void {
  rolesTarget.value = row
  rolesDraft.value = [...row.roles]
  rolesVisible.value = true
}
function proceedRoles(): void {
  if (rolesDraft.value.length === 0) {
    showToast('请至少选择一个角色')
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
      roles: rolesDraft.value,
      reason,
      version: rolesTarget.value.version,
    })
    showToast('角色已更新')
    reasonVisible.value = false
    await load()
  } finally {
    rolesSaving.value = false
  }
}

function openActions(row: SystemUser): void {
  actionTarget.value = row
  actionVisible.value = true
}
const actionVisible = ref(false)
const actionTarget = ref<SystemUser | null>(null)
const actionItems = computed(() => {
  const row = actionTarget.value
  if (!row) return []
  return [
    { name: '编辑用户', key: 'edit' },
    { name: '设置角色', key: 'roles' },
    { name: '重置密码', key: 'password' },
    { name: row.isEnabled ? '停用用户' : '启用用户', key: 'toggle', color: row.isEnabled ? '#E45B5B' : undefined },
  ]
})
async function handleAction(action: { key: string }): Promise<void> {
  const row = actionTarget.value
  actionVisible.value = false
  if (!row) return
  if (action.key === 'edit') openEdit(row)
  else if (action.key === 'roles') openRoles(row)
  else if (action.key === 'password') openResetPassword(row)
  else if (action.key === 'toggle') await toggleEnabled(row)
}

onMounted(async () => {
  void load()
  try {
    units.value = await listOrgUnitsAdmin()
  } catch {
    // 忽略
  }
})
</script>

<template>
  <div>
    <div class="panel-head">
      <h2>用户管理</h2>
      <van-button size="small" type="primary" @click="openCreate">新增用户</van-button>
    </div>

    <van-search v-model="keyword" placeholder="姓名 / 工号" shape="round" @search="search" @clear="search" />

    <PageState :loading="loading" :empty="rows.length === 0" empty-text="暂无用户">
      <div class="user-list">
        <article v-for="row in rows" :key="row.id" class="user-card">
          <div class="row--between" @click="openActions(row)">
            <div class="row">
              <strong>{{ row.name }}</strong>
              <span class="small muted">{{ row.username }}</span>
            </div>
            <span class="chip" :class="row.isEnabled ? 'chip--success' : 'chip--gray'">{{ row.isEnabled ? '启用' : '停用' }}</span>
          </div>
          <div class="small muted">{{ row.unitName ?? '校级账号' }} · {{ row.roles.map((role) => enumLabel('Role', role, userStore.enums)).join(' / ') }}</div>
          <div class="small muted">最近登录 {{ formatDateTime(row.lastLoginAt) }}</div>
          <div class="actions">
            <van-button size="mini" plain type="primary" @click="openActions(row)">操作</van-button>
          </div>
        </article>
      </div>
      <div class="pager">
        <van-button size="small" :disabled="page <= 1" @click="changePage(page - 1)">上一页</van-button>
        <span class="small muted">{{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }}</span>
        <van-button size="small" :disabled="page >= Math.ceil(total / pageSize)" @click="changePage(page + 1)">下一页</van-button>
      </div>
    </PageState>

    <van-action-sheet
      v-model:show="actionVisible"
      :actions="actionItems"
      cancel-text="取消"
      close-on-click-action
      @select="handleAction"
    />

    <van-popup v-model:show="formVisible" position="bottom" round :style="{ maxHeight: '88vh' }">
      <div class="sheet">
        <div class="sheet-head">
          <strong>{{ editing ? '编辑用户' : '新增用户' }}</strong>
          <button type="button" class="close-btn" @click="formVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="field">
          <label>姓名<span class="required">*</span></label>
          <van-field v-model="form.name" />
        </div>
        <div class="field">
          <label>工号<span class="required">*</span></label>
          <van-field v-model="form.username" :disabled="!!editing" />
        </div>
        <div v-if="!editing" class="field">
          <label>初始密码<span class="required">*</span></label>
          <van-field v-model="form.password" type="password" placeholder="8–64 位" />
        </div>
        <div class="field">
          <label>手机号</label>
          <van-field v-model="form.phone" />
        </div>
        <div class="field">
          <label>所属单元</label>
          <van-field readonly is-link :model-value="unitLabel" placeholder="校级账号" @click="unitPickerVisible = true" />
        </div>
        <div v-if="!editing" class="field">
          <label>角色</label>
          <div class="chip-group">
            <button
              v-for="option in roleOptions"
              :key="option.value"
              type="button"
              class="filter-chip"
              :class="{ 'is-active': form.roles.includes(option.value as Role) }"
              @click="toggleRole(option.value)"
            >{{ option.label }}</button>
          </div>
        </div>
        <div class="switch-row">
          <div class="field-label">启用</div>
          <van-switch v-model="form.isEnabled" size="22px" />
        </div>
        <van-button block type="primary" :loading="saving" @click="submit">保存</van-button>
      </div>
    </van-popup>

    <van-popup v-model:show="unitPickerVisible" position="bottom" round :style="{ height: '60vh' }">
      <div class="sheet picker">
        <div class="sheet-head"><strong>选择执行单元</strong></div>
        <div class="picker-list">
          <button type="button" class="picker-item" @click="form.unitId = ''; unitPickerVisible = false">校级账号</button>
          <button
            v-for="unit in units"
            :key="unit.id"
            type="button"
            class="picker-item"
            @click="form.unitId = unit.id; unitPickerVisible = false"
          >{{ unit.code }} {{ unit.name }}</button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="rolesVisible" position="bottom" round safe-area-inset-bottom>
      <div class="sheet">
        <div class="sheet-head">
          <strong>设置角色 · {{ rolesTarget?.name }}</strong>
          <button type="button" class="close-btn" @click="rolesVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="chip-group">
          <button
            v-for="option in roleOptions"
            :key="option.value"
            type="button"
            class="filter-chip"
            :class="{ 'is-active': rolesDraft.includes(option.value as Role) }"
            @click="rolesDraft = rolesDraft.includes(option.value as Role) ? rolesDraft.filter((item) => item !== option.value) : [...rolesDraft, option.value as Role]"
          >{{ option.label }}</button>
        </div>
        <van-button block type="primary" style="margin-top: 16px" @click="proceedRoles">下一步</van-button>
      </div>
    </van-popup>

    <van-popup v-model:show="passwordVisible" position="bottom" round safe-area-inset-bottom>
      <div class="sheet">
        <div class="sheet-head">
          <strong>重置密码 · {{ passwordTarget?.name }}</strong>
          <button type="button" class="close-btn" @click="passwordVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <van-field v-model="passwordValue" type="password" placeholder="请输入新密码（8–64 位）" />
        <van-button block type="primary" style="margin-top: 16px" :loading="resetting" @click="submitResetPassword">确认重置</van-button>
      </div>
    </van-popup>

    <ReasonDialog
      v-model="reasonVisible"
      title="修改角色权限"
      label="变更原因"
      confirm-text="确认保存"
      :loading="rolesSaving"
      @confirm="submitRoles"
    />
  </div>
</template>

<style scoped>
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.panel-head h2 {
  margin: 0;
  font-size: 18px;
}
.user-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}
.user-card {
  padding: 10px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid var(--line);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 12px 0;
}
.sheet {
  padding: 16px 16px calc(16px + var(--safe-bottom));
}
.sheet.picker {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  font-size: 16px;
}
.close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background: #f4f6fa;
  color: var(--text-secondary);
}
.field {
  margin-bottom: 10px;
}
.field label,
.field-label {
  display: block;
  margin-bottom: 4px;
  color: var(--text-secondary);
  font-size: 13px;
}
.required {
  color: var(--danger);
}
.field :deep(.van-field),
.sheet > :deep(.van-field) {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  padding: 10px 12px;
}
.chip-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.picker-list {
  flex: 1;
  overflow-y: auto;
}
.picker-item {
  width: 100%;
  min-height: 46px;
  padding: 8px 4px;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--text);
  text-align: left;
  font-size: 13px;
}
</style>
