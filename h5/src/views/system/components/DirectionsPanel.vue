<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { showConfirmDialog, showToast } from 'vant'
import PageState from '@/components/PageState.vue'
import PlanSwitcher from '@/components/PlanSwitcher.vue'
import { createDirection, deleteDirection, listDirections, updateDirection } from '@/api/direction'
import { listIndicators, updateIndicatorDirection } from '@/api/indicator'
import { getPlan, listPlans } from '@/api/plan'
import { listUsers } from '@/api/system'
import { useUserStore } from '@/stores/user'
import type { Direction, PlanCard, PlanDetail } from '@/types/plan'
import type { IndicatorTreeNode } from '@/types/indicator'
import type { SystemUser } from '@/types/system'

const userStore = useUserStore()
const plans = ref<PlanCard[]>([])
const planId = ref('')
const plan = ref<PlanDetail | null>(null)
const directions = ref<Direction[]>([])
const leaders = ref<SystemUser[]>([])
const aRows = ref<IndicatorTreeNode[]>([])
const loading = ref(false)
const assignmentDrafts = reactive<Record<string, string | null>>({})
const assignmentSavingId = ref('')

const planLabel = computed(() => plans.value.find((item) => item.id === planId.value)?.name ?? '')

function flattenA(nodes: IndicatorTreeNode[]): IndicatorTreeNode[] {
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
  if (!planId.value) return
  loading.value = true
  try {
    const [detail, directionList] = await Promise.all([
      getPlan(planId.value),
      listDirections(planId.value),
      loadIndicators(),
    ])
    plan.value = detail
    directions.value = directionList
  } finally {
    loading.value = false
  }
}

async function loadLeaders(): Promise<void> {
  try {
    const result = await listUsers({ role: 'LEADER', isEnabled: true, page: 1, pageSize: 200 })
    leaders.value = result.items.filter((user) => user.isEnabled && user.roles.includes('LEADER'))
  } catch {
    // 忽略
  }
}

// 新增/编辑
const formVisible = ref(false)
const saving = ref(false)
const editing = ref<Direction | null>(null)
const leaderPickerVisible = ref(false)
const form = reactive({ name: '', sort: 0, leaderId: '' })
const leaderLabel = computed(() => leaders.value.find((user) => user.id === form.leaderId)?.name ?? '')

function openCreate(): void {
  editing.value = null
  form.name = ''
  form.sort = Math.max(0, ...directions.value.map((item) => item.sort ?? 0)) + 1
  form.leaderId = ''
  formVisible.value = true
}
function openEdit(row: Direction): void {
  editing.value = row
  form.name = row.name
  form.sort = row.sort ?? 0
  form.leaderId = row.leaderId ?? ''
  formVisible.value = true
}
async function submit(): Promise<void> {
  if (!form.name.trim()) {
    showToast('请填写方向名称')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateDirection(editing.value.id, {
        name: form.name.trim(),
        sort: form.sort,
        leaderId: form.leaderId || null,
        version: editing.value.version,
      })
      showToast('方向已更新')
    } else {
      await createDirection(planId.value, {
        name: form.name.trim(),
        sort: form.sort,
        leaderId: form.leaderId || null,
      })
      showToast('方向已新增')
    }
    formVisible.value = false
    directions.value = await listDirections(planId.value)
  } finally {
    saving.value = false
  }
}

async function remove(row: Direction): Promise<void> {
  try {
    await showConfirmDialog({
      title: '删除方向',
      message: `确认删除「${row.name}」？已关联指标时服务端将拒绝删除。`,
      confirmButtonText: '确认删除',
    })
  } catch {
    return
  }
  await deleteDirection(row.id)
  showToast('方向已删除')
  await loadAll()
}

function assignmentReadonly(row: IndicatorTreeNode): boolean {
  return plan.value?.status === 'ARCHIVED'
    || row.status === 'DISTRIBUTED'
    || row.children.some((child) => child.status === 'DISTRIBUTED')
}
function assignmentDirty(row: IndicatorTreeNode): boolean {
  return (assignmentDrafts[row.id] ?? null) !== (row.directionId ?? null)
}
async function saveAssignment(row: IndicatorTreeNode): Promise<void> {
  if (assignmentReadonly(row) || !assignmentDirty(row)) return
  assignmentSavingId.value = row.id
  try {
    await updateIndicatorDirection(row.id, assignmentDrafts[row.id] ?? null, row.version)
    showToast(`已保存「${row.code}」的方向归属`)
    await loadIndicators()
  } finally {
    assignmentSavingId.value = ''
  }
}

const assignmentPickerVisible = ref(false)
const assignmentTargetId = ref<string | null>(null)
const assignmentTargetLabel = computed(() => {
  const row = aRows.value.find((item) => item.id === assignmentTargetId.value)
  return row ? `${row.code} ${row.name}` : ''
})
function openAssignment(row: IndicatorTreeNode): void {
  if (assignmentReadonly(row)) return
  assignmentTargetId.value = row.id
  assignmentPickerVisible.value = true
}
function chooseDirection(id: string | null): void {
  if (assignmentTargetId.value) assignmentDrafts[assignmentTargetId.value] = id
  assignmentPickerVisible.value = false
}

