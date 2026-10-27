<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import StatusTag from '@/components/StatusTag.vue'
import IndicatorEditDrawer from './components/IndicatorEditDrawer.vue'
import ImportDialog from './components/ImportDialog.vue'
import RollupBadge from './components/RollupBadge.vue'
import { getPlan, listPlans } from '@/api/plan'
import { deleteIndicator, finalizeIndicators, listIndicators } from '@/api/indicator'
import { createExport, pollExport } from '@/api/export'
import { getUserOptions } from '@/api/user'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import { newIdempotencyKey } from '@/utils/idempotency'
import { booleanPlanYear, formatTargetValue, hasQuantitativeFields, yearTargetMap } from '@/utils/indicator'
import { formatDateTime } from '@/utils/format'
import type { PlanDetail } from '@/types/plan'
import type { Indicator, IndicatorTreeNode } from '@/types/indicator'
import type { IndicatorLevel, IndicatorNature, SubjectType } from '@/types/enums'
import type { UserOption } from '@/types/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const planId = computed(() => (route.params.planId as string) || (route.query.planId as string) || '')

const loading = ref(false)
const plan = ref<PlanDetail | null>(null)
const tree = ref<IndicatorTreeNode[]>([])
const userOptions = ref<UserOption[]>([])

/** 校级负责人默认勾选「只看我负责的」（P04） */
const defaultOnlyMine = (): boolean => userStore.roles.includes('SCHOOL_OWNER')

const filters = reactive({
  level: '' as IndicatorLevel | '',
  subjectType: '' as SubjectType | '',
  nature: '' as IndicatorNature | '',
  ownerId: '',
  onlyMine: defaultOnlyMine(),
  keyword: '',
})

const canEdit = computed(() => userStore.can('indicator:edit'))
const canAllocate = computed(() => userStore.can('allocation:edit'))
const canFinalize = computed(() => userStore.can('plan:manage'))
const finalized = computed(() => plan.value?.indicatorsFinalized ?? false)
const isArchived = computed(() => plan.value?.status === 'ARCHIVED')
const readOnly = computed(() => finalized.value || isArchived.value)

const years = computed<number[]>(() => {
  if (!plan.value) return []
  const list: number[] = []
  for (let year = plan.value.startYear; year <= plan.value.endYear; year += 1) {
    list.push(year)
  }
  return list
})

const currentYear = new Date().getFullYear()

/** 仅可量化 A 可拆出 A+ */
const parentOptions = computed(() =>
  tree.value
    .filter((node) => node.level === 'A' && node.isQuantifiable)
    .map((node) => ({ id: node.id, code: node.code, name: node.name })),
)

async function loadAll(): Promise<void> {
  if (!planId.value) return
  loading.value = true
  try {
    const [detail] = await Promise.all([getPlan(planId.value), loadTree()])
    plan.value = detail
  } finally {
    loading.value = false
  }
}

async function loadTree(): Promise<void> {
  if (!planId.value) return
  const ownerId = filters.onlyMine ? (userStore.userInfo?.id ?? '') : filters.ownerId
  tree.value = await listIndicators(planId.value, {
    view: 'tree',
    level: filters.level || undefined,
    ownerId: ownerId || undefined,
    keyword: filters.keyword || undefined,
  })
  await syncExpandedNodes()
}

function applyFilters(): void {
  loading.value = true
  loadTree().finally(() => {
    loading.value = false
  })
}

function resetFilters(): void {
  filters.level = ''
  filters.subjectType = ''
  filters.nature = ''
  filters.ownerId = ''
  filters.onlyMine = defaultOnlyMine()
  filters.keyword = ''
  applyFilters()
}

/** 前端按主体 / 性质过滤（接口不支持 subjectType、nature 参数，本地过滤树） */
const visibleTree = computed<IndicatorTreeNode[]>(() => {
  let nodes = tree.value
  if (filters.subjectType) {
    nodes = nodes.filter((node) => node.subjectType === filters.subjectType)
  }
  if (filters.nature) {
    nodes = nodes
      .map((node) => ({
        ...node,
        children: node.children.filter((child) => child.nature === filters.nature),
      }))
      .filter((node) => node.nature === filters.nature || node.children.length > 0)
  }
  return nodes
})

type LazyIndicatorNode = Omit<IndicatorTreeNode, 'children'> & {
  children: IndicatorTreeNode[]
  hasChildren: boolean
}

