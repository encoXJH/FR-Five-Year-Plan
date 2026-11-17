<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getRolePermissions, updateRolePermissions } from '@/api/system'
import ReasonDialog from '@/components/ReasonDialog.vue'
import type { Role } from '@/types/enums'
import type { PermissionDefinition, RoleItem } from '@/types/system'
import { useAutoRefresh } from '@/utils/autoRefresh'
import {
  getRolePermissionState,
  ROLE_ORDER,
  selectRolePermissions,
} from '@/utils/rolePermission'

const loading = ref(false)
const saving = ref(false)
const loadFailed = ref(false)
const reasonVisible = ref(false)
const roles = ref<RoleItem[]>([])
const permissions = ref<PermissionDefinition[]>([])
const activeRole = ref<Role>('ADMIN')
const savedPermissions = ref<string[]>([])
const draftPermissions = ref<string[]>([])

const orderedRoles = computed(() =>
  ROLE_ORDER.map((role) => roles.value.find((item) => item.role === role)).filter(
    (item): item is RoleItem => Boolean(item),
  ),
)
const currentRole = computed(() => roles.value.find((item) => item.role === activeRole.value))
const groupedPermissions = computed(() => {
  const groups = new Map<string, PermissionDefinition[]>()
  for (const permission of permissions.value) {
    const items = groups.get(permission.group) ?? []
    items.push(permission)
    groups.set(permission.group, items)
  }
  return Array.from(groups, ([name, items]) => ({ name, items }))
})
const dirty = computed(() => {
  if (draftPermissions.value.length !== savedPermissions.value.length) return true
  const saved = new Set(savedPermissions.value)
  return draftPermissions.value.some((code) => !saved.has(code))
})

function resetDraft(): void {
  const selected = currentRole.value
    ? selectRolePermissions(activeRole.value, permissions.value, currentRole.value.permissions)
    : []
  savedPermissions.value = selected
  draftPermissions.value = [...selected]
}

function reset(): void {
  draftPermissions.value = [...savedPermissions.value]
}

let loadGeneration = 0

async function load(silent = false): Promise<void> {
  const generation = ++loadGeneration
  if (!silent) {
    loading.value = true
    loadFailed.value = false
  }
  try {
    const result = await getRolePermissions()
    if (generation !== loadGeneration || (silent && (dirty.value || saving.value))) return
    roles.value = result.roles
    permissions.value = result.permissions
    if (!result.roles.some((item) => item.role === activeRole.value)) {
      const firstRole = result.roles[0]?.role
      if (firstRole) activeRole.value = firstRole
    }
    resetDraft()
  } catch {
    if (!silent && generation === loadGeneration) loadFailed.value = true
  } finally {
    if (!silent && generation === loadGeneration) loading.value = false
  }
}

