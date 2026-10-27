<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createOrgUnit, listOrgUnitsAdmin, updateOrgUnit } from '@/api/system'
import { useUserStore } from '@/stores/user'
import { enumLabel, enumOptions } from '@/utils/enumLabels'
import { EMPTY_DISPLAY } from '@/utils/format'
import type { OrgUnitAdmin } from '@/types/system'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref<OrgUnitAdmin[]>([])
const typeOptions = enumOptions('OrgUnitType', userStore.enums)

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await listOrgUnitsAdmin()
  } finally {
    loading.value = false
  }
}

// ---------- 新增 / 编辑 ----------
const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref<OrgUnitAdmin | null>(null)
const form = reactive({
  code: '',
  name: '',
  type: 'COLLEGE' as string,
  sort: null as number | null,
  isBAssignable: false,
})

function openCreate(): void {
  editing.value = null
  form.code = ''
  form.name = ''
  form.type = 'COLLEGE'
  form.sort = null
  form.isBAssignable = false
  dialogVisible.value = true
}

function openEdit(row: OrgUnitAdmin): void {
  editing.value = row
  form.code = row.code
  form.name = row.name
  form.type = row.type ?? 'COLLEGE'
  form.sort = row.sort ?? null
  form.isBAssignable = row.isBAssignable
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning('请填写单元名称')
    return
  }
  if (!editing.value && !form.code.trim()) {
    ElMessage.warning('请填写单元编码')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateOrgUnit(editing.value.id, {
        name: form.name.trim(),
        sort: form.sort,
        isBAssignable: form.isBAssignable,
        version: editing.value.version,
      })
      ElMessage.success('执行单元已更新')
    } else {
      await createOrgUnit({
        code: form.code.trim().toUpperCase(),
        name: form.name.trim(),
        type: form.type,
        sort: form.sort,
        isBAssignable: form.isBAssignable,
      })
      ElMessage.success('执行单元已新增')
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
async function toggleEnabled(row: OrgUnitAdmin): Promise<void> {
  if (row.isEnabled) {
    try {
      await ElMessageBox.confirm(
        `停用后该单元不能被分配新任务；若存在已下发 / 执行中的在途任务，服务端将拒绝停用。确认停用「${row.name}」？`,
        '停用执行单元',
        { confirmButtonText: '确认停用', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }
  try {
    await updateOrgUnit(row.id, { isEnabled: !row.isEnabled, version: row.version })
    ElMessage.success(row.isEnabled ? '已停用' : '已启用')
    await load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel-toolbar">
      <el-button type="primary" @click="openCreate">新增执行单元</el-button>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="code" label="编码" width="90" />
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column label="类型" width="150">
        <template #default="{ row }">
          {{ row.type ? enumLabel('OrgUnitType', row.type, userStore.enums) : EMPTY_DISPLAY }}
        </template>
      </el-table-column>
      <el-table-column label="排序" width="80" align="right">
        <template #default="{ row }">{{ row.sort ?? EMPTY_DISPLAY }}</template>
      </el-table-column>
      <el-table-column label="可承接新 B" width="110" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.isBAssignable ? 'success' : 'info'">
            {{ row.isBAssignable ? '允许' : '不允许' }}
          </el-tag>
        </template>
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

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑执行单元' : '新增执行单元'"
      width="480px"
    >
      <el-form label-width="90px">
        <el-form-item label="编码" required>
          <el-input
            v-model="form.code"
            :disabled="!!editing"
            placeholder="如 C11（上线后不可修改）"
          />
          <div v-if="editing" class="field-tip">编码上线后不可修改</div>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 智能产业学院" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" :disabled="!!editing" style="width: 100%">
            <el-option v-for="o in typeOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <div v-if="editing" class="field-tip">类型创建后不可修改</div>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" style="width: 160px" />
        </el-form-item>
        <el-form-item label="可承接新 B">
          <el-switch v-model="form.isBAssignable" />
          <div class="field-tip">与组织启停独立；仅启用且允许承接的单元可参与新增分解或转移接收</div>
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

.field-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 20px;
}
</style>
