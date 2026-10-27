<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createDirection, deleteDirection, listDirections, updateDirection } from '@/api/direction'
import { listIndicators, updateIndicatorDirection } from '@/api/indicator'
import { getPlan, listPlans } from '@/api/plan'
import { listUsers } from '@/api/system'
import { useUserStore } from '@/stores/user'
import { EMPTY_DISPLAY } from '@/utils/format'
import type { Direction, PlanCard, PlanDetail } from '@/types/plan'
import type { Indicator, IndicatorTreeNode } from '@/types/indicator'
import type { SystemUser } from '@/types/system'

const userStore = useUserStore()
const canManage = computed(() => userStore.roles.includes('ADMIN') && userStore.can('system:manage'))

const plans = ref<PlanCard[]>([])
const planId = ref('')
const plan = ref<PlanDetail | null>(null)
const directions = ref<Direction[]>([])
const leaders = ref<SystemUser[]>([])
const aRows = ref<Indicator[]>([])
const loading = ref(false)
const leadersLoading = ref(false)

const assignmentDrafts = reactive<Record<string, string | null>>({})
const assignmentSavingId = ref('')
const assignmentReadonly = computed(
  () => (plan.value?.indicatorsFinalized ?? false) || plan.value?.status === 'ARCHIVED',
)

function flattenA(nodes: IndicatorTreeNode[]): Indicator[] {
  return nodes.flatMap((node) => [
    ...(node.level === 'A' ? [node] : []),
    ...flattenA(node.children ?? []),
  ])
}

async function loadIndicators(): Promise<void> {
  if (!planId.value) return
  const tree = await listIndicators(planId.value, { view: 'tree' })
  aRows.value = flattenA(tree)
  for (const row of aRows.value) assignmentDrafts[row.id] = row.directionId ?? null
}

async function loadAll(): Promise<void> {
  if (!planId.value || !canManage.value) return
  loading.value = true
  try {
    const [planDetail, directionList] = await Promise.all([
      getPlan(planId.value),
      listDirections(planId.value),
      loadIndicators(),
    ])
    plan.value = planDetail
    directions.value = directionList
  } finally {
    loading.value = false
  }
}

async function loadLeaders(): Promise<void> {
  leadersLoading.value = true
  try {
    const result = await listUsers({ role: 'LEADER', isEnabled: true, page: 1, pageSize: 200 })
    leaders.value = result.items.filter((user) => user.isEnabled && user.roles.includes('LEADER'))
  } finally {
    leadersLoading.value = false
  }
}

onMounted(async () => {
  if (!canManage.value) return
  try {
    const [planList] = await Promise.all([listPlans(), loadLeaders()])
    plans.value = planList
    planId.value = (planList.find((item) => item.status === 'ACTIVE') ?? planList[0])?.id ?? ''
  } catch {
    // 拦截器已提示
  }
})

watch(planId, (id, previous) => {
  if (id && id !== previous) void loadAll().catch(() => undefined)
})

// ---------- S 级任务 ----------
const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref<Direction | null>(null)
const form = reactive({ name: '', sort: null as number | null, leaderId: null as string | null })

function openCreate(): void {
  editing.value = null
  form.name = ''
  form.sort = null
  form.leaderId = null
  dialogVisible.value = true
}

