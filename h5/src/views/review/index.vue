<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { showDialog, showToast } from 'vant'
import StatusTag from '@/components/StatusTag.vue'
import PageState from '@/components/PageState.vue'
import PlanSwitcher from '@/components/PlanSwitcher.vue'
import MetricValueInput from '@/components/MetricValueInput.vue'
import AttachmentList from '@/components/AttachmentList.vue'
import SubmissionReviewTimeline from '@/components/SubmissionReviewTimeline.vue'
import { batchReview, listPendingReviews, reviewSubmission } from '@/api/review'
import { getSubmission, listSubmissions } from '@/api/submission'
import { listIndicators } from '@/api/indicator'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { listPlans } from '@/api/plan'
import { useUserStore } from '@/stores/user'
import { newIdempotencyKey } from '@/utils/idempotency'
import { getCurrentPlan } from '@/utils/currentPlan'
import { ApiError } from '@/utils/request'
import { formatDateTime } from '@/utils/format'
import { enumOptions } from '@/utils/enumLabels'
import { canReviewSubmission, initialReviewValue } from '@/utils/review'
import type { ReviewNodeTarget } from '@/utils/review'
import {
  approvedReduction,
  buildMetricValue,
  formatMetricValue,
  isApprovedValueValid,
  parseSubmissionRemark,
} from '@/utils/submission'
import type { PageResult } from '@/types/api'
import type { ReviewNode, StatCaliber } from '@/types/enums'
import type { MetricValue, PendingReviewItem, SubmissionDetail, SubmissionListItem } from '@/types/submission'
import type { PlanCard } from '@/types/plan'

const route = useRoute()
const userStore = useUserStore()

const isDelegateReview = computed(
  () => userStore.roles.includes('ADMIN') && !userStore.roles.includes('SCHOOL_OWNER'),
)

const plans = ref<PlanCard[]>([])
const unitOptions = ref<OrgUnit[]>([])
const indicatorOptions = ref<{ id: string; code: string; name: string; directionName: string | null }[]>([])
const indicatorGroups = computed(() => {
  const groups = new Map<string, typeof indicatorOptions.value>()
  for (const option of indicatorOptions.value) {
    const name = option.directionName || '未归入建设方向'
    const list = groups.get(name) ?? []
    list.push(option)
    groups.set(name, list)
  }
  return [...groups.entries()].map(([name, items]) => ({ name, items }))
})

const filters = reactive({
  planId: (typeof route.query.planId === 'string' ? route.query.planId : '') || getCurrentPlan()?.id || '',
  indicatorId: '',
  unitId: '',
  reviewNode: (route.query.reviewNode === 'UNIT_REVIEW' || route.query.reviewNode === 'LEADER_REVIEW'
    ? route.query.reviewNode
    : '') as '' | ReviewNode,
  keyword: '',
  dateRange: null as [string, string] | null,
})
const reviewNodeOptions = computed(() => enumOptions('ReviewNode', userStore.enums))

const indicatorPickerVisible = ref(false)
const unitPickerVisible = ref(false)
const calendarVisible = ref(false)
const indicatorKeyword = ref('')

const filteredIndicatorGroups = computed(() => {
  const kw = indicatorKeyword.value.trim().toLowerCase()
  if (!kw) return indicatorGroups.value
  return indicatorGroups.value
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => `${item.code} ${item.name}`.toLowerCase().includes(kw)),
    }))
    .filter((group) => group.items.length > 0)
})

const indicatorLabel = computed(() => {
  const item = indicatorOptions.value.find((option) => option.id === filters.indicatorId)
  return item ? `${item.code} ${item.name}` : '全部指标'
})
const unitLabel = computed(() => {
  const item = unitOptions.value.find((option) => option.id === filters.unitId)
  return item ? item.name : '全部单元'
})
const dateLabel = computed(() =>
  filters.dateRange ? `${filters.dateRange[0]} 至 ${filters.dateRange[1]}` : '时间范围',
)

