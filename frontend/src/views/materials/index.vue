<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElNotification } from 'element-plus'
import { ArrowLeft, ArrowRight, FolderOpened, Search } from '@element-plus/icons-vue'
import { bindMaterials, listMaterialIndicators, listMaterials } from '@/api/material'
import type { MaterialIndicatorOption, MaterialItem } from '@/api/material'
import { listAllTasks } from '@/api/task'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { listPlans } from '@/api/plan'
import { getCurrentPlan, setCurrentPlan } from '@/utils/currentPlan'
import { downloadFile } from '@/api/file'
import { createExport, pollExport } from '@/api/export'
import { newIdempotencyKey } from '@/utils/idempotency'
import MaterialUploader from '@/views/submission/components/MaterialUploader.vue'
import FilePreviewDrawer from '@/components/FilePreviewDrawer.vue'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'
import { useAutoRefresh } from '@/utils/autoRefresh'
import { FILE_CATEGORY_COLORS, formatFileSize, triggerFileDownload } from '@/utils/file'
import type { TaskListItem } from '@/types/task'
import type { PlanCard } from '@/types/plan'

const CATEGORY_LABELS: Record<string, string> = {
  DOCUMENT: '文档',
  PDF: 'PDF',
  IMAGE: '图片',
  VIDEO: '视频',
  ARCHIVE: '压缩包',
}

const userStore = useUserStore()
const route = useRoute()
const router = useRouter()
const loading = ref(false)
const binding = ref(false)
const indicators = ref<MaterialIndicatorOption[]>([])
const units = ref<OrgUnit[]>([])
const rows = ref<MaterialItem[]>([])
const total = ref(0)
const materialTable = ref()
const selectedMaterials = ref<MaterialItem[]>([])
const exportingZip = ref(false)
const filters = reactive({ unitId: '', category: '', keyword: '' })
const page = ref(1)
const pageSize = ref(10)
const navKeyword = ref('')

const planId = ref('')
const plans = ref<PlanCard[]>([])
const navLoading = ref(false)
const selectedIndicatorId = ref('')
const selectedTaskId = ref('')
const selectedTaskCode = ref('')
const selectedTaskYear = ref<number | null>(null)
const selectedTaskUnitId = ref('')
const expandedIndicatorIds = ref<Set<string>>(new Set())
const tasksByIndicator = reactive<Record<string, TaskListItem[]>>({})
const tasksLoading = reactive<Record<string, boolean>>({})
let listRequestSequence = 0

const fromIndicators = computed(() => route.query.from === 'indicators')
const selectedPlanName = computed(() =>
  plans.value.find((item) => item.id === planId.value)?.name ?? '')
const canDirectUpload = computed(() =>
  userStore.roles.includes('ADMIN') || userStore.roles.includes('SCHOOL_OWNER'))
const selectedMaterialIds = computed(() => new Set(selectedMaterials.value.map((item) => item.id)))
const hasSelection = computed(() => Boolean(selectedIndicatorId.value))
const selectedIndicator = computed(() =>
  indicators.value.find((item) => item.id === selectedIndicatorId.value))
const selectedTask = computed(() =>
  (tasksByIndicator[selectedIndicatorId.value] ?? []).find((item) => item.id === selectedTaskId.value))
const selectionTitle = computed(() => {
  if (selectedTaskId.value) {
    const code = selectedTask.value?.code || selectedTaskCode.value || '学院任务'
    return selectedTaskYear.value === null ? code : `${code} · ${selectedTaskYear.value} 年`
  }
  if (selectedIndicator.value) return `${selectedIndicator.value.code} ${selectedIndicator.value.name}`
  return ''
})
const selectionSubtitle = computed(() => {
  if (selectedTaskId.value) {
    return selectedTaskYear.value === null ? '该任务全部年度审核通过的材料' : '该任务该年度审核通过的材料'
  }
  return '该指标下所有任务审核通过的材料'
})
const showUnitFilter = computed(() => !userStore.isUnitOwnerOnly && !selectedTaskId.value)
const emptyDescription = computed(() =>
  hasSelection.value ? '暂无可查看的审核通过材料' : '请从左侧选择指标或学院任务')

