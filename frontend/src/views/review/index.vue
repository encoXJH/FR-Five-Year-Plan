<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
import FilePreviewPane from '@/components/FilePreviewPane.vue'
import SubmissionTimeline from '@/views/submission/components/SubmissionTimeline.vue'
import { batchReview, listPendingReviews, reviewSubmission } from '@/api/review'
import { getSubmission, listSubmissions } from '@/api/submission'
import { listIndicators } from '@/api/indicator'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { useUserStore } from '@/stores/user'
import { newIdempotencyKey } from '@/utils/idempotency'
import { getCurrentPlan } from '@/utils/currentPlan'
import { ApiError } from '@/utils/request'
import { formatDateTime, formatPercent, formatRelativeTime } from '@/utils/format'
import { FILE_CATEGORY_COLORS, formatFileSize } from '@/utils/file'
import { percentToDisplay, percentToStore } from '@/utils/allocation'
import { shouldApplyRefreshResponse, useAutoRefresh } from '@/utils/autoRefresh'
import {
  approvedReduction,
  buildMetricValue,
  formatMetricValue,
  isApprovedValueValid,
  parseSubmissionRemark,
} from '@/utils/submission'
import type { PageResult } from '@/types/api'
import type { FileInfo } from '@/types/file'
import type { StatCaliber } from '@/types/enums'
import type {
  PendingReviewItem,
  SubmissionDetail,
  SubmissionListItem,
} from '@/types/submission'

const route = useRoute()
const userStore = useUserStore()

/** 管理员代审（非建设方向负责人）：代审原因选填，填写后写入审计日志 */
const isDelegateReview = computed(
  () => userStore.roles.includes('ADMIN') && !userStore.roles.includes('SCHOOL_OWNER'),
)

// ---------- 筛选 ----------
const filters = reactive({
  planId: (typeof route.query.planId === 'string' ? route.query.planId : '') || getCurrentPlan()?.id || '',
  indicatorId: '',
  unitId: '',
  keyword: '',
  dateRange: null as [string, string] | null,
})

const unitOptions = ref<OrgUnit[]>([])
const indicatorOptions = ref<{ id: string; code: string; name: string; directionCode: string | null; directionName: string | null }[]>([])
const indicatorGroups = computed(() => {
  const groups = new Map<string, typeof indicatorOptions.value>()
  for (const option of indicatorOptions.value) {
    const name = option.directionName
      ? option.directionName
      : '未归入建设方向'
    const list = groups.get(name) ?? []
    list.push(option)
    groups.set(name, list)
  }
  return [...groups.entries()].map(([name, items]) => ({ name, items }))
})

async function loadIndicatorOptions(): Promise<void> {
  indicatorOptions.value = []
  const linkedIndicatorId = filters.indicatorId
  filters.indicatorId = ''
  if (!filters.planId) return
  try {
    const tree = await listIndicators(filters.planId)
    const legacyParent = tree.find((node) =>
      node.children.some((child) => child.id === linkedIndicatorId),
    )
    if (legacyParent) filters.indicatorId = legacyParent.id
    indicatorOptions.value = tree.map((node) => ({
      id: node.id,
      code: node.code,
      name: node.name,
      directionCode: node.directionCode ?? null,
      directionName: node.directionName ?? null,
    }))
  } catch {
    // 指标筛选不可用时忽略，保留其他筛选
  }
}

// ---------- 待审列表 ----------
const listLoading = ref(false)
const page = ref<PageResult<PendingReviewItem>>({ items: [], total: 0, page: 1, pageSize: 50 })
const checkedIds = ref<string[]>([])

const selectedId = ref<string | null>(null)

function currentQuery() {
  const [from, to] = filters.dateRange ?? [undefined, undefined]
  return {
    planId: filters.planId || undefined,
    indicatorId: filters.indicatorId || undefined,
    unitId: filters.unitId || undefined,
    keyword: filters.keyword || undefined,
    submittedAtFrom: from ? `${from}T00:00:00+08:00` : undefined,
    submittedAtTo: to ? `${to}T23:59:59+08:00` : undefined,
    page: page.value.page,
    pageSize: page.value.pageSize,
  }
}

let listLoadGeneration = 0

