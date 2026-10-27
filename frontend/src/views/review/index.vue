<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
import FilePreviewPane from '@/components/FilePreviewPane.vue'
import SubmissionTimeline from '@/views/submission/components/SubmissionTimeline.vue'
import { batchReview, listPendingReviews, reviewSubmission } from '@/api/review'
import { getSubmission, listSubmissions } from '@/api/submission'
import { listPlans } from '@/api/plan'
import { listIndicators } from '@/api/indicator'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { useUserStore } from '@/stores/user'
import { newIdempotencyKey } from '@/utils/idempotency'
import { ApiError } from '@/utils/request'
import { formatDateTime, formatPercent, formatRelativeTime } from '@/utils/format'
import { FILE_CATEGORY_COLORS, formatFileSize } from '@/utils/file'
import { percentToDisplay, percentToStore } from '@/utils/allocation'
import {
  approvedReduction,
  buildMetricValue,
  formatMetricValue,
  isApprovedValueValid,
} from '@/utils/submission'
import type { PageResult } from '@/types/api'
import type { PlanCard } from '@/types/plan'
import type { FileInfo } from '@/types/file'
import type { StatCaliber } from '@/types/enums'
import type {
  PendingReviewItem,
  SubmissionDetail,
  SubmissionListItem,
} from '@/types/submission'

const route = useRoute()
const userStore = useUserStore()

/** 管理员代审（非校级负责人）：必须填写代审原因（API 第 10 章敏感操作） */
const isDelegateReview = computed(
  () => userStore.roles.includes('ADMIN') && !userStore.roles.includes('SCHOOL_OWNER'),
)

// ---------- 筛选 ----------
const filters = reactive({
  planId: '',
  indicatorId: '',
  unitId: '',
  keyword: '',
  dateRange: null as [string, string] | null,
})

const planOptions = ref<PlanCard[]>([])
const unitOptions = ref<OrgUnit[]>([])
const indicatorOptions = ref<{ id: string; label: string }[]>([])