onMounted(async () => {
  await loadLeaders()
  try {
    plans.value = await listPlans()
    planId.value = (plans.value.find((item) => item.status === 'ACTIVE') ?? plans.value[0])?.id ?? ''
  } catch {
    // 忽略
  }
})

watch(planId, (id, previous) => {
  if (id && id !== previous) void loadAll()
}, { immediate: true })
</script>

<template>
  <div>
    <div class="panel-head">
      <h2>建设方向</h2>
      <van-button size="small" type="primary" @click="openCreate">新增方向</van-button>
    </div>
    <PlanSwitcher v-if="plans.length > 0" v-model="planId" :plans="plans" />

    <PageState :loading="loading" :empty="directions.length === 0" empty-text="暂无建设方向">
      <div class="direction-list">
        <article v-for="row in directions" :key="row.id" class="direction-card">
          <div class="row--between">
            <div class="row">
              <span class="code">{{ row.code }}</span>
              <strong>{{ row.name }}</strong>
            </div>
            <span class="small muted">排序 {{ row.sort ?? '—' }}</span>
          </div>
          <div class="small muted">负责人：{{ row.leader?.name ?? '—' }}</div>
          <div class="row actions">
            <van-button size="mini" plain type="primary" @click="openEdit(row)">编辑</van-button>
            <van-button size="mini" plain type="danger" @click="remove(row)">删除</van-button>
          </div>
        </article>
      </div>
    </PageState>

    <div class="section-title">指标方向归属</div>
    <div v-if="aRows.length === 0" class="empty-state">暂无指标</div>
    <div v-else class="assign-list">
      <div v-for="row in aRows" :key="row.id" class="assign-row">
        <div class="grow">
          <div class="small"><span class="code">{{ row.code }}</span> {{ row.name }}</div>
          <button type="button" class="assign-btn" :disabled="assignmentReadonly(row)" @click="openAssignment(row)">
            {{ directions.find((item) => item.id === assignmentDrafts[row.id])?.name ?? '未分配建设方向' }}
          </button>
        </div>
        <van-button
          v-if="assignmentDirty(row)"
          size="mini"
          type="primary"
          :loading="assignmentSavingId === row.id"
          @click="saveAssignment(row)"
        >保存</van-button>
      </div>
    </div>

    <van-popup v-model:show="formVisible" position="bottom" round safe-area-inset-bottom>
      <div class="sheet">
        <div class="sheet-head">
          <strong>{{ editing ? '编辑方向' : '新增方向' }}</strong>
          <button type="button" class="close-btn" @click="formVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="field">
          <label>名称<span class="required">*</span></label>
          <van-field v-model="form.name" placeholder="例如：落实立德树人根本任务" />
        </div>
        <div class="field">
          <label>排序</label>
          <van-field v-model.number="form.sort" type="number" inputmode="numeric" />
        </div>
        <div class="field">
          <label>方向负责人</label>
          <van-field readonly is-link :model-value="leaderLabel" placeholder="选择校级负责人" @click="leaderPickerVisible = true" />
        </div>
        <van-button block type="primary" :loading="saving" @click="submit">保存</van-button>
      </div>
    </van-popup>

    <van-popup v-model:show="leaderPickerVisible" position="bottom" round :style="{ height: '55vh' }">
      <div class="sheet picker">
        <div class="sheet-head"><strong>选择方向负责人</strong></div>
        <div class="picker-list">
          <button type="button" class="picker-item" @click="form.leaderId = ''; leaderPickerVisible = false">不指定</button>
          <button
            v-for="user in leaders"
            :key="user.id"
            type="button"
            class="picker-item"
            @click="form.leaderId = user.id; leaderPickerVisible = false"
          >{{ user.name }}（{{ user.username }}）</button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="assignmentPickerVisible" position="bottom" round :style="{ height: '60vh' }">
      <div class="sheet picker">
        <div class="sheet-head"><strong>设置方向 · {{ assignmentTargetLabel }}</strong></div>
        <div class="picker-list">
          <button type="button" class="picker-item" @click="chooseDirection(null)">未分配建设方向</button>
          <button
            v-for="direction in directions"
            :key="direction.id"
            type="button"
            class="picker-item"
            @click="chooseDirection(direction.id)"
          >{{ direction.code }} {{ direction.name }}</button>
        </div>
      </div>
    </van-popup>
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
.direction-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}
.direction-card {
  padding: 10px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.code {
  color: var(--brand);
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
}
.actions {
  justify-content: flex-end;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid var(--line);
}
.assign-list {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  overflow: hidden;
}
.assign-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--line);
}
.assign-row:last-child {
  border-bottom: none;
}
.assign-btn {
  min-height: 32px;
  margin-top: 4px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #fbfcfe;
  color: var(--brand);
  font-size: 11px;
}
.assign-btn:disabled {
  color: var(--text-secondary);
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
.field label {
  display: block;
  margin-bottom: 4px;
  color: var(--text-secondary);
  font-size: 13px;
}
.required {
  color: var(--danger);
}
.field :deep(.van-field) {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  padding: 10px 12px;
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