async function loadList(keepSelection = false, autoSelect = true, silent = false): Promise<void> {
  const generation = ++listLoadGeneration
  const selectionAtStart = selectedId.value
  if (!silent) listLoading.value = true
  try {
    const result = await listPendingReviews(currentQuery())
    if (!shouldApplyRefreshResponse({
      current: generation === listLoadGeneration && selectedId.value === selectionAtStart,
      silent,
      skipping: reviewFormDirty.value,
    })) return
    page.value = result
    const previous = keepSelection ? selectedId.value : null
    checkedIds.value = []
    const items = page.value.items
    const next = items.find((item) => item.id === previous)?.id
      ?? (autoSelect ? items[0]?.id ?? null : null)
    selectedId.value = next
    if (next) {
      await loadDetail(next, silent)
    } else {
      detail.value = null
      detailLoadError.value = null
      history.value = []
    }
  } finally {
    if (!silent && generation === listLoadGeneration) listLoading.value = false
  }
}

function applyFilters(): void {
  page.value.page = 1
  loadList()
}

function resetFilters(): void {
  filters.indicatorId = ''
  filters.unitId = ''
  filters.keyword = ''
  filters.dateRange = null
  applyFilters()
}

function changePage(next: number): void {
  page.value.page = next
  loadList(true)
}

function changePageSize(size: number): void {
  page.value.pageSize = size
  page.value.page = 1
  loadList(true)
}

// ---------- 详情 ----------
const detail = ref<SubmissionDetail | null>(null)
const detailLoading = ref(false)
const detailLoadError = ref<{ message: string; requestId: string } | null>(null)
const history = ref<SubmissionListItem[]>([])

const caliber = computed<StatCaliber>(() => detail.value?.statCaliber ?? 'INCREMENT')

const valueText = computed(() =>
  detail.value ? formatMetricValue(detail.value.value, caliber.value) : '—',
)
const detailRemarkParts = computed(() => parseSubmissionRemark(detail.value?.remark))

/** 材料列表（防御后端省略字段） */
const detailFiles = computed(() => detail.value?.files ?? [])

let detailLoadGeneration = 0

async function loadDetail(id: string, silent = false): Promise<void> {
  const generation = ++detailLoadGeneration
  if (!silent) detailLoading.value = true
  detailLoadError.value = null
  try {
    const data = await getSubmission(id)
    if (!shouldApplyRefreshResponse({
      current: generation === detailLoadGeneration && selectedId.value === id,
      silent,
      skipping: reviewFormDirty.value,
    })) return
    detail.value = data
    initReviewForm(data)
    const taskId = data.task?.id
    const submissionHistory = taskId ? await listSubmissions(taskId) : []
    if (generation !== detailLoadGeneration || selectedId.value !== id) return
    history.value = submissionHistory
    // 内嵌预览面板（UI 规范 7.7）：有材料时自动预览第一份；自动刷新时保留用户当前预览
    const files = data.files ?? []
    if (!silent || !files.some((file) => file.id === previewFileId.value)) {
      previewFileId.value = files.length > 0 ? files[0].id : null
    }
  } catch (error) {
    if (generation !== detailLoadGeneration || selectedId.value !== id || (silent && reviewFormDirty.value)) return
    if (!silent) {
      detail.value = null
      history.value = []
      previewFileId.value = null
    }
    detailLoadError.value = {
      message: error instanceof ApiError && error.message ? error.message : '填报详情加载失败，请重试',
      requestId: error instanceof ApiError ? error.requestId : '',
    }
  } finally {
    if (!silent && generation === detailLoadGeneration) detailLoading.value = false
  }
}

function selectItem(item: PendingReviewItem): void {
  if (selectedId.value === item.id) return
  selectedId.value = item.id
  loadDetail(item.id)
}

// ---------- 审核操作 ----------
const reviewForm = reactive({
  amount: undefined as number | undefined,
  total: undefined as number | undefined,
  numerator: undefined as number | undefined,
  denominator: undefined as number | undefined,
  percent: undefined as number | undefined,
  comment: '',
  reason: '',
})

function initReviewForm(data: SubmissionDetail): void {
  const value = data.value ?? {}
  reviewForm.amount = value.amount ?? undefined
  reviewForm.total = value.total ?? undefined
  reviewForm.numerator = value.numerator ?? undefined
  reviewForm.denominator = value.denominator ?? undefined
  reviewForm.percent = percentToDisplay(value.percent)
  reviewForm.comment = ''
  reviewForm.reason = ''
}