async function loadPlans(): Promise<void> {
  try {
    plans.value = await listPlans()
  } catch {
    // 拦截器已提示
  }
}

async function loadIndicatorOptions(): Promise<void> {
  indicatorOptions.value = []
  filters.indicatorId = ''
  if (!filters.planId) return
  try {
    const tree = await listIndicators(filters.planId)
    indicatorOptions.value = tree.map((node) => ({
      id: node.id,
      code: node.code,
      name: node.name,
      directionName: node.directionName ?? null,
    }))
  } catch {
    // 拦截器已提示
  }
}

function onPlanChange(plan: { id: string }) {
  filters.planId = plan.id
  void loadIndicatorOptions()
  applyFilters()
}

// 列表
const listLoading = ref(false)
const page = ref<PageResult<PendingReviewItem>>({ items: [], total: 0, page: 1, pageSize: 20 })
const checkedIds = ref<string[]>([])
const selectedId = ref<string | null>(null)

function canOperate(item: ReviewNodeTarget): boolean {
  return userStore.can('submission:review') && canReviewSubmission(userStore.roles, item)
}

function reviewGuardMessage(item: ReviewNodeTarget): string {
  if (item.status !== 'PENDING_REVIEW') return '该填报当前不在待审核状态'
  if (item.currentReviewNode === 'UNIT_REVIEW') return '当前为学院负责人审核节点，仅任务负责人或管理员可处理'
  if (item.currentReviewNode === 'LEADER_REVIEW') return '当前为校级负责人审核节点，仅校级负责人或管理员可处理'
  return '当前填报缺少有效审核节点，暂不能处理'
}

const operablePageItems = computed(() => page.value.items.filter(canOperate))

function currentQuery() {
  const [from, to] = filters.dateRange ?? [undefined, undefined]
  return {
    planId: filters.planId || undefined,
    indicatorId: filters.indicatorId || undefined,
    unitId: filters.unitId || undefined,
    reviewNode: filters.reviewNode || undefined,
    keyword: filters.keyword || undefined,
    submittedAtFrom: from ? `${from}T00:00:00+08:00` : undefined,
    submittedAtTo: to ? `${to}T23:59:59+08:00` : undefined,
    page: page.value.page,
    pageSize: page.value.pageSize,
  }
}

async function loadList(autoSelect = false): Promise<void> {
  listLoading.value = true
  try {
    const result = await listPendingReviews(currentQuery())
    page.value = result
    checkedIds.value = []
    const next = autoSelect ? (result.items[0]?.id ?? null) : null
    selectedId.value = next
    if (next) await loadDetail(next)
    else {
      detail.value = null
      history.value = []
      detailVisible.value = false
    }
  } finally {
    listLoading.value = false
  }
}

function applyFilters(): void {
  page.value.page = 1
  selectedId.value = null
  void loadList()
}

function chooseReviewNode(value: string): void {
  filters.reviewNode = value === 'UNIT_REVIEW' || value === 'LEADER_REVIEW' ? value : ''
  applyFilters()
}

function resetFilters(): void {
  filters.indicatorId = ''
  filters.unitId = ''
  filters.reviewNode = ''
  filters.keyword = ''
  filters.dateRange = null
  applyFilters()
}

function changePage(next: number): void {
  page.value.page = next
  void loadList(false)
}