const indicatorGroups = computed(() => {
  const groups = new Map<string, MaterialIndicatorOption[]>()
  for (const item of indicators.value) {
    const key = item.directionName?.trim()
      ? item.directionName
      : '未归入建设方向'
    const list = groups.get(key)
    if (list) list.push(item)
    else groups.set(key, [item])
  }
  return [...groups.entries()].map(([name, items]) => ({ name, items }))
})
const filteredIndicatorGroups = computed(() => {
  const keyword = navKeyword.value.trim().toLowerCase()
  if (!keyword) return indicatorGroups.value
  return indicatorGroups.value
    .map((group) => ({
      ...group,
      items: group.items.filter((item) =>
        `${item.code} ${item.name}`.toLowerCase().includes(keyword)),
    }))
    .filter((group) => group.items.length > 0)
})

const uploadVisible = ref(false)
const uploadIndicatorId = ref('')
const uploadFileIds = ref<string[]>([])
const uploadKey = ref(0)

const previewVisible = ref(false)
const previewFile = ref<MaterialItem | null>(null)

function queryString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function queryNumber(value: unknown): number | null {
  const parsed = Number(queryString(value))
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null
}

function currentUnitId(): string {
  return selectedTaskId.value ? selectedTaskUnitId.value : filters.unitId
}

async function loadIndicators(silent = false): Promise<void> {
  if (!silent) navLoading.value = true
  try {
    indicators.value = await listMaterialIndicators(planId.value || undefined)
  } finally {
    if (!silent) navLoading.value = false
  }
}

function defaultPlanId(): string {
  const current = getCurrentPlan()
  return plans.value.find((item) => item.id === current?.id)?.id
    ?? plans.value.find((item) => item.status === 'ACTIVE')?.id
    ?? plans.value[0]?.id
    ?? ''
}

/** 切换计划后清空旧计划的指标、任务缓存与选择，避免跨计划串数据。 */
function resetPlanContext(): void {
  listRequestSequence += 1
  loading.value = false
  selectedIndicatorId.value = ''
  selectedTaskId.value = ''
  selectedTaskCode.value = ''
  selectedTaskYear.value = null
  selectedTaskUnitId.value = ''
  filters.unitId = ''
  filters.category = ''
  filters.keyword = ''
  navKeyword.value = ''
  rows.value = []
  selectedMaterials.value = []
  materialTable.value?.clearSelection()
  total.value = 0
  page.value = 1
  expandedIndicatorIds.value = new Set()
  Object.keys(tasksByIndicator).forEach((key) => delete tasksByIndicator[key])
  Object.keys(tasksLoading).forEach((key) => delete tasksLoading[key])
}

async function handlePlanChange(): Promise<void> {
  const selected = plans.value.find((item) => item.id === planId.value)
  if (selected) setCurrentPlan(selected)
  resetPlanContext()
  await router.replace({ path: route.path, query: planId.value ? { planId: planId.value } : {} })
  await loadIndicators()
}

function handleMaterialSelectionChange(selection: MaterialItem[]): void {
  const currentPageIds = new Set(rows.value.map((item) => item.id))
  const merged = new Map(
    selectedMaterials.value.filter((item) => !currentPageIds.has(item.id)).map((item) => [item.id, item] as const),
  )
  selection.forEach((item) => merged.set(item.id, item))
  selectedMaterials.value = [...merged.values()]
}

function syncMaterialTableSelection(): void {
  void nextTick(() => {
    const selectedIds = new Set(selectedMaterials.value.map((item) => item.id))
    rows.value.forEach((row) => materialTable.value?.toggleRowSelection(row, selectedIds.has(row.id)))
  })
}

function toggleMaterial(row: MaterialItem): void {
  if (selectedMaterialIds.value.has(row.id)) {
    selectedMaterials.value = selectedMaterials.value.filter((item) => item.id !== row.id)
  } else {
    selectedMaterials.value = [...selectedMaterials.value, row]
  }
  syncMaterialTableSelection()
}

function clearFileSelection(): void {
  selectedMaterials.value = []
  materialTable.value?.clearSelection()
}

async function loadList(silent = false): Promise<void> {
  const sequence = ++listRequestSequence
  if (!selectedIndicatorId.value) {
    rows.value = []
    total.value = 0
    loading.value = false
    return
  }
  if (!silent) loading.value = true
  try {
    const result = await listMaterials({
      planId: planId.value || undefined,
      indicatorId: selectedIndicatorId.value,
      unitId: currentUnitId() || undefined,
      taskId: selectedTaskId.value || undefined,
      year: selectedTaskYear.value ?? undefined,
      category: filters.category || undefined,
      keyword: filters.keyword.trim() || undefined,
      page: page.value,
      pageSize: pageSize.value,
    })
    if (sequence !== listRequestSequence) return
    rows.value = result.items
    total.value = result.total
    syncMaterialTableSelection()
  } finally {
    if (!silent && sequence === listRequestSequence) loading.value = false
  }
}