type IndicatorTable = {
  toggleRowExpansion: (row: LazyIndicatorNode, expanded?: boolean) => void
  updateKeyChildren: (id: string, children: IndicatorTreeNode[]) => void
}

const tableRef = ref<IndicatorTable | null>(null)
const expandAll = ref(false)
const expandedIds = new Set<string>()

const childrenById = computed<Record<string, IndicatorTreeNode[]>>(() =>
  Object.fromEntries(visibleTree.value.map((node) => [node.id, node.children])),
)

const lazyTree = computed<LazyIndicatorNode[]>(() =>
  visibleTree.value.map(({ children, ...node }) => ({
    ...node,
    children: [],
    hasChildren: children.length > 0,
  })),
)

function loadChildren(
  row: LazyIndicatorNode,
  _node: unknown,
  resolve: (children: IndicatorTreeNode[]) => void,
): void {
  resolve(childrenById.value[row.id] ?? [])
}

function handleExpandChange(row: LazyIndicatorNode, expanded: boolean): void {
  if (expanded) expandedIds.add(row.id)
  else expandedIds.delete(row.id)
  expandAll.value = lazyTree.value.length > 0 && lazyTree.value.every((node) => !node.hasChildren || expandedIds.has(node.id))
}

async function toggleExpandAll(): Promise<void> {
  const next = !expandAll.value
  for (const row of lazyTree.value) {
    if (!row.hasChildren) continue
    tableRef.value?.toggleRowExpansion(row, next)
    if (next) expandedIds.add(row.id)
    else expandedIds.delete(row.id)
  }
  expandAll.value = next
  await nextTick()
}

async function syncExpandedNodes(): Promise<void> {
  await nextTick()
  for (const id of expandedIds) {
    const children = childrenById.value[id]
    if (children) tableRef.value?.updateKeyChildren(id, children)
    else expandedIds.delete(id)
  }
  expandAll.value = lazyTree.value.length > 0 && lazyTree.value.every((node) => !node.hasChildren || expandedIds.has(node.id))
}

/** 底部统计：A 类按性质计数 */
const aRows = computed(() => tree.value.filter((node) => node.level === 'A'))
const aPlusCount = computed(() => tree.value.reduce((sum, node) => sum + node.children.length, 0))
const aConstraintCount = computed(
  () => aRows.value.filter((node) => node.nature === 'CONSTRAINT').length,
)
const aDevelopmentCount = computed(
  () => aRows.value.filter((node) => node.nature === 'DEVELOPMENT').length,
)

const targetMaps = computed(() => {
  const maps = new Map<string, Map<number, number | boolean | null>>()
  const walk = (nodes: IndicatorTreeNode[]): void => {
    for (const node of nodes) {
      maps.set(node.id, yearTargetMap(node.yearTargets))
      if (node.children.length) walk(node.children)
    }
  }
  walk(tree.value)
  return maps
})

function targetOf(row: Indicator, year: number): number | boolean | null {
  if (!hasQuantitativeFields(row.level, row.isQuantifiable)) return null
  return targetMaps.value.get(row.id)?.get(year) ?? null
}

function totalTargetDisplay(row: Indicator): string {
  if (!hasQuantitativeFields(row.level, row.isQuantifiable)) return '—'
  if (row.statCaliber === 'BOOLEAN') {
    const planYear = booleanPlanYear(row.yearTargets)
    return planYear === null ? '—' : `是（${planYear} 年）`
  }
  return formatTargetValue(row.totalTarget, row.statCaliber)
}

// ---------- 编辑抽屉 ----------
const drawerVisible = ref(false)
const editingIndicator = ref<Indicator | null>(null)
const presetParentId = ref<string | null>(null)

function openCreateA(): void {
  editingIndicator.value = null
  presetParentId.value = null
  drawerVisible.value = true
}

function openCreateAPlus(parent: Indicator): void {
  if (parent.level !== 'A' || !parent.isQuantifiable) return
  editingIndicator.value = null
  presetParentId.value = parent.id
  drawerVisible.value = true
}

function openEdit(row: Indicator): void {
  editingIndicator.value = row
  presetParentId.value = null
  drawerVisible.value = true
}

async function onSaved(): Promise<void> {
  const createdParentId = presetParentId.value
  await loadTree()
  if (createdParentId) {
    const parent = lazyTree.value.find((row) => row.id === createdParentId)
    if (parent) {
      expandedIds.add(createdParentId)
      tableRef.value?.toggleRowExpansion(parent, true)
      await nextTick()
    }
  }
}