// 详情
const detail = ref<SubmissionDetail | null>(null)
const detailVisible = ref(false)
const detailLoading = ref(false)
const history = ref<SubmissionListItem[]>([])
const caliber = computed<StatCaliber>(() => detail.value?.statCaliber ?? 'INCREMENT')
const valueText = computed(() =>
  detail.value ? formatMetricValue(detail.value.value, caliber.value) : '—',
)
const remarkParts = computed(() => parseSubmissionRemark(detail.value?.remark))
const detailFiles = computed(() => detail.value?.files ?? [])
const canOperateDetail = computed(() => detail.value ? canOperate(detail.value) : false)
const currentNodeHint = computed(() => {
  const item = detail.value
  if (!item) return ''
  if (item.currentReviewNode === 'UNIT_REVIEW') {
    return '学院负责人初审通过后转校级负责人终审，暂不认定完成值。'
  }
  if (item.currentReviewNode === 'LEADER_REVIEW') {
    return item.approvedValue
      ? '校级负责人终审默认继承院审认定值，可继续核减；终审通过后计入完成值。'
      : '校级负责人终审通过后计入完成值。'
  }
  return ''
})

async function loadDetail(id: string): Promise<void> {
  detailLoadSequence += 1
  const sequence = detailLoadSequence
  detailLoading.value = true
  try {
    const data = await getSubmission(id)
    if (sequence !== detailLoadSequence) return
    detail.value = data
    initReviewForm(data)
    detailVisible.value = true
    const taskId = data.task?.id
    history.value = taskId ? await listSubmissions(taskId) : []
  } catch {
    if (sequence === detailLoadSequence) detail.value = null
  } finally {
    if (sequence === detailLoadSequence) detailLoading.value = false
  }
}
let detailLoadSequence = 0

function openItem(item: PendingReviewItem): void {
  selectedId.value = item.id
  void loadDetail(item.id)
}

// 审核表单
const reviewValue = ref<MetricValue>({})
const comment = ref('')
const reviewReason = ref('')
const approving = ref(false)
const rejecting = ref(false)

function initReviewForm(data: SubmissionDetail): void {
  reviewValue.value = initialReviewValue(data)
  comment.value = ''
  reviewReason.value = ''
}

const reviewBaseline = computed(() => detail.value ? initialReviewValue(detail.value) : {})
const approvedValue = computed(() =>
  caliber.value === 'BOOLEAN'
    ? buildMetricValue(caliber.value, { achieved: reviewBaseline.value.achieved ?? null })
    : buildMetricValue(caliber.value, reviewValue.value),
)
const reduction = computed(() =>
  detail.value ? approvedReduction(caliber.value, reviewBaseline.value, approvedValue.value) : null,
)

function validateApproved(): string[] {
  const errors: string[] = []
  if (!detail.value) return errors
  if (caliber.value !== 'BOOLEAN'
    && !isApprovedValueValid(caliber.value, reviewBaseline.value, approvedValue.value)) {
    errors.push('认定值不能大于本级认定值（60005）')
  }
  if (reduction.value !== null && !comment.value.trim()) {
    errors.push('核减认定值时必须填写审核意见')
  }
  return errors
}

async function handleApprove(): Promise<void> {
  const target = detail.value
  if (!target) return
  if (!canOperate(target)) {
    showToast(reviewGuardMessage(target))
    return
  }
  const errors = validateApproved()
  if (errors.length > 0) {
    showToast(errors[0])
    return
  }
  const isUnitReview = target.currentReviewNode === 'UNIT_REVIEW'
  const reductionText = reduction.value !== null ? `，本级认定值核减 ${reduction.value}` : ''
  try {
    const { showConfirmDialog } = await import('vant')
    await showConfirmDialog({
      title: isUnitReview ? '通过院审' : '通过校审',
      message: `确认通过「${target.task?.code ?? ''}」的填报${reductionText}？${isUnitReview ? '院审通过后转校级负责人终审，暂不认定完成值。' : '终审通过后计入完成值。'}`,
      confirmButtonText: '确认通过',
    })
  } catch {
    return
  }
  approving.value = true
  try {
    const body = caliber.value === 'BOOLEAN'
      ? {
          result: 'APPROVED' as const,
          comment: comment.value || undefined,
          version: target.version,
          reason: reviewReason.value || undefined,
        }
      : {
          result: 'APPROVED' as const,
          comment: comment.value || undefined,
          approvedValue: approvedValue.value,
          version: target.version,
          reason: reviewReason.value || undefined,
        }
    await reviewSubmission(target.id, body, newIdempotencyKey())
    showToast(isUnitReview
      ? '院审已通过，待校级负责人审核'
      : reduction.value !== null ? `校审已通过（核减 ${reduction.value}）` : '校审已通过')
    detailVisible.value = false
    await loadList()
  } catch (error) {
    if (error instanceof ApiError) showToast(`${error.message}（${error.code}）`)
  } finally {
    approving.value = false
  }
}