function syncQuery(): void {
  const query: Record<string, string> = {}
  if (planId.value) query.planId = planId.value
  if (selectedIndicatorId.value) query.indicatorId = selectedIndicatorId.value
  if (selectedTaskId.value) {
    query.taskId = selectedTaskId.value
    if (selectedTaskCode.value) query.taskCode = selectedTaskCode.value
  }
  if (currentUnitId()) query.unitId = currentUnitId()
  if (selectedTaskYear.value !== null) query.year = String(selectedTaskYear.value)
  if (fromIndicators.value) query.from = 'indicators'
  void router.replace({ query })
}

function search(): void {
  page.value = 1
  syncQuery()
  void loadList()
}

function resetFilters(): void {
  filters.unitId = ''
  filters.category = ''
  filters.keyword = ''
  search()
}

async function loadTasks(indicatorId: string): Promise<void> {
  if (tasksByIndicator[indicatorId] || tasksLoading[indicatorId]) return
  tasksLoading[indicatorId] = true
  try {
    const tasks = await listAllTasks({ indicatorId })
    tasksByIndicator[indicatorId] = [...tasks].sort((left, right) =>
      (left.unit?.code ?? '').localeCompare(right.unit?.code ?? '')
      || left.code.localeCompare(right.code))
  } catch {
    tasksByIndicator[indicatorId] = []
  } finally {
    tasksLoading[indicatorId] = false
  }
}

function toggleIndicatorTasks(indicator: MaterialIndicatorOption): void {
  const next = new Set(expandedIndicatorIds.value)
  if (next.has(indicator.id)) {
    next.delete(indicator.id)
  } else {
    next.add(indicator.id)
    void loadTasks(indicator.id)
  }
  expandedIndicatorIds.value = next
}

function selectIndicator(indicator: MaterialIndicatorOption): void {
  selectedIndicatorId.value = indicator.id
  selectedTaskId.value = ''
  selectedTaskCode.value = ''
  selectedTaskYear.value = null
  selectedTaskUnitId.value = ''
  filters.unitId = ''
  page.value = 1
  syncQuery()
  void loadList()
}

function selectTask(task: TaskListItem): void {
  selectedIndicatorId.value = task.topIndicator?.id ?? task.indicator?.id ?? selectedIndicatorId.value
  selectedTaskId.value = task.id
  selectedTaskCode.value = task.code
  selectedTaskYear.value = null
  selectedTaskUnitId.value = task.unit?.id ?? ''
  filters.unitId = ''
  page.value = 1
  syncQuery()
  void loadList()
}

function showAllYears(): void {
  selectedTaskYear.value = null
  search()
}

function clearSelection(): void {
  listRequestSequence += 1
  loading.value = false
  clearFileSelection()
  selectedIndicatorId.value = ''
  selectedTaskId.value = ''
  selectedTaskCode.value = ''
  selectedTaskYear.value = null
  selectedTaskUnitId.value = ''
  filters.unitId = ''
  rows.value = []
  total.value = 0
  page.value = 1
  syncQuery()
}

function goBack(): void {
  if (window.history.state?.back) {
    router.back()
    return
  }
  void router.push(planId.value ? `/plans/${planId.value}/indicators` : '/plans')
}

function openUpload(): void {
  uploadIndicatorId.value = selectedIndicatorId.value || ''
  uploadFileIds.value = []
  uploadKey.value += 1
  uploadVisible.value = true
}

async function confirmBind(): Promise<void> {
  if (!uploadIndicatorId.value) {
    ElMessage.warning('请选择要关联的关键产出指标')
    return
  }
  if (uploadFileIds.value.length === 0) {
    ElMessage.warning('请等待材料上传完成后再确认')
    return
  }
  binding.value = true
  try {
    await bindMaterials(uploadIndicatorId.value, uploadFileIds.value)
    ElMessage.success(`已上传并关联 ${uploadFileIds.value.length} 份材料`)
    uploadVisible.value = false
    if (!selectedIndicatorId.value) {
      selectedIndicatorId.value = uploadIndicatorId.value
    }
    await loadList()
  } finally {
    binding.value = false
  }
}

function openPreview(row: MaterialItem): void {
  previewFile.value = row
  previewVisible.value = true
}