function openEdit(row: Direction): void {
  editing.value = row
  form.name = row.name
  form.sort = row.sort
  form.leaderId = row.leaderId
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning('请填写 S 级任务名称')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateDirection(editing.value.id, {
        name: form.name.trim(),
        sort: form.sort,
        leaderId: form.leaderId,
        version: editing.value.version,
      })
      ElMessage.success('S 级任务已更新')
    } else {
      await createDirection(planId.value, {
        name: form.name.trim(),
        sort: form.sort,
        leaderId: form.leaderId,
      })
      ElMessage.success('S 级任务已新增')
    }
    dialogVisible.value = false
    directions.value = await listDirections(planId.value)
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function remove(row: Direction): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除 S 级任务「${row.name}」？已关联 A 级指标时服务端将拒绝删除。`,
      '删除 S 级任务',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
    await deleteDirection(row.id)
    ElMessage.success('S 级任务已删除')
    await loadAll()
  } catch {
    // 取消或拦截器已提示
  }
}

function directionName(id: string | null | undefined): string {
  return directions.value.find((item) => item.id === id)?.name ?? EMPTY_DISPLAY
}

function assignmentDirty(row: Indicator): boolean {
  return (assignmentDrafts[row.id] ?? null) !== (row.directionId ?? null)
}

async function saveAssignment(row: Indicator): Promise<void> {
  if (assignmentReadonly.value || !assignmentDirty(row)) return
  assignmentSavingId.value = row.id
  try {
    await updateIndicatorDirection(row.id, assignmentDrafts[row.id] ?? null, row.version)
    ElMessage.success(`已保存「${row.code}」的 S 归属`)
    await loadIndicators()
  } catch {
    // 拦截器已提示
  } finally {
    assignmentSavingId.value = ''
  }
}
</script>

<template>
  <el-alert v-if="!canManage" type="warning" :closable="false" title="仅具有 system:manage 权限的管理员可配置 S 级任务" />
  <div v-else v-loading="loading">
    <div class="panel-toolbar">
      <el-select v-model="planId" placeholder="请选择计划" style="width: 320px">
        <el-option v-for="item in plans" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-button :disabled="!planId" @click="loadAll">刷新</el-button>
      <el-button type="primary" :disabled="!planId" @click="openCreate">新增 S 级任务</el-button>
    </div>

    <el-empty v-if="!planId" description="暂无可配置的计划" />
    <template v-else>
      <el-card shadow="never" class="section-card">
        <template #header><span class="card-title">S 级任务与总负责人</span></template>
        <el-table :data="directions" border>
          <el-table-column prop="name" label="S 级任务" min-width="220" />
          <el-table-column prop="sort" label="排序" width="90" align="right">
            <template #default="{ row }">{{ row.sort ?? EMPTY_DISPLAY }}</template>
          </el-table-column>
          <el-table-column label="总负责人（LEADER）" min-width="160">
            <template #default="{ row }">{{ row.leader?.name ?? EMPTY_DISPLAY }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty><el-empty :image-size="70" description="尚未配置 S 级任务" /></template>
        </el-table>
      </el-card>

      <el-card shadow="never" class="section-card">
        <template #header><span class="card-title">A 级指标的 S 归属</span></template>
        <el-alert
          v-if="assignmentReadonly"
          type="info"
          :closable="false"
          title="指标体系已定稿或计划已归档，S 归属只读"
          class="assignment-alert"
        />
        <el-table :data="aRows" border>
          <el-table-column prop="code" label="A 编号" width="110" class-name="mono" />
          <el-table-column prop="name" label="A 级指标" min-width="220" show-overflow-tooltip />
          <el-table-column label="当前归属" min-width="160">
            <template #default="{ row }">{{ directionName(row.directionId) }}</template>
          </el-table-column>
          <el-table-column label="设置归属" min-width="220">
            <template #default="{ row }">
              <el-select
                v-model="assignmentDrafts[row.id]"
                clearable
                filterable
                placeholder="不归属 S"
                :disabled="assignmentReadonly"
                style="width: 100%"
              >
                <el-option v-for="item in directions" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :disabled="assignmentReadonly || !assignmentDirty(row)"
                :loading="assignmentSavingId === row.id"
                @click="saveAssignment(row)"
              >
                保存
              </el-button>
            </template>
          </el-table-column>
          <template #empty><el-empty :image-size="70" description="该计划暂无 A 级指标" /></template>
        </el-table>
      </el-card>
    </template>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑 S 级任务' : '新增 S 级任务'" width="480px">
      <el-form label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" controls-position="right" />
        </el-form-item>
        <el-form-item label="总负责人">
          <el-select
            v-model="form.leaderId"
            clearable
            filterable
            :loading="leadersLoading"
            placeholder="仅可选择 LEADER 角色用户"
            style="width: 100%"
          >
            <el-option
              v-for="user in leaders"
              :key="user.id"
              :label="`${user.name}（${user.username}）`"
              :value="user.id"
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

.section-card + .section-card {
  margin-top: var(--space-4);
}

.card-title {
  font-weight: 600;
}

.assignment-alert {
  margin-bottom: var(--space-3);
}

.mono {
  font-family: var(--app-font-mono);
}
</style>
