<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import PageState from '@/components/PageState.vue'
import PlanSwitcher from '@/components/PlanSwitcher.vue'
import FilePreviewPopup from '@/components/FilePreviewPopup.vue'
import FileUploader from '@/components/FileUploader.vue'
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
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'
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

const plans = ref<PlanCard[]>([])
const planId = ref('')
const indicators = ref<MaterialIndicatorOption[]>([])
const units = ref<OrgUnit[]>([])
const rows = ref<MaterialItem[]>([])
const total = ref(0)
const loading = ref(false)
const page = ref(1)
const pageSize = 10
const listRequestSequence = ref(0)

const filters = reactive({ unitId: '', category: '', keyword: '' })
const selectedIndicatorId = ref('')
const selectedTaskId = ref('')
const selectedTaskCode = ref('')
const selectedTaskYear = ref<number | null>(null)
const selectedTaskUnitId = ref('')
const selectedMaterials = ref<MaterialItem[]>([])

const canDirectUpload = computed(
  () => userStore.roles.includes('ADMIN') || userStore.roles.includes('SCHOOL_OWNER'),
)
const selectedIndicator = computed(
  () => indicators.value.find((item) => item.id === selectedIndicatorId.value) ?? null,
)
const selectedMaterialIds = computed(() => new Set(selectedMaterials.value.map((item) => item.id)))
const showUnitFilter = computed(() => !userStore.isUnitOwnerOnly && !selectedTaskId.value)
const selectionTitle = computed(() => {
  if (selectedTaskId.value) {
    const code = selectedTaskCode.value || '学院任务'
    return selectedTaskYear.value === null ? code : `${code} · ${selectedTaskYear.value} 年`
  }
  return selectedIndicator.value ? `${selectedIndicator.value.code} ${selectedIndicator.value.name}` : ''
})
const selectionSubtitle = computed(() =>
  selectedTaskId.value
    ? (selectedTaskYear.value === null ? '该任务全部年度审核通过的材料' : '该任务该年度审核通过的材料')
    : (selectedIndicatorId.value ? '该指标下所有任务审核通过的材料' : ''),
)

// 指标选择
const selectorVisible = ref(false)
const navKeyword = ref('')
const expandedIds = ref<Set<string>>(new Set())
const tasksByIndicator = reactive<Record<string, TaskListItem[]>>({})
const tasksLoading = reactive<Record<string, boolean>>({})

const indicatorGroups = computed(() => {
  const groups = new Map<string, MaterialIndicatorOption[]>()
  for (const item of indicators.value) {
    const key = item.directionName?.trim() ? item.directionName : '未归入建设方向'
    const list = groups.get(key)
    if (list) list.push(item)
    else groups.set(key, [item])
  }
  return [...groups.entries()].map(([name, items]) => ({ name, items }))
})
const filteredGroups = computed(() => {
  const keyword = navKeyword.value.trim().toLowerCase()
  if (!keyword) return indicatorGroups.value
  return indicatorGroups.value
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => `${item.code} ${item.name}`.toLowerCase().includes(keyword)),
    }))
    .filter((group) => group.items.length > 0)
})

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

function toggleIndicator(item: MaterialIndicatorOption): void {
  const next = new Set(expandedIds.value)
  if (next.has(item.id)) next.delete(item.id)
  else {
    next.add(item.id)
    void loadTasks(item.id)
  }
  expandedIds.value = next
}

function selectIndicator(item: MaterialIndicatorOption): void {
  selectedIndicatorId.value = item.id
  selectedTaskId.value = ''
  selectedTaskCode.value = ''
  selectedTaskYear.value = null
  selectedTaskUnitId.value = ''
  filters.unitId = ''
  page.value = 1
  selectorVisible.value = false
  selectedMaterials.value = []
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
  selectorVisible.value = false
  selectedMaterials.value = []
  void loadList()
}

function clearSelection(): void {
  selectedIndicatorId.value = ''
  selectedTaskId.value = ''
  selectedTaskCode.value = ''
  selectedTaskYear.value = null
  selectedTaskUnitId.value = ''
  filters.unitId = ''
  rows.value = []
  total.value = 0
  selectedMaterials.value = []
  page.value = 1
}

function currentUnitId(): string {
  return selectedTaskId.value ? selectedTaskUnitId.value : filters.unitId
}

async function loadIndicators(): Promise<void> {
  indicators.value = await listMaterialIndicators(planId.value || undefined)
}