async function download(row: MaterialItem): Promise<void> {
  await downloadFile(row.id, row.fileName, row.size)
}

async function exportSelectedZip(): Promise<void> {
  if (selectedMaterials.value.length === 0) {
    ElMessage.warning('请先勾选要导出的材料')
    return
  }
  exportingZip.value = true
  const notification = ElNotification({
    title: '导出 ZIP',
    message: `正在打包 ${selectedMaterials.value.length} 份材料，请稍候…`,
    type: 'info',
    duration: 0,
  })
  try {
    const { exportId } = await createExport(
      { type: 'MATERIAL_ZIP', params: { fileIds: selectedMaterials.value.map((item) => item.id) } },
      newIdempotencyKey(),
    )
    const job = await pollExport(exportId, undefined, { timeoutMs: 600000 })
    notification.close()
    if (job.status === 'SUCCEEDED' && job.downloadUrl) {
      triggerFileDownload(job.downloadUrl, '材料库.zip')
      ElNotification({ title: '导出成功', message: 'ZIP 已生成，已开始下载', type: 'success' })
    } else {
      ElNotification({ title: '导出失败', message: job.errorMessage ?? '材料打包失败', type: 'error' })
    }
  } catch (error) {
    notification.close()
    ElNotification({
      title: '导出失败',
      message: error instanceof Error ? error.message : '材料打包失败',
      type: 'error',
    })
  } finally {
    exportingZip.value = false
  }
}

onMounted(async () => {
  const routePlanId = queryString(route.query.planId)
  const requests: Promise<unknown>[] = [
    listPlans().then((data) => { plans.value = data }).catch(() => undefined),
  ]
  if (!userStore.isUnitOwnerOnly) {
    requests.push(listOrgUnits().then((data) => { units.value = data }))
  }
  await Promise.all(requests)

  planId.value = routePlanId || defaultPlanId()
  if (!planId.value) return
  if (!routePlanId) {
    await router.replace({ query: { ...route.query, planId: planId.value } })
  }
  await loadIndicators()

  const queryIndicatorId = queryString(route.query.indicatorId)
  if (!queryIndicatorId) return

  selectedIndicatorId.value = queryIndicatorId
  const queryTaskId = queryString(route.query.taskId)
  if (queryTaskId) {
    selectedTaskId.value = queryTaskId
    selectedTaskCode.value = queryString(route.query.taskCode)
    selectedTaskYear.value = queryNumber(route.query.year)
    selectedTaskUnitId.value = queryString(route.query.unitId)
    const next = new Set(expandedIndicatorIds.value)
    next.add(queryIndicatorId)
    expandedIndicatorIds.value = next
    await loadTasks(queryIndicatorId)
    const task = (tasksByIndicator[queryIndicatorId] ?? []).find((item) => item.id === queryTaskId)
    if (task) {
      selectedTaskCode.value = task.code || selectedTaskCode.value
      selectedTaskUnitId.value = task.unit?.id ?? selectedTaskUnitId.value
    }
  } else {
    filters.unitId = queryString(route.query.unitId)
  }
  await loadList()
})

useAutoRefresh(async () => {
  await loadIndicators(true)
  await loadList(true)
})
</script>