/** 审核表单已改动：自动刷新时跳过，避免覆盖认定值或审核意见 */
const reviewFormDirty = computed(() => {
  const value = detail.value?.value
  if (!value) return false
  return (reviewForm.amount ?? null) !== (value.amount ?? null)
    || (reviewForm.total ?? null) !== (value.total ?? null)
    || (reviewForm.numerator ?? null) !== (value.numerator ?? null)
    || (reviewForm.denominator ?? null) !== (value.denominator ?? null)
    || (reviewForm.percent ?? null) !== (percentToDisplay(value.percent) ?? null)
    || reviewForm.comment.trim() !== ''
    || reviewForm.reason.trim() !== ''
})

const approvedValue = computed(() =>
  buildMetricValue(caliber.value, {
    amount: reviewForm.amount ?? null,
    total: reviewForm.total ?? null,
    numerator: reviewForm.numerator ?? null,
    denominator: reviewForm.denominator ?? null,
    percent: percentToStore(reviewForm.percent),
    achieved: detail.value?.value.achieved ?? null,
  }),
)

const reduction = computed(() =>
  detail.value ? approvedReduction(caliber.value, detail.value.value, approvedValue.value) : null,
)

const approving = ref(false)
const rejecting = ref(false)

function validateApproved(): string[] {
  const errors: string[] = []
  if (!detail.value) return errors
  if (!isApprovedValueValid(caliber.value, detail.value.value, approvedValue.value)) {
    errors.push('认定值不能大于填报值（60005）')
  }
  if (reduction.value !== null && !reviewForm.comment.trim()) {
    errors.push('核减认定值时必须填写审核意见')
  }
  return errors
}

async function afterReviewed(message: string): Promise<void> {
  ElMessage.success(message)
  await loadList()
}