async function handleReject(): Promise<void> {
  const target = detail.value
  if (!target) return
  if (!canOperate(target)) {
    showToast(reviewGuardMessage(target))
    return
  }
  if (!comment.value.trim()) {
    showToast('驳回时必须填写审核意见（60004）')
    return
  }
  try {
    const { showConfirmDialog } = await import('vant')
    await showConfirmDialog({
      title: target.currentReviewNode === 'UNIT_REVIEW' ? '院审驳回' : '校审驳回',
      message: `确认驳回「${target.task?.code ?? ''}」的填报？驳回后学院需修改并重新走审核链。`,
      confirmButtonText: '确认驳回',
    })
  } catch {
    return
  }
  rejecting.value = true
  try {
    await reviewSubmission(
      target.id,
      {
        result: 'REJECTED',
        comment: comment.value,
        version: target.version,
        reason: reviewReason.value || undefined,
      },
      newIdempotencyKey(),
    )
    showToast('已驳回')
    detailVisible.value = false
    await loadList()
  } catch (error) {
    if (error instanceof ApiError) showToast(`${error.message}（${error.code}）`)
  } finally {
    rejecting.value = false
  }
}

// 批量
const checkedItems = computed(() => page.value.items.filter((item) => checkedIds.value.includes(item.id) && canOperate(item)))
function toggleCheck(id: string, checked: boolean): void {
  const target = page.value.items.find((item) => item.id === id)
  if (checked && target && !canOperate(target)) {
    showToast(reviewGuardMessage(target))
    return
  }
  checkedIds.value = checked
    ? [...checkedIds.value, id]
    : checkedIds.value.filter((item) => item !== id)
}
function toggleCheckAll(checked: boolean): void {
  checkedIds.value = checked ? operablePageItems.value.map((item) => item.id) : []
}
const batchNodeSummary = computed(() => {
  const unitCount = checkedItems.value.filter((item) => item.currentReviewNode === 'UNIT_REVIEW').length
  const leaderCount = checkedItems.value.filter((item) => item.currentReviewNode === 'LEADER_REVIEW').length
  return [unitCount ? `院审 ${unitCount} 条` : '', leaderCount ? `校审 ${leaderCount} 条` : '']
    .filter(Boolean)
    .join(' · ')
})
const batchVisible = ref(false)
const batchComment = ref('')
const batchReason = ref('')
const batchLoading = ref(false)

function openBatch(): void {
  if (checkedItems.value.length === 0) {
    showToast(checkedIds.value.length > 0 ? '勾选内容中没有当前账号可处理的节点' : '请先勾选要批量通过的填报')
    return
  }
  batchComment.value = ''
  batchReason.value = ''
  batchVisible.value = true
}

async function confirmBatch(): Promise<void> {
  const targets = page.value.items.filter((item) => checkedIds.value.includes(item.id))
  const blocked = targets.find((item) => !canOperate(item))
  if (blocked) {
    showToast(reviewGuardMessage(blocked))
    return
  }
  const ids = targets.map((item) => item.id)
  if (ids.length === 0) return
  batchLoading.value = true
  try {
    const results = await batchReview(
      { submissionIds: ids, comment: batchComment.value || undefined, reason: batchReason.value || undefined },
      newIdempotencyKey(),
    )
    batchVisible.value = false
    const failures = results.filter((item) => !item.success)
    if (failures.length > 0) {
      await showDialog({
        title: '批量审核结果',
        message: `成功 ${results.length - failures.length} 条，失败 ${failures.length} 条：\n${failures.map((f) => `${f.message}（${f.code}）`).join('\n')}`,
      })
    } else {
      showToast(`已完成 ${results.length} 条当前节点审核`)
    }
    await loadList()
  } finally {
    batchLoading.value = false
  }
}

