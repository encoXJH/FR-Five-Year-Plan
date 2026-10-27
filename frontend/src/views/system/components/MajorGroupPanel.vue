<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createMajorGroup, listMajorGroups, updateMajorGroup } from '@/api/system'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { getUserOptions } from '@/api/user'
import { EMPTY_DISPLAY } from '@/utils/format'
import type { MajorGroup } from '@/types/system'
import type { UserOption } from '@/types/user'

const loading = ref(false)
const rows = ref<MajorGroup[]>([])
const units = ref<OrgUnit[]>([])
const userOptions = ref<UserOption[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await listMajorGroups()
  } finally {
    loading.value = false
  }
}

// ---------- 新增 / 编辑 ----------
const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref<MajorGroup | null>(null)
const form = reactive({
  name: '',
  unitId: '' as string,
  ownerId: '' as string,
})

function openCreate(): void {
  editing.value = null
  form.name = ''
  form.unitId = ''
  form.ownerId = ''
  dialogVisible.value = true
}

function openEdit(row: MajorGroup): void {
  editing.value = row
  form.name = row.name
  form.unitId = row.unitId ?? ''
  form.ownerId = row.ownerId ?? ''
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning('请填写专业群名称')
    return
  }
  if (!form.unitId) {
    ElMessage.warning('请选择所属学院')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateMajorGroup(editing.value.id, {
        name: form.name.trim(),
        unitId: form.unitId,
        ownerId: form.ownerId || null,
        version: editing.value.version,
      })
      ElMessage.success('专业群已更新')
    } else {
      await createMajorGroup({
        name: form.name.trim(),
        unitId: form.unitId,
        ownerId: form.ownerId || null,
      })
      ElMessage.success('专业群已新增')
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
async function toggleEnabled(row: MajorGroup): Promise<void> {
  try {
    await updateMajorGroup(row.id, { isEnabled: !row.isEnabled, version: row.version })
    ElMessage.success(row.isEnabled ? '已停用' : '已启用')
    await load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(() => {
  load()
  listOrgUnits()
    .then((list) => {
      units.value = list
    })
    .catch(() => undefined)
  getUserOptions()
    .then((list) => {
      userOptions.value = list
    })
    .catch(() => undefined)
})
</script>

<template>
  <div>
    <div class="panel-toolbar">
      <el-button type="primary" @click="openCreate">新增专业群</el-button>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="name" label="名称" min-width="200" />
      <el-table-column label="所属学院" min-width="160">
        <template #default="{ row }">{{ row.unitName ?? EMPTY_DISPLAY }}</template>
      </el-table-column>
      <el-table-column label="负责人" width="120">
        <template #default="{ row }">{{ row.ownerName ?? EMPTY_DISPLAY }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.isEnabled ? 'success' : 'info'">
            {{ row.isEnabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
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

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑专业群' : '新增专业群'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 软件技术专业群" />
        </el-form-item>
        <el-form-item label="所属学院" required>
          <el-select v-model="form.unitId" clearable style="width: 100%">
            <el-option v-for="u in units" :key="u.id" :label="`${u.code} ${u.name}`" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="负责人">
          <el-select v-model="form.ownerId" clearable filterable style="width: 100%">
            <el-option
              v-for="u in userOptions"
              :key="u.id"
              :label="`${u.name}（${u.username}）`"
              :value="u.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.panel-toolbar {
  display: flex;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}
</style>