async function loadList(): Promise<void> {
  const sequence = ++listRequestSequence.value
  if (!selectedIndicatorId.value) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
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
      pageSize,
    })
    if (sequence !== listRequestSequence.value) return
    rows.value = result.items
    total.value = result.total
  } finally {
    if (sequence === listRequestSequence.value) loading.value = false
  }
}

function search(): void {
  page.value = 1
  void loadList()
}

function resetFilters(): void {
  filters.category = ''
  filters.keyword = ''
  filters.unitId = ''
  search()
}

function changePage(next: number): void {
  page.value = next
  void loadList()
}

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

function toggleMaterial(row: MaterialItem): void {
  if (selectedMaterialIds.value.has(row.id)) {
    selectedMaterials.value = selectedMaterials.value.filter((item) => item.id !== row.id)
  } else {
    selectedMaterials.value = [...selectedMaterials.value, row]
  }
}

// 预览/下载
const previewVisible = ref(false)
const previewFile = ref<MaterialItem | null>(null)
function openPreview(row: MaterialItem): void {
  previewFile.value = row
  previewVisible.value = true
}
async function download(row: MaterialItem): Promise<void> {
  try {
    await downloadFile(row.id, row.fileName, row.size)
  } catch {
    showToast('获取下载地址失败，请重试')
  }
}

// 上传
const uploadVisible = ref(false)
const uploadIndicatorId = ref('')
const uploadFileIds = ref<string[]>([])
const uploadKey = ref(0)
const binding = ref(false)
const unitPickerVisible = ref(false)
const uploadIndicatorLabel = computed(
  () => indicators.value.find((item) => item.id === uploadIndicatorId.value)?.name ?? '',
)
const uploadPickerVisible = ref(false)

function openUpload(): void {
  uploadIndicatorId.value = selectedIndicatorId.value || ''
  uploadFileIds.value = []
  uploadKey.value += 1
  uploadVisible.value = true
}

async function confirmBind(): Promise<void> {
  if (!uploadIndicatorId.value) {
    showToast('请选择要关联的关键产出指标')
    return
  }
  if (uploadFileIds.value.length === 0) {
    showToast('请等待材料上传完成后再确认')
    return
  }
  binding.value = true
  try {
    await bindMaterials(uploadIndicatorId.value, uploadFileIds.value)
    showToast(`已上传并关联 ${uploadFileIds.value.length} 份材料`)
    uploadVisible.value = false
    if (!selectedIndicatorId.value) selectedIndicatorId.value = uploadIndicatorId.value
    await loadList()
  } finally {
    binding.value = false
  }
}

// 批量 ZIP
const exportingZip = ref(false)
async function exportSelectedZip(): Promise<void> {
  if (selectedMaterials.value.length === 0) {
    showToast('请先勾选要导出的材料')
    return
  }
  exportingZip.value = true
  try {
    const { exportId } = await createExport(
      { type: 'MATERIAL_ZIP', params: { fileIds: selectedMaterials.value.map((item) => item.id) } },
      newIdempotencyKey(),
    )
    const job = await pollExport(exportId, undefined, { timeoutMs: 600000 })
    if (job.status === 'SUCCEEDED' && job.downloadUrl) {
      triggerFileDownload(job.downloadUrl, '材料库.zip')
      showToast('材料包已生成，开始下载')
    } else {
      showToast(job.errorMessage ?? '材料打包失败')
    }
  } catch (error) {
    showToast(error instanceof Error ? error.message : '材料打包失败')
  } finally {
    exportingZip.value = false
  }
}

// 计划
function defaultPlanId(): string {
  const current = getCurrentPlan()
  return plans.value.find((item) => item.id === current?.id)?.id
    ?? plans.value.find((item) => item.status === 'ACTIVE')?.id
    ?? plans.value[0]?.id
    ?? ''
}

function resetPlanContext(): void {
  listRequestSequence.value += 1
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
  total.value = 0
  page.value = 1
  expandedIds.value = new Set()
  Object.keys(tasksByIndicator).forEach((key) => delete tasksByIndicator[key])
  Object.keys(tasksLoading).forEach((key) => delete tasksLoading[key])
}

async function handlePlanChange(plan: { id: string; name: string }): Promise<void> {
  planId.value = plan.id
  setCurrentPlan(plan)
  resetPlanContext()
  await router.replace({ path: route.path, query: { planId: plan.id } })
  await loadIndicators()
}