async function handleApprove(): Promise<void> {
  const target = detail.value
  if (!target) return
  const errors = validateApproved()
  if (errors.length > 0) {
    ElMessage.error(errors[0])
    return
  }
  try {
    await ElMessageBox.confirm(
      reduction.value !== null
        ? `确认通过「${target.task?.code ?? ''}」的填报？认定值核减 ${reduction.value}。`
        : `确认通过「${target.task?.code ?? ''}」的填报？`,
      '通过填报',
      { type: 'info', confirmButtonText: '确认通过', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  approving.value = true
  try {
    const body =
      caliber.value === 'BOOLEAN'
        ? { result: 'APPROVED' as const, comment: reviewForm.comment || undefined, version: target.version, reason: reviewForm.reason || undefined }
        : { result: 'APPROVED' as const, comment: reviewForm.comment || undefined, approvedValue: approvedValue.value, version: target.version, reason: reviewForm.reason || undefined }
    await reviewSubmission(target.id, body, newIdempotencyKey())
    await afterReviewed(
      reduction.value !== null ? `已通过（核减 ${reduction.value}）` : '已通过',
    )
  } catch (error) {
    if (error instanceof ApiError) ElMessage.error(`${error.message}（${error.code}）`)
  } finally {
    approving.value = false
  }
}

async function handleReject(): Promise<void> {
  const target = detail.value
  if (!target) return
  if (!reviewForm.comment.trim()) {
    ElMessage.error('驳回时必须填写审核意见（60004）')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认驳回「${target.task?.code ?? ''}」的填报？驳回后学院需修改后重新提交。`,
      '驳回填报',
      { type: 'warning', confirmButtonText: '确认驳回', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  rejecting.value = true
  try {
    await reviewSubmission(
      target.id,
      {
        result: 'REJECTED',
        comment: reviewForm.comment,
        version: target.version,
        reason: reviewForm.reason || undefined,
      },
      newIdempotencyKey(),
    )
    await afterReviewed('已驳回')
  } catch (error) {
    if (error instanceof ApiError) ElMessage.error(`${error.message}（${error.code}）`)
  } finally {
    rejecting.value = false
  }
}

// ---------- 批量通过 ----------
const batchVisible = ref(false)
const batchComment = ref('')
const batchReason = ref('')
const batchLoading = ref(false)

const selectableItems = computed(() => page.value.items)

const checkedItems = computed(() =>
  selectableItems.value.filter((item) => checkedIds.value.includes(item.id)),
)

function toggleCheck(id: string, checked: boolean): void {
  checkedIds.value = checked
    ? [...checkedIds.value, id]
    : checkedIds.value.filter((item) => item !== id)
}

function toggleCheckAll(checked: boolean): void {
  checkedIds.value = checked ? selectableItems.value.map((item) => item.id) : []
}

function openBatch(): void {
  if (checkedItems.value.length === 0) {
    ElMessage.warning('请先勾选要批量通过的填报')
    return
  }
  batchComment.value = ''
  batchReason.value = ''
  batchVisible.value = true
}

async function confirmBatch(): Promise<void> {
  const submissionIds = checkedItems.value.map((item) => item.id)
  if (submissionIds.length === 0) {
    ElMessage.warning('请先勾选要批量通过的填报')
    return
  }
  batchLoading.value = true
  try {
    const results = await batchReview(
      {
        submissionIds,
        comment: batchComment.value || undefined,
        reason: batchReason.value || undefined,
      },
      newIdempotencyKey(),
    )
    batchVisible.value = false
    const failures = results.filter((item) => !item.success)
    if (failures.length > 0) {
      const lines = failures.map((failure) => `${failure.message}（${failure.code}）`)
      await ElMessageBox.alert(
        `成功 ${results.length - failures.length} 条，失败 ${failures.length} 条：\n${lines.join('\n')}`,
        '批量审核结果',
        { confirmButtonText: '我知道了' },
      )
    } else {
      ElMessage.success(`已批量通过 ${results.length} 条`)
    }
    await loadList()
  } finally {
    batchLoading.value = false
  }
}

// ---------- 材料预览（P09 内嵌右侧面板，UI 规范 7.7） ----------
const previewFileId = ref<string | null>(null)

const previewFile = computed<FileInfo | null>(
  () => detailFiles.value.find((file) => file.id === previewFileId.value) ?? null,
)

function openPreview(file: { id: string }): void {
  previewFileId.value = file.id
}

function closePreview(): void {
  previewFileId.value = null
}

// ---------- 键盘（UI 规范 10.4：↑ / ↓ 上一条 / 下一条；Ctrl + Enter 通过，需二次确认） ----------
function onKeydown(event: KeyboardEvent): void {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    if (detail.value && !approving.value && !rejecting.value) {
      event.preventDefault()
      handleApprove()
    }
    return
  }
  const target = event.target as HTMLElement | null
  if (target && ['INPUT', 'TEXTAREA'].includes(target.tagName)) return
  if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
  const items = page.value.items
  if (items.length === 0) return
  const index = items.findIndex((item) => item.id === selectedId.value)
  const nextIndex =
    event.key === 'ArrowDown'
      ? Math.min(index + 1, items.length - 1)
      : Math.max(index - 1, 0)
  if (nextIndex !== index && nextIndex >= 0) {
    event.preventDefault()
    selectItem(items[nextIndex])
  }
}

// ---------- 跨页定位（UI 规范 10.4：URL 带 submissionId 时选中、滚动定位并高亮 2 秒） ----------
const locatedId = ref<string | null>(null)
const deepLinkMessage = ref<{ type: 'error' | 'warning'; text: string } | null>(null)

async function locateItem(id: string): Promise<void> {
  locatedId.value = id
  await nextTick()
  document
    .querySelector(`[data-submission-id="${id}"]`)
    ?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  window.setTimeout(() => {
    if (locatedId.value === id) locatedId.value = null
  }, 2000)
}

async function locateDeepLinkedSubmission(id: string): Promise<void> {
  deepLinkMessage.value = null
  try {
    const linked = await getSubmission(id)
    if (linked.status !== 'PENDING_REVIEW') {
      deepLinkMessage.value = { type: 'warning', text: '该填报当前不在待审核状态，无需继续审核' }
      return
    }
    const pageSize = page.value.pageSize
    let result = page.value
    let found = result.items.some((item) => item.id === id)
    while (!found && result.page < Math.ceil(result.total / pageSize)) {
      result = await listPendingReviews({
        ...currentQuery(),
        page: result.page + 1,
        pageSize,
      })
      found = result.items.some((item) => item.id === id)
    }
    if (!found) {
      deepLinkMessage.value = { type: 'warning', text: '该填报不在当前账号的待审核列表中，可能已被处理或不在审核权限范围内' }
      return
    }
    page.value = result
    checkedIds.value = []
    selectedId.value = id
    await loadDetail(id)
    await locateItem(id)
  } catch (error) {
    if (error instanceof ApiError && (error.code === 20004 || error.code === 20005)) {
      deepLinkMessage.value = { type: 'error', text: '无权查看或审核该填报，请使用具有相应数据权限的账号' }
    } else if (error instanceof ApiError && error.code === 10003) {
      deepLinkMessage.value = { type: 'error', text: '该填报不存在或已被删除' }
    } else {
      deepLinkMessage.value = {
        type: 'error',
        text: error instanceof ApiError ? error.message : '深链填报加载失败，请稍后重试',
      }
    }
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  const presetId = typeof route.query.submissionId === 'string' ? route.query.submissionId : null
  loadList(false, !presetId)
    .then(async () => {
      if (presetId) await locateDeepLinkedSubmission(presetId)
    })
    .catch(() => {
      if (presetId) deepLinkMessage.value = { type: 'error', text: '待审核列表加载失败，暂时无法定位该填报' }
    })
  void loadIndicatorOptions()
  listOrgUnits().then((list) => (unitOptions.value = list)).catch(() => undefined)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})

useAutoRefresh(() => loadList(true, true, true), { skip: () => reviewFormDirty.value })
</script>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1 class="page-title">审核中心</h1>
        <p class="page-subtitle">待审核 {{ page.total }} 条 · ↑ / ↓ 切换上一条 / 下一条 · Ctrl + Enter 通过</p>
      </div>
    </div>

    <div class="app-action-row batch-actions">
      <el-button
        type="primary"
        :disabled="checkedIds.length === 0"
        @click="openBatch"
      >
        批量通过{{ checkedIds.length > 0 ? `（已选 ${checkedIds.length}）` : '' }}
      </el-button>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="指标">
          <el-select
            v-model="filters.indicatorId"
            clearable
            filterable
            :disabled="!filters.planId"
            popper-class="review-indicator-popper"
            :placeholder="filters.planId ? '全部指标（可按编码或名称搜索）' : '请先选择计划'"
            style="width: 280px"
            @change="applyFilters"
          >
            <el-option-group v-for="group in indicatorGroups" :key="group.name" :label="group.name">
              <el-option
                v-for="option in group.items"
                :key="option.id"
                :label="`${option.code} ${option.name}`"
                :value="option.id"
              >
                <span class="opt-code">{{ option.code }}</span><span class="opt-name" :title="option.name">{{ option.name }}</span>
              </el-option>
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="学院">
          <el-select
            v-model="filters.unitId"
            clearable
            filterable
            placeholder="全部学院"
            style="width: 200px"
            @change="applyFilters"
          >
            <el-option
              v-for="unit in unitOptions"
              :key="unit.id"
              :label="`${unit.code} ${unit.name}`"
              :value="unit.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="提交时间">
          <el-date-picker
            v-model="filters.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            style="width: 240px"
            @change="applyFilters"
          />
        </el-form-item>
        <el-form-item label="关键字">
          <el-input
            v-model="filters.keyword"
            clearable
            placeholder="编号 / 指标名"
            style="width: 160px"
            @clear="applyFilters"
            @keyup.enter="applyFilters"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="applyFilters">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-alert
      v-if="deepLinkMessage"
      :type="deepLinkMessage.type"
      :title="deepLinkMessage.text"
      :closable="false"
      class="deep-link-alert"
    />

    <div class="review-layout" :class="{ 'with-preview': previewFile !== null }">
      <!-- 待审列表 -->
      <el-card shadow="never" class="list-card" v-loading="listLoading">
        <div v-if="selectableItems.length > 0" class="list-head">
          <el-checkbox
            :model-value="checkedIds.length === selectableItems.length"
            :indeterminate="checkedIds.length > 0 && checkedIds.length < selectableItems.length"
            @change="(checked: boolean) => toggleCheckAll(checked)"
          >
            全选
          </el-checkbox>
        </div>
        <ul v-if="page.items.length > 0" class="pending-list">
          <li
            v-for="item in page.items"
            :key="item.id"
            class="pending-item"
            :class="{ 'is-active': item.id === selectedId, 'is-located': item.id === locatedId }"
            :data-submission-id="item.id"
            @click="selectItem(item)"
          >
            <el-checkbox
              :model-value="checkedIds.includes(item.id)"
              @click.stop
              @change="(checked: boolean) => toggleCheck(item.id, checked)"
            />
            <div class="pending-main">
              <div class="pending-line">
                <span class="mono pending-code">{{ item.task?.code ?? '—' }}</span>
                <el-tag size="small" type="warning" effect="light">待审核</el-tag>
              </div>
              <div class="pending-sub">{{ item.unit?.name ?? '—' }}</div>
              <div class="pending-sub">
                {{ formatMetricValue(item.value, item.statCaliber) }}
                · {{ formatRelativeTime(item.submittedAt) }}
                · 材料 {{ item.fileCount ?? 0 }} 份
              </div>
            </div>
          </li>
        </ul>
        <el-empty v-else-if="!listLoading" :image-size="120" description="太棒了，待审核的材料都处理完了" />
        <div v-if="page.total > page.pageSize" class="pager">
          <el-pagination
            :current-page="page.page"
            :page-size="page.pageSize"
            :total="page.total"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="changePageSize"
            @current-change="changePage"
          />
        </div>
      </el-card>

      <!-- 填报详情 + 审核操作 -->
      <el-card shadow="never" class="detail-card" v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <div>
              <div class="detail-title">
                <span class="mono">{{ detail.task?.code ?? '' }}</span>
                {{ (detail.topIndicator ?? detail.indicator)?.name ?? '' }}
                <StatusTag kind="StatCaliber" :value="detail.statCaliber" />
              </div>
              <div class="detail-meta">
                {{ detail.unit?.name ?? '—' }}
                · {{ detail.year }} 年
                · 提交于 {{ formatDateTime(detail.submittedAt) }}
              </div>
            </div>
          </div>

          <h3 class="section-title">本次填报</h3>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="填报值">
              <span class="value-text">{{ valueText }}</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.sourceNote" label="数据来源说明">
              {{ detail.sourceNote }}
            </el-descriptions-item>
            <el-descriptions-item v-if="detailRemarkParts.completionDate" label="完成日期">
              {{ detailRemarkParts.completionDate }}
            </el-descriptions-item>
            <el-descriptions-item label="完成情况说明">
              {{ detailRemarkParts.remark || '—' }}
            </el-descriptions-item>
          </el-descriptions>

          <h3 class="section-title">佐证材料（{{ detailFiles.length }} 份）</h3>
          <ul v-if="detailFiles.length > 0" class="file-list">
            <li v-for="file in detailFiles" :key="file.id" class="file-row">
              <span class="file-dot" :style="{ backgroundColor: FILE_CATEGORY_COLORS[file.category] }" />
              <span class="file-name" :title="file.fileName">{{ file.fileName }}</span>
              <span class="file-size">{{ formatFileSize(file.size) }}</span>
              <el-button link type="primary" @click="openPreview(file)">预览</el-button>
            </li>
          </ul>
          <div v-else class="empty-text">未上传材料</div>

          <template v-if="history.length > 0">
            <h3 class="section-title">历史填报 / 审核记录</h3>
            <SubmissionTimeline :submissions="history" :caliber="caliber" />
          </template>

          <el-divider />

          <h3 class="section-title">审核操作</h3>
            <el-form label-width="110px" class="review-form" @submit.prevent>
            <template v-if="caliber === 'INCREMENT'">
              <el-form-item label="认定值">
                <el-input-number v-model="reviewForm.amount" :min="0" :precision="4" controls-position="right" />
                <span class="field-hint">≤ 填报值 {{ detail.value.amount ?? '—' }}</span>
              </el-form-item>
            </template>
            <template v-else-if="caliber === 'STOCK' || caliber === 'GROWTH'">
              <el-form-item label="认定总量">
                <el-input-number v-model="reviewForm.total" :min="0" :precision="4" controls-position="right" />
                <span class="field-hint">≤ 填报值 {{ detail.value.total ?? '—' }}</span>
              </el-form-item>
            </template>
            <template v-else-if="caliber === 'RATIO'">
              <el-form-item label="认定分子">
                <el-input-number v-model="reviewForm.numerator" :min="0" :precision="4" controls-position="right" />
                <span class="field-hint">≤ {{ detail.value.numerator ?? '—' }}</span>
              </el-form-item>
              <el-form-item label="认定分母">
                <el-input-number v-model="reviewForm.denominator" :min="0" :precision="4" controls-position="right" />
                <span class="field-hint">≤ {{ detail.value.denominator ?? '—' }}</span>
              </el-form-item>
            </template>
            <template v-else-if="caliber === 'DIRECT_PERCENT'">
              <el-form-item label="认定百分比">
                <el-input-number v-model="reviewForm.percent" :min="0" :max="100" :precision="4" controls-position="right" />
                <span class="field-unit">%</span>
                <span class="field-hint">≤ 填报值 {{ detail.value.percent === null || detail.value.percent === undefined ? '—' : formatPercent(detail.value.percent) }}</span>
              </el-form-item>
            </template>

            <el-form-item v-if="reduction !== null" label=" ">
              <span class="reduction-hint">核减 {{ reduction }}，请填写审核意见说明原因</span>
            </el-form-item>

            <el-form-item label="审核意见">
              <el-input
                v-model="reviewForm.comment"
                type="textarea"
                :rows="3"
                maxlength="500"
                show-word-limit
                placeholder="驳回或核减时必填（≤ 500 字）"
              />
            </el-form-item>

            <el-form-item v-if="isDelegateReview" label="代审原因（选填）">
              <el-input
                v-model="reviewForm.reason"
                type="textarea"
                :rows="2"
                maxlength="500"
                show-word-limit
                placeholder="选填，填写后记录到操作日志（≤ 500 字）"
              />
            </el-form-item>

            </el-form>
            <div class="app-action-row review-actions">
              <el-button type="primary" :loading="approving" :disabled="rejecting" @click="handleApprove">
                通过
              </el-button>
              <el-button type="danger" plain :loading="rejecting" :disabled="approving" @click="handleReject">
                驳回
              </el-button>
            </div>
        </template>
        <el-result
          v-else-if="detailLoadError && !detailLoading"
          icon="error"
          title="填报详情加载失败"
          :sub-title="detailLoadError.requestId
            ? `${detailLoadError.message}；请求编号：${detailLoadError.requestId}`
            : detailLoadError.message"
        >
          <template #extra>
            <el-button type="primary" @click="selectedId && loadDetail(selectedId)">重试</el-button>
          </template>
        </el-result>
        <el-empty v-else-if="!detailLoading" :image-size="120" description="从左侧选择一条待审核的填报" />
      </el-card>

      <!-- 材料在线预览面板（P09 内嵌右侧面板，UI 规范 7.7） -->
      <el-card v-if="previewFile" shadow="never" class="preview-card">
        <FilePreviewPane
          :file="previewFile"
          :files="detailFiles"
          @select="openPreview"
          @close="closePreview"
        />
      </el-card>
    </div>

    <!-- 批量通过 -->
    <el-dialog v-model="batchVisible" title="批量通过" width="480px">
      <p class="batch-tip">
        将对勾选的 {{ checkedItems.length }} 条填报执行「通过」（不核减，认定值 = 填报值）：
      </p>
      <ul class="batch-list">
        <li v-for="item in checkedItems" :key="item.id" class="mono">{{ item.task?.code ?? item.id }}</li>
      </ul>
      <el-form label-width="90px">
        <el-form-item label="审核意见">
          <el-input
            v-model="batchComment"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="选填"
          />
        </el-form-item>
        <el-form-item v-if="isDelegateReview" label="代审原因（选填）">
          <el-input
            v-model="batchReason"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="选填，填写后记录到操作日志"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchLoading" @click="confirmBatch">批量通过</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header {
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

.filter-card {
  margin-bottom: var(--space-4);
  border-radius: var(--radius-card);
}

:global(.review-indicator-popper) {
  max-width: 440px;
}

:global(.review-indicator-popper .el-select-dropdown__item) {
  display: flex;
  align-items: center;
  gap: 6px;
}

:global(.review-indicator-popper .opt-code) {
  flex: none;
  color: var(--el-color-primary);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

:global(.review-indicator-popper .opt-name) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.deep-link-alert {
  margin-bottom: var(--space-4);
}

.review-layout {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: var(--space-4);
  align-items: start;
}

/* 内嵌预览面板展开时：待审列表 320px │ 填报详情 │ 预览面板（≥ 50% 剩余宽度，UI 规范 7.7） */
.review-layout.with-preview {
  grid-template-columns: 320px minmax(0, 1fr) minmax(0, 1.2fr);
}

.preview-card {
  border-radius: var(--radius-card);
  height: calc(100vh - 220px);
  min-height: 480px;
  position: sticky;
  top: var(--space-4);
}

.preview-card :deep(.el-card__body) {
  padding: 0;
  height: 100%;
}

@media (max-width: 1200px) {
  .review-layout.with-preview {
    grid-template-columns: 320px 1fr;
  }

  .preview-card {
    grid-column: 1 / -1;
    position: static;
    height: 560px;
  }
}

.list-card {
  border-radius: var(--radius-card);
}

.list-head {
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: var(--space-2);
}

.pending-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  max-height: 640px;
  overflow-y: auto;
}

.pending-item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--el-border-color-lighter);
  border-left: 3px solid transparent;
  border-radius: var(--el-border-radius-base);
  cursor: pointer;
}

.pending-item:hover {
  background: var(--el-color-primary-light-9);
}

.pending-item.is-active {
  border-left-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

/* 跨页定位高亮（UI 规范 10.4）：主色浅底 2 秒渐隐 */
.pending-item.is-located {
  animation: locate-flash 2s ease-out;
}

@keyframes locate-flash {
  0% {
    background-color: var(--el-color-primary-light-7);
  }
  100% {
    background-color: var(--el-color-primary-light-9);
  }
}

.pending-main {
  min-width: 0;
  flex: 1;
}

.pending-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.pending-code {
  font-weight: 600;
  font-size: 13px;
}

.pending-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mono {
  font-family: var(--app-font-mono);
}

.detail-card {
  border-radius: var(--radius-card);
  min-height: 480px;
}

.detail-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: var(--space-3);
}

.detail-title {
  font-size: 16px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.detail-meta {
  margin-top: var(--space-1);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  margin: var(--space-4) 0 var(--space-3);
}

.section-title:first-of-type {
  margin-top: 0;
}

.value-text {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.file-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.file-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
}

.file-dot {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  flex-shrink: 0;
}

.file-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.review-form {
  max-width: 640px;
}

.field-unit {
  margin-left: var(--space-2);
  color: var(--el-text-color-regular);
}

.field-hint {
  margin-left: var(--space-2);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.reduction-hint {
  color: var(--el-color-warning);
  font-size: 13px;
  font-weight: 600;
}

.review-actions {
  display: flex;
  gap: var(--space-2);
}

.empty-text {
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}

.pager {
  margin-top: var(--space-3);
  display: flex;
  justify-content: center;
}

.batch-tip {
  margin: 0 0 var(--space-2);
  font-size: 13px;
}

.batch-list {
  list-style: none;
  margin: 0 0 var(--space-3);
  padding: var(--space-2) var(--space-3);
  max-height: 160px;
  overflow-y: auto;
  background: var(--el-fill-color-light);
  border-radius: var(--el-border-radius-base);
  font-size: 13px;
}

@media (max-width: 768px) {
  .review-layout,
  .review-layout.with-preview {
    grid-template-columns: minmax(0, 1fr);
  }

  .preview-card {
    position: static;
    height: 520px;
    min-height: 0;
  }

  .filter-card :deep(.el-form-item) {
    display: flex;
    margin-right: 0;
  }

  .filter-card :deep(.el-form-item__content),
  .filter-card :deep(.el-select),
  .filter-card :deep(.el-input) {
    width: 100%;
  }

  .batch-actions .el-button {
    width: 100%;
    min-height: 44px;
    margin: 0;
  }

  .review-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    width: 100%;
  }

  .review-actions .el-button {
    width: 100%;
    min-height: 44px;
    margin: 0;
  }
}
</style>