// ---------- 行操作 ----------
function goDetail(row: Indicator): void {
  router.push(`/indicators/${row.id}`)
}

/** 只有 A+ 可进入分解矩阵 */
function allocatableRow(row: Indicator): boolean {
  return row.level === 'A_PLUS'
}

function goAllocation(row: Indicator): void {
  router.push({ path: `/plans/${planId.value}/allocation`, query: { indicatorId: row.id } })
}

function goYearTargets(row: Indicator): void {
  if (!hasQuantitativeFields(row.level, row.isQuantifiable)) return
  router.push({ path: `/indicators/${row.id}`, query: { tab: 'targets' } })
}

async function handleDelete(row: Indicator): Promise<void> {
  await ElMessageBox.confirm(
    `确认删除指标「${row.code} ${row.name}」？已下发的指标不能删除（40004）。`,
    '删除指标',
    { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
  )
  try {
    await deleteIndicator(row.id)
    ElMessage.success('指标已删除')
    await loadTree()
  } catch {
    // 拦截器已提示
  }
}

// ---------- 导入 ----------
const importVisible = ref(false)

// ---------- 导出 ----------
const exporting = ref(false)

async function handleExport(): Promise<void> {
  exporting.value = true
  const notification = ElNotification({
    title: '导出 Excel',
    message: '导出任务已创建，排队中 → 生成中…',
    type: 'info',
    duration: 0,
  })
  try {
    const { exportId } = await createExport(
      { type: 'INDICATORS', params: { planId: planId.value } },
      newIdempotencyKey(),
    )
    const job = await pollExport(exportId)
    notification.close()
    if (job.status === 'SUCCEEDED' && job.downloadUrl) {
      window.open(job.downloadUrl, '_blank')
      ElNotification({ title: '导出 Excel', message: '导出完成，已开始下载', type: 'success' })
    } else {
      ElNotification({
        title: '导出失败',
        message: job.errorMessage ?? '导出任务失败，请稍后重试',
        type: 'error',
      })
    }
  } catch (error) {
    notification.close()
    ElNotification({
      title: '导出失败',
      message: error instanceof Error ? error.message : '导出任务失败，请稍后重试',
      type: 'error',
    })
  } finally {
    exporting.value = false
  }
}

// ---------- 定稿 ----------
const finalizing = ref(false)

/** 定稿校验清单（无独立预检接口，confirm 中列出将校验项目，失败时逐项展示 errors） */
const FINALIZE_CHECKLIST = [
  'A → A+ 拆分守恒：A+ 目标之和 = A 目标（40003）',
  '增量型各年度目标之和 = 五年总量（40002）',
  '必填字段完整（名称、口径、达成方向等）',
  '负责人已指定',
]

async function handleFinalize(): Promise<void> {
  await ElMessageBox.confirm(
    `<p>定稿将逐项校验：</p><ul style="margin:4px 0 8px;padding-left:20px;">${FINALIZE_CHECKLIST.map(
      (item) => `<li>${item}</li>`,
    ).join('')}</ul><p>定稿后修改目标需发起「目标调整」。确认定稿？</p>`,
    '指标体系定稿',
    {
      confirmButtonText: '确认定稿',
      cancelButtonText: '取消',
      dangerouslyUseHTMLString: true,
    },
  )
  finalizing.value = true
  try {
    if (!plan.value) return
    await finalizeIndicators(planId.value, plan.value.version, newIdempotencyKey())
    ElMessage.success('指标体系已定稿')
    await loadAll()
  } catch (error) {
    if (error instanceof ApiError && (error.code === 40003 || error.code === 40002)) {
      const details = error.fieldErrors.map((item) => `${item.field}：${item.message}`)
      ElMessageBox.alert(
        details.length > 0 ? details.join('\n') : error.message,
        `定稿校验未通过（${error.code}）`,
        { confirmButtonText: '我知道了', type: 'error' },
      )
    }
  } finally {
    finalizing.value = false
  }
}

/** 无计划上下文（菜单直链 /indicators）时自动进入当前执行中的计划 */
async function redirectToActivePlan(): Promise<void> {
  try {
    const plans = await listPlans()
    const active = plans.find((p) => p.status === 'ACTIVE') ?? plans[0]
    if (active) await router.replace(`/plans/${active.id}/indicators`)
  } catch {
    // 拉取失败保持空状态引导
  }
}

filters.onlyMine = defaultOnlyMine()

// planId 驱动加载：组件可能被复用（直链跳转后 onMounted 不再触发），用 watch 保证有上下文即加载
watch(planId, (id) => {
  if (id) loadAll()
}, { immediate: true })

onMounted(() => {
  if (!planId.value) {
    redirectToActivePlan()
  }
  getUserOptions()
    .then((list) => {
      userOptions.value = list
    })
    .catch(() => undefined)
})
</script>

<template>
  <el-empty v-if="!planId" description="请先从首页选择一个计划">
    <el-button type="primary" @click="router.push('/plans')">返回计划列表</el-button>
  </el-empty>

  <div v-else v-loading="loading">
    <el-alert
      v-if="isArchived"
      type="info"
      :closable="false"
      title="该计划已归档，只能查看"
      class="page-alert"
    />
    <el-alert
      v-else-if="finalized"
      type="info"
      :closable="false"
      class="page-alert"
      :title="`指标体系已于 ${plan?.finalizedAt ? formatDateTime(plan.finalizedAt) : '—'} 定稿，修改目标需发起「目标调整」（40005）`"
    />

    <div class="page-header">
      <div>
        <h1 class="page-title">指标总表</h1>
        <p class="page-subtitle">{{ plan?.name ?? '' }}</p>
      </div>
      <div class="header-actions">
        <template v-if="canEdit && !readOnly">
          <el-button type="primary" @click="openCreateA">新增 A 类指标</el-button>
          <el-button @click="importVisible = true">导入 Excel</el-button>
        </template>
        <el-button :loading="exporting" @click="handleExport">导出 Excel</el-button>
        <el-tooltip
          v-if="canFinalize"
          :disabled="!readOnly"
          content="指标体系已定稿或计划已归档"
          placement="top"
        >
          <span>
            <el-button
              type="warning"
              :disabled="readOnly"
              :loading="finalizing"
              @click="handleFinalize"
            >
              定稿
            </el-button>
          </span>
        </el-tooltip>
      </div>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="主体">
          <el-select v-model="filters.subjectType" clearable placeholder="全部" style="width: 120px">
            <el-option label="校级" value="SCHOOL" />
            <el-option label="专业群" value="MAJOR_GROUP" />
          </el-select>
        </el-form-item>
        <el-form-item label="层级">
          <el-select v-model="filters.level" clearable placeholder="全部" style="width: 120px">
            <el-option label="A 类" value="A" />
            <el-option label="A+ 类" value="A_PLUS" />
          </el-select>
        </el-form-item>
        <el-form-item label="指标性质">
          <el-select v-model="filters.nature" clearable placeholder="全部" style="width: 120px">
            <el-option label="约束性" value="CONSTRAINT" />
            <el-option label="发展性" value="DEVELOPMENT" />
          </el-select>
        </el-form-item>
        <el-form-item label="负责人">
          <el-select
            v-model="filters.ownerId"
            clearable
            filterable
            placeholder="全部"
            style="width: 180px"
            :disabled="filters.onlyMine"
          >
            <el-option
              v-for="user in userOptions"
              :key="user.id"
              :label="`${user.name}（${user.username}）`"
              :value="user.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input
            v-model="filters.keyword"
            clearable
            placeholder="编号 / 名称"
            style="width: 200px"
            @keyup.enter="applyFilters"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="filters.onlyMine" @change="applyFilters">只看我负责的</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="applyFilters">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <div class="table-toolbar">
        <el-button link type="primary" @click="toggleExpandAll">
          {{ expandAll ? '全部收起' : '全部展开' }}
        </el-button>
      </div>
      <el-table
        ref="tableRef"
        :data="lazyTree"
        row-key="id"
        border
        size="small"
        max-height="calc(100vh - 330px)"
        lazy
        :load="loadChildren"
        :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
        @expand-change="handleExpandChange"
      >
        <el-table-column prop="code" label="编号" width="96" fixed="left" class-name="mono" />
        <el-table-column label="层级" width="140" align="center">
          <template #default="{ row }">
            <StatusTag kind="IndicatorLevel" :value="row.level" />
          </template>
        </el-table-column>
        <el-table-column prop="name" label="关键产出指标" min-width="200" fixed="left" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'row-a-name': row.level === 'A' }">{{ row.name }}</span>
            <div v-if="row.level === 'A' && !row.isQuantifiable" class="undecomposable-tip">
              不可量化 · 仅维护基础信息
            </div>
          </template>
        </el-table-column>
        <el-table-column label="性质" width="88" align="center">
          <template #default="{ row }">
            <StatusTag kind="IndicatorNature" :value="row.nature" />
          </template>
        </el-table-column>
        <el-table-column label="口径" width="120" align="center">
          <template #default="{ row }">
            <StatusTag v-if="row.statCaliber" kind="StatCaliber" :value="row.statCaliber" />
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column
          label="单位"
          min-width="72"
          align="center"
          class-name="complete-text-cell"
        >
          <template #default="{ row }">{{ row.unitLabel ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="五年目标" width="104" align="right">
          <template #default="{ row }">
            {{ totalTargetDisplay(row) }}
            <RollupBadge v-if="row.level === 'A' && row.isQuantifiable && row.rollupMode" :mode="row.rollupMode" />
          </template>
        </el-table-column>
        <el-table-column label="年度目标" align="center">
          <el-table-column
            v-for="year in years"
            :key="year"
            :label="String(year)"
            width="70"
            align="right"
          >
            <template #header>
              <span :class="{ 'current-year': year === currentYear }">{{ year }}</span>
            </template>
            <template #default="{ row }">
              {{ formatTargetValue(targetOf(row, year), row.statCaliber) }}
              <RollupBadge v-if="row.level === 'A' && row.isQuantifiable && row.rollupMode" :mode="row.rollupMode" />
            </template>
          </el-table-column>
        </el-table-column>
        <el-table-column label="负责人" width="84" align="center">
          <template #default="{ row }">{{ row.owner?.name ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="84" align="center">
          <template #default="{ row }">
            <StatusTag kind="IndicatorStatus" :value="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="148" fixed="right" align="center">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" @click="goDetail(row)">详情</el-button>
              <el-button
                v-if="canAllocate && allocatableRow(row)"
                link
                type="primary"
                @click="goAllocation(row)"
              >
                分解
              </el-button>
              <el-dropdown trigger="click">
                <el-button link type="primary">
                  更多<el-icon :size="12"><ArrowDown /></el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-if="canEdit && !readOnly" @click="openEdit(row)">
                      编辑
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="hasQuantitativeFields(row.level, row.isQuantifiable)"
                      @click="goYearTargets(row)"
                    >
                      年度目标
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="canEdit && !readOnly && row.level === 'A' && row.isQuantifiable"
                      @click="openCreateAPlus(row)"
                    >
                      拆出 A+
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="canEdit && !readOnly"
                      class="danger-item"
                      @click="handleDelete(row)"
                    >
                      <span class="danger-text">删除</span>
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :image-size="80" description="还没有指标，可以手动新增或从 Excel 导入">
            <template v-if="canEdit && !readOnly">
              <el-button type="primary" @click="openCreateA">新增 A 类指标</el-button>
              <el-button @click="importVisible = true">导入 Excel</el-button>
            </template>
          </el-empty>
        </template>
      </el-table>
      <div class="table-footer">
        共 A 类 {{ aRows.length }} 条、A+ 类 {{ aPlusCount }} 条；约束性 {{ aConstraintCount }} /
        发展性 {{ aDevelopmentCount }}
      </div>
    </el-card>

    <IndicatorEditDrawer
      v-model="drawerVisible"
      :plan-id="planId"
      :indicator="editingIndicator"
      :preset-parent-id="presetParentId"
      :parents="parentOptions"
      :years="years"
      @saved="onSaved"
    />

    <ImportDialog v-model="importVisible" :plan-id="planId" @imported="loadTree" />
  </div>
</template>

<style scoped>
.page-alert {
  margin-bottom: var(--space-4);
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
}

.page-subtitle {
  margin: var(--space-1) 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.header-actions {
  display: flex;
  gap: var(--space-2);
}

.filter-card {
  margin-bottom: var(--space-4);
  border-radius: var(--radius-card);
}

.table-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: var(--space-2);
}

.mono {
  font-family: var(--app-font-mono);
}

.row-a-name {
  font-weight: 600;
}

.undecomposable-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.current-year {
  color: var(--el-color-primary);
  border-bottom: 2px solid var(--el-color-primary);
}

:deep(.complete-text-cell .cell) {
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  white-space: nowrap;
}

.row-actions :deep(.el-button) {
  height: 24px;
  margin: 0;
  padding: 0;
}

.danger-text {
  color: var(--el-color-danger);
}

.table-footer {
  margin-top: var(--space-3);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