onMounted(async () => {
  const routePlanId = typeof route.query.planId === 'string' ? route.query.planId : ''
  const requests: Promise<unknown>[] = [
    listPlans().then((data) => {
      plans.value = data
    }).catch(() => undefined),
  ]
  if (!userStore.isUnitOwnerOnly) {
    requests.push(listOrgUnits().then((data) => {
      units.value = data
    }))
  }
  await Promise.all(requests)
  planId.value = routePlanId || defaultPlanId()
  if (!planId.value) return
  if (!routePlanId) await router.replace({ query: { ...route.query, planId: planId.value } })
  await loadIndicators()

  const queryIndicatorId = typeof route.query.indicatorId === 'string' ? route.query.indicatorId : ''
  if (!queryIndicatorId) return
  selectedIndicatorId.value = queryIndicatorId
  const queryTaskId = typeof route.query.taskId === 'string' ? route.query.taskId : ''
  if (queryTaskId) {
    selectedTaskId.value = queryTaskId
    selectedTaskCode.value = typeof route.query.taskCode === 'string' ? route.query.taskCode : ''
    const year = Number(route.query.year)
    selectedTaskYear.value = Number.isFinite(year) && year > 0 ? year : null
    selectedTaskUnitId.value = typeof route.query.unitId === 'string' ? route.query.unitId : ''
    expandedIds.value = new Set([...expandedIds.value, queryIndicatorId])
    await loadTasks(queryIndicatorId)
    const task = (tasksByIndicator[queryIndicatorId] ?? []).find((item) => item.id === queryTaskId)
    if (task) {
      selectedTaskCode.value = task.code || selectedTaskCode.value
      selectedTaskUnitId.value = task.unit?.id ?? selectedTaskUnitId.value
    }
  }
  await loadList()
})
</script>