async function handleRoleChange(value: string | number | boolean): Promise<void> {
  const role = value as Role
  if (!ROLE_ORDER.includes(role) || role === activeRole.value || saving.value || loading.value) return
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('切换角色将放弃当前未保存的修改，是否继续？', '切换角色', {
        confirmButtonText: '放弃并切换',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  activeRole.value = role
  resetDraft()
}

function setPermission(permission: PermissionDefinition, value: string | number | boolean): void {
  if (getRolePermissionState(activeRole.value, permission) !== 'EDITABLE' || saving.value) return
  const selected = new Set(draftPermissions.value)
  if (value === true) selected.add(permission.code)
  else selected.delete(permission.code)
  draftPermissions.value = permissions.value
    .map((item) => item.code)
    .filter((code) => selected.has(code))
}

function openSaveDialog(): void {
  if (currentRole.value && dirty.value && !saving.value) reasonVisible.value = true
}

let saveGeneration = 0

async function save(reason: string): Promise<void> {
  const role = currentRole.value
  if (!role || !dirty.value || saving.value) return
  const targetRole = role.role
  const version = role.version
  const selected = new Set(draftPermissions.value)
  const payload = permissions.value.map((item) => item.code).filter((code) => selected.has(code))
  const generation = ++saveGeneration
  saving.value = true
  try {
    const result = await updateRolePermissions(targetRole, {
      permissions: payload,
      reason,
      version,
    })
    if (generation !== saveGeneration || activeRole.value !== targetRole) return
    roles.value = roles.value.map((item) => (item.role === targetRole ? result : item))
    resetDraft()
    reasonVisible.value = false
    ElMessage.success('角色权限已保存，相关用户刷新页面后生效')
  } catch {
    // 拦截器已提示
  } finally {
    if (generation === saveGeneration) saving.value = false
  }
}

onMounted(() => {
  void load()
})

useAutoRefresh(() => load(true), { skip: () => dirty.value || saving.value })
</script>

<template>
  <div v-loading="loading" class="role-permission-panel">
    <el-alert type="info" :closable="false" show-icon class="panel-alert">
      每次仅编辑一个角色。核心锁定权限不可取消；未列入当前角色可配置范围的权限不可授权。
    </el-alert>

    <div class="role-toolbar">
      <span class="toolbar-label">当前角色</span>
      <el-radio-group :model-value="activeRole" @change="handleRoleChange">
        <el-radio-button
          v-for="item in orderedRoles"
          :key="item.role"
          :value="item.role"
          :disabled="loading || saving"
        >
          {{ item.roleName }}
        </el-radio-button>
      </el-radio-group>
      <el-tag v-if="dirty" type="warning" size="small">有未保存修改</el-tag>
    </div>

    <el-alert v-if="activeRole === 'ADMIN'" type="warning" :closable="false" class="admin-alert">
      平台管理员拥有全部权限，所有授权项均为核心锁定。
    </el-alert>

    <el-result v-if="loadFailed" icon="error" title="角色权限加载失败" sub-title="请稍后重试">
      <template #extra>
        <el-button type="primary" @click="load()">重新加载</el-button>
      </template>
    </el-result>

    <template v-else>
      <section v-for="group in groupedPermissions" :key="group.name" class="permission-group">
        <h3 class="group-title">{{ group.name }}</h3>
        <el-table :data="group.items" border>
          <el-table-column prop="name" label="权限名称" min-width="180" />
          <el-table-column prop="description" label="说明" min-width="320" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description || '—' }}</template>
          </el-table-column>
          <el-table-column label="是否授权" width="190" align="center">
            <template #default="{ row }">
              <div class="permission-switch">
                <el-switch
                  :model-value="draftPermissions.includes(row.code)"
                  :disabled="getRolePermissionState(activeRole, row) !== 'EDITABLE' || saving"
                  :aria-label="`${row.name}授权`"
                  @change="(value: string | number | boolean) => setPermission(row, value)"
                />
                <el-tag
                  v-if="getRolePermissionState(activeRole, row) === 'LOCKED'"
                  type="warning"
                  size="small"
                  effect="plain"
                >
                  核心锁定
                </el-tag>
                <span
                  v-else-if="getRolePermissionState(activeRole, row) === 'UNAVAILABLE'"
                  class="unavailable-text"
                >
                  不可授权
                </span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <el-empty
        v-if="!loading && permissions.length === 0"
        description="暂无可配置权限"
        :image-size="60"
      />

      <div class="panel-footer">
        <el-button :disabled="!dirty || saving" @click="reset">重置</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="!dirty || loading || !currentRole"
          @click="openSaveDialog"
        >
          保存
        </el-button>
      </div>
    </template>

    <ReasonDialog
      v-model="reasonVisible"
      title="修改角色权限"
      :target="currentRole?.roleName ?? ''"
      confirm-text="确认保存"
      :loading="saving"
      @confirm="save"
    />
  </div>
</template>

<style scoped>
.panel-alert,
.admin-alert {
  margin-bottom: var(--space-4);
}

.role-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-5);
}

.toolbar-label {
  color: var(--el-text-color-regular);
  font-size: 14px;
}

.permission-group {
  margin-bottom: var(--space-5);
}

.group-title {
  margin: 0 0 var(--space-3);
  padding-left: 8px;
  border-left: 3px solid var(--el-color-primary);
  font-size: 14px;
  font-weight: 600;
}

.permission-switch {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
}

.unavailable-text {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.panel-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  padding-top: var(--space-4);
  border-top: 1px solid var(--el-border-color-light);
}
</style>