async function loadIndicatorOptions(): Promise<void> {
  indicatorOptions.value = []
  filters.indicatorId = ''
  if (!filters.planId) return
  try {
    const tree = await listIndicators(filters.planId)
    const options: { id: string; label: string }[] = []
    for (const node of tree) {
      options.push({ id: node.id, label: `${node.code} ${node.name}` })
      for (const child of node.children ?? []) {
        options.push({ id: child.id, label: `${child.code} ${child.name}` })
      }
    }
    indicatorOptions.value = options
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

async function loadList(keepSelection = false): Promise<void> {
  listLoading.value = true
  try {
    page.value = await listPendingReviews(currentQuery())
    const previous = keepSelection ? selectedId.value : null
    checkedIds.value = []
    const items = page.value.items
    const next = items.find((item) => item.id === previous)?.id ?? items[0]?.id ?? null
    selectedId.value = next
    if (next) {
      await loadDetail(next)
    } else {
      detail.value = null
      history.value = []
    }
  } finally {
    listLoading.value = false
  }
}

function applyFilters(): void {
  page.value.page = 1
  loadList()
}

function resetFilters(): void {
  filters.planId = ''
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
const history = ref<SubmissionListItem[]>([])

const caliber = computed<StatCaliber>(
  () => detail.value?.task?.indicator?.statCaliber ?? 'INCREMENT',
)
const unitLabel = computed(() => detail.value?.task?.indicator?.unitLabel ?? null)

const valueText = computed(() =>
  detail.value ? formatMetricValue(detail.value.value, caliber.value, unitLabel.value) : '—',
)

/** 材料列表（防御后端省略字段） */
const detailFiles = computed(() => detail.value?.files ?? [])
const detailHistorical = computed(() => detail.value?.task?.isHistorical === true)

async function loadDetail(id: string): Promise<void> {
  detailLoading.value = true
  try {
    const data = await getSubmission(id)
    detail.value = data
    initReviewForm(data)
    const taskId = data.task?.id
    history.value = taskId ? await listSubmissions(taskId).catch(() => []) : []
    // 内嵌预览面板（UI 规范 7.7）：有材料时自动预览第一份
    const files = data.files ?? []
    previewFileId.value = files.length > 0 ? files[0].id : null
  } catch {
    detail.value = null
    previewFileId.value = null
  } finally {
    detailLoading.value = false
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
  if (isDelegateReview.value && !reviewForm.reason.trim()) {
    errors.push('管理员代审必须填写代审原因')
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
  if (target.task?.isHistorical) {
    ElMessage.warning('历史任务只读，不能审核')
    return
  }
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
  if (target.task?.isHistorical) {
    ElMessage.warning('历史任务只读，不能审核')
    return
  }
  if (!reviewForm.comment.trim()) {
    ElMessage.error('驳回时必须填写审核意见（60004）')
    return
  }
  if (isDelegateReview.value && !reviewForm.reason.trim()) {
    ElMessage.error('管理员代审必须填写代审原因')
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

const selectableItems = computed(() =>
  page.value.items.filter((item) => item.task?.isHistorical !== true),
)

const checkedItems = computed(() =>
  selectableItems.value.filter((item) => checkedIds.value.includes(item.id)),
)

function toggleCheck(id: string, checked: boolean): void {
  const item = page.value.items.find((entry) => entry.id === id)
  if (item?.task?.isHistorical) return
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
    ElMessage.warning('历史任务只读，没有可批量审核的填报')
    return
  }
  if (isDelegateReview.value && !batchReason.value.trim()) {
    ElMessage.error('管理员代审必须填写代审原因')
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
    if (detail.value && !detailHistorical.value && !approving.value && !rejecting.value) {
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

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  const presetId = typeof route.query.submissionId === 'string' ? route.query.submissionId : null
  loadList().then(() => {
    const target = presetId ? page.value.items.find((item) => item.id === presetId) : undefined
    if (target) {
      selectItem(target)
      locateItem(target.id)
    }
  })
  listPlans().then((list) => (planOptions.value = list)).catch(() => undefined)
  listOrgUnits().then((list) => (unitOptions.value = list)).catch(() => undefined)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1 class="page-title">审核中心</h1>
        <p class="page-subtitle">待审核 {{ page.total }} 条 · ↑ / ↓ 切换上一条 / 下一条 · Ctrl + Enter 通过</p>
      </div>
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
        <el-form-item label="计划">
          <el-select
            v-model="filters.planId"
            clearable
            placeholder="全部计划"
            style="width: 200px"
            @change="loadIndicatorOptions(); applyFilters()"
          >
            <el-option v-for="plan in planOptions" :key="plan.id" :label="plan.name" :value="plan.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="指标">
          <el-select
            v-model="filters.indicatorId"
            clearable
            filterable
            :disabled="!filters.planId"
            :placeholder="filters.planId ? '全部指标' : '请先选择计划'"
            style="width: 220px"
            @change="applyFilters"
          >
            <el-option
              v-for="option in indicatorOptions"
              :key="option.id"
              :label="option.label"
              :value="option.id"
            />
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
              :disabled="item.task?.isHistorical === true"
              @click.stop
              @change="(checked: boolean) => toggleCheck(item.id, checked)"
            />
            <div class="pending-main">
              <div class="pending-line">
                <span class="mono pending-code">{{ item.task?.code ?? '—' }}</span>
                <el-tag size="small" type="warning" effect="light">待审核</el-tag>
                <el-tag v-if="item.task?.isHistorical" size="small" type="info" effect="plain">历史只读</el-tag>
              </div>
              <div class="pending-sub">{{ item.task?.unit?.name ?? '—' }}</div>
              <div class="pending-sub">
                {{ formatMetricValue(item.value, item.task?.indicator?.statCaliber ?? 'INCREMENT', item.task?.indicator?.unitLabel) }}
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
                {{ detail.task?.indicator?.name ?? '' }}
                <StatusTag
                  v-if="detail.task?.indicator?.statCaliber"
                  kind="StatCaliber"
                  :value="detail.task.indicator.statCaliber"
                />
                <el-tag v-if="detailHistorical" type="info" size="small" effect="plain">历史只读</el-tag>
              </div>
              <div class="detail-meta">
                {{ detail.task?.unit?.name ?? '—' }}
                · {{ detail.task?.owner?.name ?? '—' }}
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
            <el-descriptions-item label="完成情况说明">
              {{ detail.remark ?? '—' }}
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
            <SubmissionTimeline :submissions="history" :caliber="caliber" :unit-label="unitLabel" />
          </template>

          <el-divider />

          <template v-if="!detailHistorical">
            <h3 class="section-title">审核操作</h3>
            <el-form label-width="110px" class="review-form" @submit.prevent>
            <template v-if="caliber === 'INCREMENT'">
              <el-form-item label="认定值">
                <el-input-number v-model="reviewForm.amount" :min="0" :precision="0" controls-position="right" />
                <span v-if="unitLabel" class="field-unit">{{ unitLabel }}</span>
                <span class="field-hint">≤ 填报值 {{ detail.value.amount ?? '—' }}</span>
              </el-form-item>
            </template>
            <template v-else-if="caliber === 'STOCK' || caliber === 'GROWTH'">
              <el-form-item label="认定总量">
                <el-input-number v-model="reviewForm.total" :min="0" :precision="4" controls-position="right" />
                <span v-if="unitLabel" class="field-unit">{{ unitLabel }}</span>
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
                <el-input-number v-model="reviewForm.percent" :min="0" :max="100" :precision="1" controls-position="right" />
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

            <el-form-item v-if="isDelegateReview" label="代审原因" required>
              <el-input
                v-model="reviewForm.reason"
                type="textarea"
                :rows="2"
                maxlength="500"
                show-word-limit
                placeholder="管理员代审属于敏感操作，必须填写原因（≤ 500 字）"
              />
            </el-form-item>

            <el-form-item label=" ">
              <div class="review-actions">
                <el-button type="danger" :loading="rejecting" :disabled="approving" @click="handleReject">
                  驳回
                </el-button>
                <el-button type="success" :loading="approving" :disabled="rejecting" @click="handleApprove">
                  通过
                </el-button>
              </div>
            </el-form-item>
            </el-form>
          </template>
          <el-alert
            v-else
            type="info"
            :closable="false"
            title="历史任务只读，审核操作已停用；填报内容、材料和审核记录仍可查看"
          />
        </template>
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
        <el-form-item v-if="isDelegateReview" label="代审原因" required>
          <el-input
            v-model="batchReason"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="管理员代审必须填写原因"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="success" :loading="batchLoading" @click="confirmBatch">批量通过</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
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

.filter-card {
  margin-bottom: var(--space-4);
  border-radius: var(--radius-card);
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
</style>