<template>
  <div class="page" :class="{ 'page--footer': selectedMaterials.length > 0 }">
    <PlanSwitcher
      v-if="plans.length > 0"
      :model-value="planId"
      :plans="plans"
      @change="handlePlanChange"
    />

    <div class="page-head">
      <h1>材料库</h1>
      <van-button v-if="canDirectUpload" size="small" type="primary" icon="plus" @click="openUpload">上传材料</van-button>
    </div>

    <button type="button" class="selection-btn" @click="selectorVisible = true">
      <van-icon name="folder-o" size="18" />
      <div class="grow">
        <div v-if="selectionTitle" class="ellipsis">{{ selectionTitle }}</div>
        <div v-else class="muted">选择关键产出指标或学院任务</div>
        <div v-if="selectionSubtitle" class="small muted">{{ selectionSubtitle }}</div>
      </div>
      <van-icon name="arrow-down" size="13" />
    </button>
    <button v-if="selectedIndicatorId" type="button" class="clear-link" @click="clearSelection">清除选择</button>

    <template v-if="selectedIndicatorId">
      <div class="filter-scroll">
        <button
          v-for="option in [
            { value: '', label: '全部类型' },
            { value: 'DOCUMENT', label: '文档' },
            { value: 'PDF', label: 'PDF' },
            { value: 'IMAGE', label: '图片' },
            { value: 'VIDEO', label: '视频' },
            { value: 'ARCHIVE', label: '压缩包' },
          ]"
          :key="option.value"
          type="button"
          class="filter-chip"
          :class="{ 'is-active': filters.category === option.value }"
          @click="filters.category = option.value; search()"
        >{{ option.label }}</button>
      </div>

      <div class="filter-line">
        <button v-if="showUnitFilter" type="button" class="filter-btn" @click="unitPickerVisible = true">
          {{ filters.unitId ? units.find((unit) => unit.id === filters.unitId)?.name ?? '全部单元' : '全部单元' }}
          <van-icon name="arrow-down" size="10" />
        </button>
        <van-search v-model="filters.keyword" placeholder="搜索文件名" shape="round" @search="search" @clear="search" />
      </div>
      <button
        v-if="selectedTaskId && selectedTaskYear !== null"
        type="button"
        class="clear-link"
        @click="selectedTaskYear = null; search()"
      >查看全部年度</button>

      <PageState
        :loading="loading"
        :empty="rows.length === 0"
        empty-text="暂无可查看的审核通过材料"
      >
        <div class="material-list">
          <article
            v-for="row in rows"
            :key="row.id"
            class="material-card"
            :class="{ 'is-selected': selectedMaterialIds.has(row.id) }"
          >
            <button type="button" class="check-btn" @click="toggleMaterial(row)">
              <van-icon :name="selectedMaterialIds.has(row.id) ? 'checked' : 'circle'" :color="selectedMaterialIds.has(row.id) ? '#2B5BC0' : '#C4CDDA'" size="20" />
            </button>
            <span class="file-dot" :style="{ backgroundColor: FILE_CATEGORY_COLORS[row.category] }" />
            <div class="material-main" @click="openPreview(row)">
              <div class="file-name ellipsis-2">{{ row.fileName }}</div>
              <div class="file-meta">
                <span>{{ CATEGORY_LABELS[row.category] ?? row.category }}</span>
                <span>{{ formatFileSize(row.size) }}</span>
                <span>{{ formatDateTime(row.uploadedAt) }}</span>
              </div>
              <div class="file-meta">
                <span>{{ row.indicator.code }} {{ row.indicator.name }}</span>
              </div>
              <div class="file-meta">
                <span v-if="row.taskCode">{{ row.taskCode }}</span>
                <span v-if="row.year">{{ row.year }} 年</span>
                <span v-if="row.unit">{{ row.unit.name }}</span>
                <span v-if="row.uploadedBy">上传人 {{ row.uploadedBy.name }}</span>
              </div>
            </div>
            <button type="button" class="icon-btn" @click="openPreview(row)">
              <van-icon name="eye-o" size="18" />
            </button>
            <button type="button" class="icon-btn" @click="download(row)">
              <van-icon name="down" size="18" />
            </button>
          </article>
        </div>
        <div class="pager">
          <van-button size="small" :disabled="page <= 1" @click="changePage(page - 1)">上一页</van-button>
          <span class="small muted">{{ page }} / {{ totalPages }}（共 {{ total }} 份）</span>
          <van-button size="small" :disabled="page >= totalPages" @click="changePage(page + 1)">下一页</van-button>
        </div>
      </PageState>
    </template>
    <div v-else class="empty-state">
      <div class="empty-state__icon"><van-icon name="folder-o" /></div>
      请选择关键产出指标或学院任务
    </div>

    <div v-if="selectedMaterials.length > 0" class="sticky-footer">
      <div class="sticky-footer__row">
        <van-button @click="selectedMaterials = []">取消选择</van-button>
        <van-button type="primary" :loading="exportingZip" @click="exportSelectedZip">
          导出 ZIP（{{ selectedMaterials.length }}）
        </van-button>
      </div>
    </div>

    <!-- 指标/任务选择 -->
    <van-popup v-model:show="selectorVisible" position="bottom" round :style="{ height: '88vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>选择指标或任务</strong>
          <button type="button" class="close-btn" @click="selectorVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <van-search v-model="navKeyword" placeholder="搜索指标编号或名称" shape="round" />
        <div class="picker-list">
          <div v-for="group in filteredGroups" :key="group.name" class="direction-group">
            <div class="direction-title">{{ group.name }}</div>
            <div v-for="item in group.items" :key="item.id" class="indicator-block">
              <div class="indicator-row">
                <button type="button" class="indicator-main" @click="selectIndicator(item)">
                  <span class="code">{{ item.code }}</span>
                  <span class="ellipsis">{{ item.name }}</span>
                </button>
                <button type="button" class="expand-btn" @click="toggleIndicator(item)">
                  {{ expandedIds.has(item.id) ? '收起' : '任务' }}
                  <van-icon :name="expandedIds.has(item.id) ? 'arrow-up' : 'arrow-down'" size="10" />
                </button>
              </div>
              <div v-if="expandedIds.has(item.id)" class="task-sublist">
                <div v-if="tasksLoading[item.id]" class="small muted">加载任务…</div>
                <button
                  v-for="task in tasksByIndicator[item.id] ?? []"
                  :key="task.id"
                  type="button"
                  class="task-subitem"
                  @click="selectTask(task)"
                >
                  <span class="code">{{ task.code }}</span>
                  <span class="ellipsis">{{ task.unit?.name ?? '—' }}</span>
                </button>
                <div v-if="!(tasksByIndicator[item.id] ?? []).length && !tasksLoading[item.id]" class="small muted">
                  该指标暂无任务
                </div>
              </div>
            </div>
          </div>
          <div v-if="filteredGroups.length === 0" class="empty-state">没有匹配的指标</div>
        </div>
      </div>
    </van-popup>

    <!-- 上传材料 -->
    <van-popup v-model:show="uploadVisible" position="bottom" round :style="{ height: '85vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>上传材料</strong>
          <button type="button" class="close-btn" @click="uploadVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="upload-body">
          <div class="field">
            <label>关联指标<span class="required">*</span></label>
            <van-field
              readonly
              is-link
              :model-value="uploadIndicatorLabel"
              placeholder="请选择关键产出指标"
              @click="uploadPickerVisible = true"
            />
          </div>
          <FileUploader
            :key="uploadKey"
            v-model="uploadFileIds"
            biz-type="INDICATOR_ATTACHMENT"
            hint="材料上传完成后点击确认关联"
          />
        </div>
        <div class="sheet-actions">
          <van-button block @click="uploadVisible = false">取消</van-button>
          <van-button block type="primary" :loading="binding" @click="confirmBind">确认关联</van-button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="uploadPickerVisible" position="bottom" round :style="{ height: '70vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>选择指标</strong>
          <button type="button" class="close-btn" @click="uploadPickerVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="picker-list">
          <button
            v-for="item in indicators"
            :key="item.id"
            type="button"
            class="picker-item"
            @click="uploadIndicatorId = item.id; uploadPickerVisible = false"
          >
            <strong class="code">{{ item.code }}</strong> {{ item.name }}
          </button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="unitPickerVisible" position="bottom" round :style="{ height: '60vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>选择执行单元</strong>
          <button type="button" class="close-btn" @click="unitPickerVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="picker-list">
          <button type="button" class="picker-item" @click="filters.unitId = ''; unitPickerVisible = false; search()">全部单元</button>
          <button
            v-for="unit in units"
            :key="unit.id"
            type="button"
            class="picker-item"
            @click="filters.unitId = unit.id; unitPickerVisible = false; search()"
          >{{ unit.code }} {{ unit.name }}</button>
        </div>
      </div>
    </van-popup>

    <FilePreviewPopup v-model="previewVisible" :file="previewFile" :files="rows" @select="(file) => (previewFile = file as MaterialItem)" />
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: 10px 0 12px;
}
.page-head h1 {
  margin: 0;
  font-size: 22px;
}
.selection-btn {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 58px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--text);
  text-align: left;
}
.clear-link {
  min-height: 32px;
  margin: 2px 0 0 auto;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  display: block;
}
.filter-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.filter-btn {
  flex: 0 0 auto;
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: #fff;
  color: var(--text);
  font-size: 12px;
}
.material-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.material-card {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.material-card.is-selected {
  box-shadow: 0 0 0 1px var(--brand) inset;
}
.check-btn,
.icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: none;
  background: transparent;
  color: var(--brand);
  flex-shrink: 0;
}
.file-dot {
  width: 8px;
  height: 8px;
  margin-top: 8px;
  border-radius: 2px;
  flex-shrink: 0;
}
.material-main {
  flex: 1;
  min-width: 0;
}
.file-name {
  font-size: 13px;
  line-height: 19px;
}
.file-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  margin-top: 3px;
  color: var(--text-secondary);
  font-size: 11px;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 12px 0;
}
.picker-sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 14px 14px calc(14px + var(--safe-bottom));
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
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
.picker-list {
  flex: 1;
  overflow-y: auto;
  margin-top: 6px;
}
.direction-group {
  margin-bottom: 8px;
}
.direction-title {
  padding: 8px 4px 4px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
}
.indicator-block {
  border-bottom: 1px solid var(--line);
}
.indicator-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.indicator-main {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-height: 46px;
  padding: 8px 4px;
  border: none;
  background: transparent;
  color: var(--text);
  text-align: left;
  font-size: 13px;
  min-width: 0;
}
.expand-btn {
  flex-shrink: 0;
  min-height: 34px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 17px;
  background: #fbfcfe;
  color: var(--brand);
  font-size: 11px;
}
.task-sublist {
  padding: 0 0 6px 14px;
}
.task-subitem {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 42px;
  padding: 6px;
  border: none;
  border-radius: 8px;
  background: #f8fafd;
  margin-bottom: 4px;
  color: var(--text);
  text-align: left;
  font-size: 12px;
}
.code {
  color: var(--brand);
  font-family: Consolas, Menlo, monospace;
  font-weight: 600;
  flex-shrink: 0;
}
.upload-body {
  flex: 1;
  overflow-y: auto;
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
.sheet-actions {
  display: flex;
  gap: 10px;
  margin-top: 10px;
}
.picker-item {
  width: 100%;
  min-height: 48px;
  padding: 10px 6px;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--text);
  text-align: left;
  font-size: 13px;
}
</style>