<template>
  <div class="materials-page">
    <div class="page-header">
      <div>
        <h1 class="page-title"><el-icon><FolderOpened /></el-icon> 材料库</h1>
        <p class="page-subtitle">
          <template v-if="selectedPlanName">{{ selectedPlanName }} · </template>按指标和学院任务查看审核通过的材料，再进入预览
        </p>
      </div>
      <div class="page-header-actions">
        <el-button v-if="canDirectUpload" type="primary" @click="openUpload">上传材料</el-button>
        <el-button v-if="fromIndicators" text class="back-button" @click="goBack">
          <el-icon><ArrowLeft /></el-icon> 返回指标总表
        </el-button>
      </div>
    </div>

    <div class="material-plan-bar">
      <span class="material-plan-bar__label">计划</span>
      <el-select
        v-model="planId"
        class="material-plan-select"
        filterable
        placeholder="选择计划"
        :disabled="plans.length === 0"
        @change="handlePlanChange"
      >
        <el-option v-for="plan in plans" :key="plan.id" :label="plan.name" :value="plan.id" />
      </el-select>
    </div>

    <div class="material-workspace">
      <aside v-loading="navLoading" class="material-nav" aria-label="指标与任务">
        <div class="material-nav__head">
          <strong>指标与任务</strong>
          <el-input
            v-model="navKeyword"
            size="small"
            clearable
            placeholder="搜索指标编码或名称"
            :prefix-icon="Search"
          />
        </div>
        <div class="material-nav__body">
          <div v-for="group in filteredIndicatorGroups" :key="group.name" class="material-nav__direction">
            <div class="material-nav__direction-name">{{ group.name }}</div>
            <div v-for="indicator in group.items" :key="indicator.id" class="material-nav__indicator">
              <div
                class="material-nav__indicator-row"
                :class="{
                  active: selectedIndicatorId === indicator.id && !selectedTaskId,
                  ancestor: selectedIndicatorId === indicator.id && Boolean(selectedTaskId),
                }"
              >
                <button
                  type="button"
                  class="material-nav__indicator-main"
                  :title="`${indicator.code} ${indicator.name}`"
                  @click="selectIndicator(indicator)"
                >
                  <span class="material-nav__code">{{ indicator.code }}</span>
                  <span class="material-nav__name">{{ indicator.name }}</span>
                </button>
                <button
                  type="button"
                  class="material-nav__toggle"
                  :aria-label="`${expandedIndicatorIds.has(indicator.id) ? '收起' : '展开'} ${indicator.code} 的学院任务`"
                  :aria-expanded="expandedIndicatorIds.has(indicator.id)"
                  @click="toggleIndicatorTasks(indicator)"
                >
                  <el-icon :class="{ expanded: expandedIndicatorIds.has(indicator.id) }"><ArrowRight /></el-icon>
                </button>
              </div>
              <div v-if="expandedIndicatorIds.has(indicator.id)" class="material-nav__tasks">
                <div v-if="tasksLoading[indicator.id]" class="material-nav__hint">任务加载中…</div>
                <template v-else>
                  <button
                    v-for="task in tasksByIndicator[indicator.id] ?? []"
                    :key="task.id"
                    type="button"
                    class="material-nav__task"
                    :class="{ active: selectedTaskId === task.id }"
                    @click="selectTask(task)"
                  >
                    <span class="material-nav__task-code">{{ task.code }}</span>
                    <span class="material-nav__task-unit">{{ task.unit?.name ?? '—' }}</span>
                  </button>
                  <div v-if="(tasksByIndicator[indicator.id] ?? []).length === 0" class="material-nav__hint">
                    暂无学院任务
                  </div>
                </template>
              </div>
            </div>
          </div>
          <el-empty v-if="filteredIndicatorGroups.length === 0" :image-size="60" description="没有匹配的指标" />
        </div>
      </aside>

      <section class="material-content">
        <div v-if="!hasSelection" class="material-guide">
          <el-empty :image-size="110" description="请从左侧选择指标或学院任务，查看审核通过的材料" />
        </div>
        <template v-else>
          <div class="material-content__head">
            <div class="material-content__title">
              <strong>{{ selectionTitle }}</strong>
              <span>{{ selectionSubtitle }} · 共 {{ total }} 份</span>
            </div>
            <div class="material-content__actions">
              <el-button v-if="selectedTaskId && selectedTaskYear !== null" @click="showAllYears">查看全部年度</el-button>
              <el-button @click="clearSelection">清空选择</el-button>
            </div>
          </div>

          <el-card shadow="never" class="filter-card">
            <el-form inline @submit.prevent>
              <el-form-item v-if="showUnitFilter" label="所属学院">
                <el-select v-model="filters.unitId" clearable filterable placeholder="全部学院" style="width: 190px" @change="search">
                  <el-option v-for="item in units" :key="item.id" :label="item.name" :value="item.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="材料类型">
                <el-select v-model="filters.category" clearable placeholder="全部类型" style="width: 130px" @change="search">
                  <el-option v-for="(label, value) in CATEGORY_LABELS" :key="value" :label="label" :value="value" />
                </el-select>
              </el-form-item>
              <el-form-item label="关键词">
                <el-input v-model="filters.keyword" clearable placeholder="文件名 / 指标编码或名称" style="width: 220px" @keyup.enter="search" @clear="search" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="search">查询</el-button>
                <el-button @click="resetFilters">重置</el-button>
              </el-form-item>
            </el-form>
          </el-card>

          <div v-if="selectedMaterials.length" class="material-selection-bar">
            <span>已选 <strong>{{ selectedMaterials.length }}</strong> 份材料<small>支持跨页选择，切换计划后自动清空</small></span>
            <div class="material-selection-bar__actions">
              <el-button type="primary" :loading="exportingZip" @click="exportSelectedZip">导出 ZIP（{{ selectedMaterials.length }}）</el-button>
              <el-button :disabled="exportingZip" @click="clearFileSelection">清空</el-button>
              <el-button text :disabled="exportingZip" @click="clearFileSelection">取消</el-button>
            </div>
          </div>

          <el-card shadow="never" class="list-card">
            <div class="material-table-wrap">
              <el-table
                ref="materialTable"
                v-loading="loading"
                :data="rows"
                row-key="id"
                border
                size="small"
                :style="{ minWidth: selectedTaskId ? '864px' : '1104px' }"
                @select="handleMaterialSelectionChange"
                @select-all="handleMaterialSelectionChange"
              >
                <el-table-column type="selection" width="44" fixed="left" align="center" />
                <el-table-column label="材料名称" min-width="200">
                  <template #default="{ row }">
                    <div class="file-name-cell">
                      <span class="file-dot" :style="{ backgroundColor: FILE_CATEGORY_COLORS[row.category as keyof typeof FILE_CATEGORY_COLORS] }" />
                      <span :title="row.fileName">{{ row.fileName }}</span>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column v-if="selectedTaskId" label="年度" width="80">
                  <template #default="{ row }">{{ row.year ? `${row.year} 年` : '—' }}</template>
                </el-table-column>
                <el-table-column v-else label="学院任务" width="150">
                  <template #default="{ row }">
                    <template v-if="row.taskCode || row.year">
                      <span v-if="row.taskCode" class="task-code">{{ row.taskCode }}</span>
                      <span v-if="row.year" class="material-year">{{ row.year }} 年</span>
                    </template>
                    <span v-else>—</span>
                  </template>
                </el-table-column>
                <el-table-column v-if="!selectedTaskId" label="所属学院" min-width="120">
                  <template #default="{ row }">{{ row.unit?.name ?? '—' }}</template>
                </el-table-column>
                <el-table-column label="类型" width="80">
                  <template #default="{ row }">{{ CATEGORY_LABELS[row.category] ?? row.category }}</template>
                </el-table-column>
                <el-table-column label="大小" width="90" align="right">
                  <template #default="{ row }">{{ formatFileSize(row.size) }}</template>
                </el-table-column>
                <el-table-column label="上传人" width="100">
                  <template #default="{ row }">{{ row.uploadedBy?.name ?? '—' }}</template>
                </el-table-column>
                <el-table-column label="上传时间" width="150">
                  <template #default="{ row }">{{ formatDateTime(row.uploadedAt) }}</template>
                </el-table-column>
                <el-table-column label="操作" width="120" fixed="right" align="center">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="openPreview(row)">预览</el-button>
                    <el-button link type="primary" @click="download(row)">下载</el-button>
                  </template>
                </el-table-column>
                <template #empty><el-empty :image-size="80" :description="emptyDescription" /></template>
              </el-table>
            </div>
            <div v-loading="loading" class="material-mobile-list">
              <article v-for="row in rows" :key="row.id" class="material-mobile-card" :class="{ selected: selectedMaterialIds.has(row.id) }">
                <div class="material-mobile-card__head">
                  <el-checkbox :model-value="selectedMaterialIds.has(row.id)" @change="toggleMaterial(row)">
                    <span class="material-mobile-card__name">{{ row.fileName }}</span>
                  </el-checkbox>
                </div>
                <div class="material-mobile-card__meta">
                  <span>{{ CATEGORY_LABELS[row.category] ?? row.category }} · {{ formatFileSize(row.size) }}</span>
                  <span v-if="row.taskCode">{{ row.taskCode }}<template v-if="row.year"> · {{ row.year }} 年</template></span>
                  <span v-if="row.unit">{{ row.unit.name }}</span>
                  <span>{{ formatDateTime(row.uploadedAt) }}</span>
                </div>
                <div class="material-mobile-card__actions">
                  <el-button link type="primary" @click="openPreview(row)">预览</el-button>
                  <el-button link type="primary" @click="download(row)">下载</el-button>
                </div>
              </article>
              <el-empty v-if="rows.length === 0" :image-size="80" :description="emptyDescription" />
            </div>
            <el-pagination
              v-model:current-page="page"
              v-model:page-size="pageSize"
              class="list-pagination"
              background
              layout="total, sizes, prev, pager, next"
              :total="total"
              :page-sizes="[10, 20, 50]"
              @current-change="loadList"
              @size-change="search"
            />
          </el-card>
        </template>
      </section>
    </div>

    <el-dialog v-model="uploadVisible" title="上传并关联材料" width="min(680px, calc(100vw - 32px))" align-center :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="关联关键产出指标" required>
          <el-select v-model="uploadIndicatorId" filterable popper-class="material-indicator-popper" placeholder="请选择指标（可按编码或名称搜索）" style="width: 100%">
            <el-option-group v-for="group in indicatorGroups" :key="group.name" :label="group.name">
              <el-option v-for="item in group.items" :key="item.id" :label="`${item.code} ${item.name}`" :value="item.id">
                <span class="opt-code">{{ item.code }}</span><span class="opt-name">{{ item.name }}</span>
              </el-option>
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="工作材料" required>
          <MaterialUploader :key="uploadKey" v-model="uploadFileIds" biz-type="INDICATOR_ATTACHMENT" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="binding" @click="confirmBind">完成上传并关联</el-button>
      </template>
    </el-dialog>

    <FilePreviewDrawer v-model="previewVisible" :file="previewFile" />
  </div>