// 深链
async function handleDeepLink(id: string): Promise<void> {
  try {
    const linked = await getSubmission(id)
    if (linked.status !== 'PENDING_REVIEW') {
      showToast('该填报当前不在待审核状态，无需继续审核')
      return
    }
    if (!canOperate(linked)) showToast(reviewGuardMessage(linked))
    selectedId.value = id
    await loadDetail(id)
  } catch {
    // 拦截器已提示
  }
}

onMounted(async () => {
  await Promise.all([loadPlans(), (async () => {
    if (!userStore.isUnitOwnerOnly) {
      unitOptions.value = await listOrgUnits()
    }
  })()])
  await loadIndicatorOptions()
  await loadList()
  const deepLinkId = typeof route.query.submissionId === 'string' ? route.query.submissionId : ''
  if (deepLinkId) await handleDeepLink(deepLinkId)
})

const planSwitcherPlans = computed(() => plans.value)
</script>

<template>
  <div class="page" :class="{ 'page--footer': checkedIds.length > 0 }">
    <div class="page-head">
      <h1>审核中心</h1>
      <p class="muted small">待审核填报的统一处理；支持核减认定值与批量通过</p>
    </div>

    <PlanSwitcher
      v-if="planSwitcherPlans.length > 0"
      :model-value="filters.planId"
      :plans="planSwitcherPlans"
      @change="onPlanChange"
    />

    <div class="filter-scroll review-node-filter" aria-label="审核节点筛选">
      <button
        type="button"
        class="filter-chip"
        :class="{ 'is-active': filters.reviewNode === '' }"
        @click="chooseReviewNode('')"
      >全部节点</button>
      <button
        v-for="option in reviewNodeOptions"
        :key="option.value"
        type="button"
        class="filter-chip"
        :class="{ 'is-active': filters.reviewNode === option.value }"
        @click="chooseReviewNode(option.value)"
      >待{{ option.label }}</button>
    </div>

    <div class="filter-grid">
      <button type="button" class="filter-btn" @click="indicatorPickerVisible = true">
        <span class="ellipsis">{{ indicatorLabel }}</span>
        <van-icon name="arrow-down" size="11" />
      </button>
      <button v-if="!userStore.isUnitOwnerOnly" type="button" class="filter-btn" @click="unitPickerVisible = true">
        <span class="ellipsis">{{ unitLabel }}</span>
        <van-icon name="arrow-down" size="11" />
      </button>
      <button type="button" class="filter-btn" @click="calendarVisible = true">
        <span class="ellipsis">{{ dateLabel }}</span>
        <van-icon name="calendar-o" size="13" />
      </button>
    </div>

    <div class="filter-line">
      <van-search v-model="filters.keyword" placeholder="任务编号 / 指标名称" shape="round" @search="applyFilters" @clear="applyFilters" />
      <van-button size="small" type="primary" @click="applyFilters">查询</van-button>
    </div>
    <button type="button" class="reset-link" @click="resetFilters">重置筛选</button>

    <div class="list-toolbar">
      <span class="small muted">待审核 {{ page.total }} 条</span>
      <div class="row">
        <van-checkbox
          :model-value="checkedItems.length === operablePageItems.length && operablePageItems.length > 0"
          :disabled="operablePageItems.length === 0"
          shape="square"
          @update:model-value="toggleCheckAll"
        >全选可审</van-checkbox>
        <van-button size="mini" type="primary" :disabled="checkedItems.length === 0" @click="openBatch">
          批量通过{{ checkedItems.length ? `（${checkedItems.length}）` : '' }}
        </van-button>
      </div>
    </div>

    <PageState :loading="listLoading" :empty="page.items.length === 0" empty-text="当前没有待审核的填报">
      <div class="review-list">
        <article
          v-for="item in page.items"
          :key="item.id"
          class="review-card"
          :class="{ 'is-active': selectedId === item.id }"
          :data-submission-id="item.id"
          @click="openItem(item)"
        >
          <div class="row--between">
            <div class="row">
              <van-checkbox
                :model-value="checkedIds.includes(item.id)"
                :disabled="!canOperate(item)"
                shape="square"
                @click.stop
                @update:model-value="(checked: boolean) => toggleCheck(item.id, checked)"
              />
              <strong>{{ item.task?.code ?? '—' }}</strong>
            </div>
            <StatusTag kind="SubmissionStatus" :value="item.status" :review-node="item.currentReviewNode" />
          </div>
          <div class="small ellipsis-2">{{ item.topIndicator?.name ?? item.indicator?.name ?? '—' }}</div>
          <div class="small muted">{{ item.unit?.name ?? '—' }} · {{ item.year }} 年 · {{ formatDateTime(item.submittedAt ?? item.createdAt) }}</div>
          <div class="row--between review-values">
            <span class="small">填报值</span>
            <strong>{{ formatMetricValue(item.value, item.statCaliber, item.topIndicator?.name ? undefined : undefined) }}</strong>
          </div>
        </article>
      </div>
      <div class="pager">
        <van-button size="small" :disabled="page.page <= 1" @click="changePage(page.page - 1)">上一页</van-button>
        <span class="small muted">{{ page.page }} / {{ Math.max(1, Math.ceil(page.total / page.pageSize)) }}</span>
        <van-button
          size="small"
          :disabled="page.page >= Math.ceil(page.total / page.pageSize)"
          @click="changePage(page.page + 1)"
        >下一页</van-button>
      </div>
    </PageState>

    <!-- 审核详情 -->
    <van-popup v-model:show="detailVisible" position="bottom" round :style="{ height: '92vh' }">
      <div class="detail-sheet">
        <div class="sheet-head">
          <strong>审核详情</strong>
          <button type="button" class="close-btn" @click="detailVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="detail-body">
          <div v-if="detailLoading && !detail" class="skeleton-block">加载详情…</div>
          <template v-else-if="detail">
            <div class="card card--tight">
              <div class="row--between">
                <strong>{{ detail.task?.code ?? '—' }}</strong>
                <StatusTag kind="SubmissionStatus" :value="detail.status" :review-node="detail.currentReviewNode" />
              </div>
              <div class="small ellipsis-2">{{ detail.topIndicator?.name ?? detail.indicator?.name ?? '—' }}</div>
              <div class="small muted">{{ detail.unit?.name ?? '—' }} · {{ detail.year }} 年</div>
              <div class="kv-list review-kv">
                <div class="kv"><span class="kv__label">填报值</span><span class="kv__value">{{ valueText }}</span></div>
                <div class="kv"><span class="kv__label">提交时间</span><span class="kv__value">{{ formatDateTime(detail.submittedAt ?? detail.createdAt) }}</span></div>
                <div v-if="detail.sourceNote" class="kv"><span class="kv__label">来源说明</span><span class="kv__value">{{ detail.sourceNote }}</span></div>
                <div v-if="remarkParts.completionDate" class="kv"><span class="kv__label">完成日期</span><span class="kv__value">{{ remarkParts.completionDate }}</span></div>
                <div class="kv"><span class="kv__label">情况说明</span><span class="kv__value">{{ remarkParts.remark || '—' }}</span></div>
              </div>
            </div>

            <div class="card card--tight">
              <div class="section-title">佐证材料（{{ detailFiles.length }} 份）</div>
              <AttachmentList :files="detailFiles" empty-text="本次提交未上传材料" />
            </div>

            <div v-if="detail.reviews.length > 0" class="card card--tight">
              <div class="section-title">审核记录</div>
              <SubmissionReviewTimeline :reviews="detail.reviews" :caliber="caliber" />
            </div>

            <div class="card card--tight">
              <div class="section-title">当前节点处理</div>
              <p v-if="currentNodeHint" class="small muted node-hint">{{ currentNodeHint }}</p>
              <template v-if="canOperateDetail">
                <template v-if="caliber !== 'BOOLEAN'">
                  <p class="small muted">可在本级核减认定值，认定值不能大于本级认定值（60005）。</p>
                  <MetricValueInput v-model="reviewValue" :caliber="caliber" />
                </template>
                <p v-else class="small muted">是否型只做通过 / 驳回，认定值与填报值一致。</p>

                <div class="field">
                  <label>审核意见{{ reduction !== null ? '（核减必填）' : '' }}</label>
                  <van-field v-model="comment" type="textarea" rows="2" maxlength="500" show-word-limit placeholder="驳回或核减时必须填写意见" />
                </div>
                <div v-if="isDelegateReview" class="field">
                  <label>代审原因</label>
                  <van-field v-model="reviewReason" type="textarea" rows="2" maxlength="500" show-word-limit placeholder="管理员代审原因（选填，写入审计日志）" />
                </div>
                <p v-if="reduction !== null" class="small text-warning">本级核减量：{{ reduction }}</p>
              </template>
              <p v-else class="small text-warning guard-text">{{ reviewGuardMessage(detail) }}</p>
            </div>

            <div class="card card--tight">
              <div class="section-title">填报记录</div>
              <div v-if="history.length === 0" class="empty-state">暂无历史记录</div>
              <div v-else class="history-list">
                <div v-for="item in history" :key="item.id" class="history-item">
                  <div class="row--between">
                    <span class="small">{{ formatDateTime(item.submittedAt ?? item.createdAt) }}</span>
                    <StatusTag kind="SubmissionStatus" :value="item.status" :review-node="item.currentReviewNode" />
                  </div>
                  <div class="small">{{ formatMetricValue(item.value, caliber) }}</div>
                </div>
              </div>
            </div>
          </template>
        </div>
        <div v-if="canOperateDetail" class="detail-footer">
          <van-button block type="danger" plain :loading="rejecting" @click="handleReject">
            {{ detail?.currentReviewNode === 'UNIT_REVIEW' ? '院审驳回' : '校审驳回' }}
          </van-button>
          <van-button block type="primary" :loading="approving" @click="handleApprove">
            {{ detail?.currentReviewNode === 'UNIT_REVIEW' ? '院审通过' : '校审通过' }}
          </van-button>
        </div>
      </div>
    </van-popup>

    <!-- 筛选弹层 -->
    <van-popup v-model:show="indicatorPickerVisible" position="bottom" round :style="{ height: '72vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>选择指标</strong>
          <button type="button" class="close-btn" @click="indicatorPickerVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <van-search v-model="indicatorKeyword" placeholder="按编码或名称搜索" shape="round" />
        <div class="picker-list">
          <button type="button" class="picker-item" @click="filters.indicatorId = ''; indicatorPickerVisible = false; applyFilters()">
            全部指标
          </button>
          <div v-for="group in filteredIndicatorGroups" :key="group.name">
            <div class="direction-title">{{ group.name }}</div>
            <button
              v-for="item in group.items"
              :key="item.id"
              type="button"
              class="picker-item"
              @click="filters.indicatorId = item.id; indicatorPickerVisible = false; applyFilters()"
            >
              <span class="code">{{ item.code }}</span> {{ item.name }}
            </button>
          </div>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="unitPickerVisible" position="bottom" round :style="{ height: '65vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>选择执行单元</strong>
          <button type="button" class="close-btn" @click="unitPickerVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="picker-list">
          <button type="button" class="picker-item" @click="filters.unitId = ''; unitPickerVisible = false; applyFilters()">
            全部单元
          </button>
          <button
            v-for="unit in unitOptions"
            :key="unit.id"
            type="button"
            class="picker-item"
            @click="filters.unitId = unit.id; unitPickerVisible = false; applyFilters()"
          >{{ unit.code }} {{ unit.name }}</button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="calendarVisible" position="bottom" round>
      <van-calendar
        type="range"
        :min-date="new Date(2020, 0, 1)"
        :max-date="new Date(2100, 11, 31)"
        @confirm="(dates: Date[]) => {
          filters.dateRange = dates.map((date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`) as [string, string]
          calendarVisible = false
          applyFilters()
        }"
        @cancel="calendarVisible = false"
      />
    </van-popup>

    <van-popup v-model:show="batchVisible" position="bottom" round safe-area-inset-bottom>
      <div class="picker-sheet simple">
        <div class="sheet-head">
          <strong>批量通过（{{ checkedItems.length }} 条）</strong>
          <button type="button" class="close-btn" @click="batchVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <p class="small muted batch-node-summary">
          {{ batchNodeSummary }}。院审通过后转校审，校审通过后计入完成值；校审自动继承院审认定值。
        </p>
        <div class="field">
          <label>批量审核意见</label>
          <van-field v-model="batchComment" type="textarea" rows="2" maxlength="500" show-word-limit />
        </div>
        <div v-if="isDelegateReview" class="field">
          <label>代审原因</label>
          <van-field v-model="batchReason" type="textarea" rows="2" maxlength="500" show-word-limit />
        </div>
        <div class="sheet-actions">
          <van-button block @click="batchVisible = false">取消</van-button>
          <van-button block type="primary" :loading="batchLoading" @click="confirmBatch">确认通过</van-button>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.page-head {
  margin: 2px 0 10px;
}
.page-head h1 {
  margin: 0;
  font-size: 22px;
}
.page-head p {
  margin: 3px 0 0;
}
.review-node-filter {
  max-width: 100%;
  margin-top: 4px;
}
.filter-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 8px;
}
.filter-btn {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: #fff;
  color: var(--text);
  font-size: 12px;
  min-width: 0;
}
.filter-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}
.reset-link {
  display: block;
  min-height: 28px;
  margin-left: auto;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
}
.list-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 4px 0 8px;
}
.review-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.review-card {
  min-width: 0;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
  overflow-wrap: anywhere;
}
.review-card .row--between > *,
.review-card .row {
  min-width: 0;
}
.review-card.is-active {
  border-color: var(--brand);
}
.review-values {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed var(--line);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 12px 0;
}
.detail-sheet {
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100%;
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
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
.detail-body {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 10px 12px;
}
.node-hint,
.guard-text,
.batch-node-summary {
  margin: 0 0 8px;
  line-height: 18px;
  overflow-wrap: anywhere;
}
.detail-footer {
  display: flex;
  gap: 10px;
  padding: 10px 14px calc(10px + var(--safe-bottom));
  border-top: 1px solid var(--line);
}
.review-kv {
  margin-top: 8px;
}
.history-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.history-item {
  padding: 8px;
  border-radius: 8px;
  background: #f8fafd;
}
.field {
  margin-top: 10px;
}
.field label {
  display: block;
  margin-bottom: 4px;
  color: var(--text-secondary);
  font-size: 13px;
}
.field :deep(.van-field) {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  padding: 10px 12px;
}
.picker-sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 14px 14px calc(14px + var(--safe-bottom));
}
.picker-sheet.simple {
  height: auto;
}
.picker-list {
  flex: 1;
  overflow-y: auto;
  margin-top: 6px;
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
.direction-title {
  padding: 8px 4px 4px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
}
.code {
  color: var(--brand);
  font-family: Consolas, Menlo, monospace;
  font-weight: 600;
}
.sheet-actions {
  display: flex;
  gap: 10px;
  margin-top: 12px;
}
</style>