</template>

<style scoped>
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.page-title { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 20px; font-weight: 600; }
.page-title .el-icon { color: var(--el-color-primary); }
.page-subtitle { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.page-header-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; flex: none; }
.back-button { flex: none; }
.material-plan-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.material-plan-bar__label { flex: none; color: var(--el-text-color-secondary); font-size: 13px; }
.material-plan-select { width: min(520px, 100%); }
.material-workspace { display: grid; grid-template-columns: 300px minmax(0, 1fr); align-items: start; gap: 16px; }
.material-nav { overflow: hidden; background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.material-nav__head { display: flex; flex-direction: column; gap: 8px; padding: 12px; border-bottom: 1px solid var(--el-border-color-lighter); }
.material-nav__head strong { color: var(--el-text-color-primary); font-size: 13px; }
.material-nav__body { max-height: calc(100vh - 260px); overflow: auto; padding: 8px; }
.material-nav__direction + .material-nav__direction { margin-top: 10px; }
.material-nav__direction-name { padding: 4px 6px; color: var(--el-text-color-secondary); font-size: 12px; }
.material-nav__indicator { margin-top: 2px; }
.material-nav__indicator-row { display: flex; align-items: stretch; gap: 2px; border-radius: 4px; }
.material-nav__indicator-row.active { background: var(--el-color-primary-light-9); }
.material-nav__indicator-row.ancestor { background: var(--el-fill-color-light); }
.material-nav__indicator-main { display: flex; flex: 1; align-items: baseline; gap: 6px; min-width: 0; padding: 7px 6px; font: inherit; text-align: left; cursor: pointer; background: transparent; border: 0; border-radius: 4px; }
.material-nav__indicator-main:hover { background: var(--el-color-primary-light-9); }
.material-nav__code { flex: none; color: var(--el-color-primary); font-family: var(--app-font-mono, ui-monospace, monospace); font-size: 12px; font-weight: 600; }
.material-nav__name { min-width: 0; overflow: hidden; color: var(--el-text-color-primary); font-size: 12px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.material-nav__toggle { display: inline-flex; align-items: center; justify-content: center; width: 26px; color: var(--el-text-color-secondary); cursor: pointer; background: transparent; border: 0; border-radius: 4px; }
.material-nav__toggle:hover { background: var(--el-fill-color); }
.material-nav__toggle .el-icon { transition: transform 0.15s ease; }
.material-nav__toggle .el-icon.expanded { transform: rotate(90deg); }
.material-nav__tasks { margin: 2px 0 4px 16px; padding-left: 8px; border-left: 1px solid var(--el-border-color-lighter); }
.material-nav__task { display: flex; flex-direction: column; gap: 1px; width: 100%; padding: 6px 8px; font: inherit; text-align: left; cursor: pointer; background: transparent; border: 0; border-radius: 4px; }
.material-nav__task:hover { background: var(--el-fill-color-light); }
.material-nav__task.active { background: var(--el-color-primary-light-9); }
.material-nav__task-code { color: var(--el-color-primary); font-family: var(--app-font-mono, ui-monospace, monospace); font-size: 12px; font-weight: 600; }
.material-nav__task-unit { overflow: hidden; color: var(--el-text-color-secondary); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.material-nav__hint { padding: 6px 8px; color: var(--el-text-color-placeholder); font-size: 12px; }
.material-content { min-width: 0; }
.material-guide { display: grid; min-height: 360px; place-items: center; background: var(--el-bg-color); border: 1px dashed var(--el-border-color); border-radius: 6px; }
.material-content__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.material-content__title { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.material-content__title strong { color: var(--el-text-color-primary); font-size: 15px; }
.material-content__title span { color: var(--el-text-color-secondary); font-size: 12px; }
.material-content__actions { display: flex; flex-wrap: wrap; gap: 8px; flex: none; }
.filter-card { margin-bottom: 16px; }
.filter-card :deep(.el-card__body) { padding-bottom: 0; }
.material-selection-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; padding: 10px 12px; color: var(--el-color-primary); background: var(--el-color-primary-light-9); border: 1px solid var(--el-color-primary-light-7); border-radius: 6px; }
.material-selection-bar > span { font-size: 13px; }
.material-selection-bar small { margin-left: 10px; color: var(--el-text-color-secondary); }
.material-selection-bar__actions { display: flex; align-items: center; gap: 8px; flex: none; }
.material-mobile-list { display: none; }
:global(.material-indicator-popper) { max-width: 560px; }
:global(.material-indicator-popper .el-select-dropdown__item) { display: flex; align-items: center; gap: 6px; }
:global(.material-indicator-popper .opt-code) { flex: none; color: var(--el-color-primary); font-weight: 600; font-variant-numeric: tabular-nums; }
:global(.material-indicator-popper .opt-name) { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-name-cell { display: flex; align-items: center; gap: 8px; min-width: 0; }
.file-name-cell > span:last-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-dot { flex-shrink: 0; width: 8px; height: 8px; border-radius: 50%; }
.indicator-code, .task-code { color: var(--el-color-primary); font-weight: 600; font-variant-numeric: tabular-nums; }
.material-year { margin-left: 6px; color: var(--el-text-color-secondary); font-size: 12px; }
.material-table-wrap { overflow-x: auto; }
.list-pagination { justify-content: flex-end; margin-top: 16px; }
.materials-page :deep(.material-uploader) { width: 100%; }

@media (max-width: 900px) {
  .material-workspace { grid-template-columns: minmax(0, 1fr); }
  .material-nav__body { max-height: 360px; }
}

@media (max-width: 768px) {
  .page-header { flex-direction: column; }
  .page-header-actions { width: 100%; }
  .page-header-actions .el-button { flex: 1; min-height: 44px; margin: 0; }
  .material-plan-bar { flex-direction: column; align-items: stretch; }
  .material-plan-select { width: 100%; }
  .material-content__head { flex-direction: column; }
  .material-content__actions { width: 100%; }
  .filter-card :deep(.el-form-item) { display: flex; margin-right: 0; }
  .filter-card :deep(.el-form-item__content) { flex: 1; }
  .filter-card :deep(.el-select), .filter-card :deep(.el-input) { width: 100% !important; }
  .material-selection-bar { align-items: stretch; flex-direction: column; }
  .material-selection-bar small { display: block; margin: 4px 0 0; }
  .material-selection-bar__actions { flex-wrap: wrap; }
  .material-selection-bar__actions .el-button { min-height: 40px; margin: 0; }
  .material-selection-bar__actions .el-button:first-child { flex: 1; }
  .material-table-wrap { display: none; }
  .material-mobile-list { display: grid; gap: 10px; }
  .material-mobile-card { padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
  .material-mobile-card.selected { background: var(--el-color-primary-light-9); border-color: var(--el-color-primary-light-5); }
  .material-mobile-card__head :deep(.el-checkbox) { width: 100%; height: auto; align-items: flex-start; white-space: normal; }
  .material-mobile-card__head :deep(.el-checkbox__input) { margin-top: 3px; }
  .material-mobile-card__head :deep(.el-checkbox__label) { min-width: 0; line-height: 1.45; overflow-wrap: anywhere; }
  .material-mobile-card__name { color: var(--el-text-color-primary); font-weight: 600; }
  .material-mobile-card__meta { display: flex; flex-direction: column; gap: 4px; margin: 8px 0 4px 24px; color: var(--el-text-color-secondary); font-size: 12px; }
  .material-mobile-card__actions { display: flex; justify-content: flex-end; }
  .material-mobile-card__actions .el-button { min-height: 40px; }
  .list-pagination { justify-content: center; }
}
</style>
