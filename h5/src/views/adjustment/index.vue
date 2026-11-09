<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import StatusTag from '@/components/StatusTag.vue'
import PageState from '@/components/PageState.vue'
import AttachmentList from '@/components/AttachmentList.vue'
import {
  decideAdjustment,
  getAdjustment,
  listAdjustments,
  withdrawAdjustment,
} from '@/api/adjustment'
import { useUserStore } from '@/stores/user'
import { newIdempotencyKey } from '@/utils/idempotency'
import { ApiError } from '@/utils/request'
import { enumLabel } from '@/utils/enumLabels'
import { formatDateTime, formatNumber } from '@/utils/format'
import {
  buildConservationLines,
  canWithdraw,
  diffCell,
  groupSnapshot,
  groupTotal,
  isPendingStatus,
  isTransferType,
  nodeOfStatus,
  summarizeItems,
} from '@/utils/adjustment'
import { formatTargetValue } from '@/utils/indicator'
import type { PageResult } from '@/types/api'
import type { AdjustmentDetail, AdjustmentListItem, AdjustmentSnapshot } from '@/types/adjustment'
import type { Decision } from '@/types/enums'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const scope = ref<'TODO' | 'MINE' | 'ALL'>(
  userStore.can('adjustment:decide') ? 'TODO' : 'MINE',
)
const loading = ref(false)
const page = ref<PageResult<AdjustmentListItem>>({ items: [], total: 0, page: 1, pageSize: 10 })

const scopeTabs = [
  { key: 'TODO', label: '待我处理' },
  { key: 'MINE', label: '我发起的' },
  { key: 'ALL', label: '全部' },
] as const

async function loadList(): Promise<void> {
  loading.value = true
  try {
    page.value = await listAdjustments({
      scope: scope.value,
      page: page.value.page,
      pageSize: page.value.pageSize,
    })
  } finally {
    loading.value = false
  }
}

function switchScope(key: 'TODO' | 'MINE' | 'ALL'): void {
  if (scope.value === key) return
  scope.value = key
  page.value.page = 1
  void loadList()
}

function changePage(next: number): void {
  page.value.page = next
  void loadList()
}

function typeLabel(type: string): string {
  return enumLabel('AdjustmentType', type, userStore.enums)
}
function nodeLabel(node: string | null | undefined): string {
  return node ? enumLabel('ApprovalNode', node, userStore.enums) : '—'
}
function itemsSummary(row: AdjustmentListItem): string {
  return summarizeItems(row.items ?? [], row.indicator?.unitLabel)
}

// 详情
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<AdjustmentDetail | null>(null)
const decisionComment = ref('')
const deciding = ref<Decision | null>(null)
const withdrawVisible = ref(false)
const withdrawing = ref(false)
const decisionKey = ref(newIdempotencyKey())
const withdrawKey = ref(newIdempotencyKey())
let detailSequence = 0

async function openDetail(row: Pick<AdjustmentListItem, 'id'>): Promise<void> {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  decisionComment.value = ''
  decisionKey.value = newIdempotencyKey()
  withdrawKey.value = newIdempotencyKey()
  const sequence = ++detailSequence
  try {
    const data = await getAdjustment(row.id)
    if (sequence !== detailSequence) return
    detail.value = data
  } catch {
    if (sequence === detailSequence) detailVisible.value = false
  } finally {
    if (sequence === detailSequence) detailLoading.value = false
  }
}

const currentNode = computed(() => {
  const current = detail.value
  if (!current) return null
  return current.currentNode ?? nodeOfStatus(current.status)
})

const detailFrozen = computed(() => {
  const value = detail.value?.frozenAmount
  return typeof value === 'number' && value > 0 ? value : null
})

const detailHistorical = computed(() => detail.value?.task?.isHistorical === true)

const canDecide = computed(() => {
  const current = detail.value
  const user = userStore.userInfo
  if (!current || !user || detailHistorical.value || !isPendingStatus(current.status)
    || !userStore.can('adjustment:decide')) return false
  const step = current.steps?.find((item) => item.node === currentNode.value && !item.decision)
  if (!step) return false
  if (step.node === 'OWNER_REVIEW') {
    return step.expectedApprover ? step.expectedApprover.id === user.id : userStore.roles.includes('ADMIN')
  }
  if (step.node === 'RECEIVER_CONFIRM') {
    return userStore.roles.includes('UNIT_OWNER') && step.expectedUnit?.id === user.unit?.id
  }
  return step.node === 'LEADER_APPROVAL' && userStore.roles.includes('LEADER')
})

const showWithdraw = computed(() => {
  const current = detail.value
  if (!current || detailHistorical.value) return false
  return canWithdraw(current.status, current.applicant?.id, userStore.userInfo?.id)
})

async function handleDecision(decision: Decision): Promise<void> {
  const target = detail.value
  if (!target || !canDecide.value) return
  if (decision === 'REJECT' && !decisionComment.value.trim()) {
    showToast('驳回时必须填写意见')
    return
  }
  if (decision === 'REJECT') {
    try {
      await showConfirmDialog({
        title: '驳回申请',
        message: '驳回后整个申请结束，确认驳回？',
        confirmButtonText: '确认驳回',
      })
    } catch {
      return
    }
  }
  deciding.value = decision
  try {
    const updated = await decideAdjustment(
      target.id,
      {
        decision,
        comment: decisionComment.value.trim() || undefined,
        version: target.version,
      },
      decisionKey.value,
    )
    decisionKey.value = newIdempotencyKey()
    detail.value = updated
    decisionComment.value = ''
    showToast(decision === 'APPROVE' ? '已同意' : '已驳回')
    await loadList()
  } catch (error) {
    if (error instanceof ApiError) showToast(`${error.message}（${error.code}）`)
  } finally {
    deciding.value = null
  }
}

async function submitWithdraw(): Promise<void> {
  const target = detail.value
  if (!target) return
  withdrawing.value = true
  try {
    const updated = await withdrawAdjustment(target.id, target.version, withdrawKey.value)
    withdrawKey.value = newIdempotencyKey()
    detail.value = updated
    withdrawVisible.value = false
    showToast('已撤回')
    await loadList()
  } catch (error) {
    if (error instanceof ApiError) showToast(`${error.message}（${error.code}）`)
  } finally {
    withdrawing.value = false
  }
}

// 快照对比
const snapshotYears = computed(() => {
  const years = new Set<number>()
  for (const item of detail.value?.snapshot?.before ?? []) {
    if (item.year != null) years.add(item.year)
  }
  for (const item of detail.value?.snapshot?.after ?? []) {
    if (item.year != null) years.add(item.year)
  }
  return [...years].sort((a, b) => a - b)
})
const snapshot = computed<AdjustmentSnapshot | null>(() => detail.value?.snapshot ?? null)
const groups = computed(() =>
  snapshot.value ? groupSnapshot(snapshot.value.before, snapshot.value.after) : [],
)
const conservationLines = computed(() =>
  buildConservationLines(groups.value, detail.value?.type),
)
function cellValue(group: (typeof groups.value)[number], year: number, phase: 'before' | 'after') {
  return (phase === 'before' ? group.before : group.after)[year] ?? null
}
function cellDiffOf(group: (typeof groups.value)[number], year: number) {
  return diffCell(group.before[year] ?? null, group.after[year] ?? null)
}
function groupTotalOf(group: (typeof groups.value)[number], phase: 'before' | 'after'): number | null {
  return phase === 'before'
    ? (group.totalBefore ?? groupTotal(group.before))
    : (group.totalAfter ?? groupTotal(group.after))
}

const approvalSteps = computed(() => {
  const current = detail.value
  if (!current) return []
  const steps = current.steps ?? []
  const chain = isTransferType(current.type)
    ? ['OWNER_REVIEW', 'RECEIVER_CONFIRM', 'LEADER_APPROVAL']
    : ['OWNER_REVIEW', 'LEADER_APPROVAL']
  return chain.map((node) => {
    const step = steps.find((item) => item.node === node)
    const expected = step?.expectedApprover?.name ?? step?.expectedUnit?.name ?? '—'
    let state = 'PENDING'
    if (step?.decision === 'APPROVE') state = 'APPROVED'
    else if (step?.decision === 'REJECT') state = 'REJECTED'
    else if (isPendingStatus(current.status) && currentNode.value === node) state = 'CURRENT'
    return {
      node,
      label: nodeLabel(node),
      expected,
      state,
      decidedBy: step?.decidedBy?.name ?? expected,
      decidedAt: step?.decidedAt ? formatDateTime(step.decidedAt) : '',
      comment: step?.comment ?? null,
    }
  })
})

function goApply(): void {
  router.push('/adjustments/apply')
}

onMounted(async () => {
  await loadList()
  const linkedId = typeof route.query.adjustmentId === 'string' ? route.query.adjustmentId : ''
  if (linkedId) await openDetail({ id: linkedId })
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>调整申请与审批</h1>
        <p class="muted small">延期 / 转移 / 目标调整；审批链：初审 → 接收方确认 → 校领导审批</p>
      </div>
      <van-button v-if="userStore.can('adjustment:apply')" size="small" type="primary" @click="goApply">
        发起调整
      </van-button>
    </div>

    <div class="filter-scroll">
      <button
        v-for="tab in scopeTabs"
        :key="tab.key"
        type="button"
        class="filter-chip"
        :class="{ 'is-active': scope === tab.key }"
        @click="switchScope(tab.key)"
      >{{ tab.label }}</button>
    </div>

    <PageState :loading="loading" :empty="page.items.length === 0" empty-text="当前没有调整申请">
      <div class="adjust-list">
        <article v-for="row in page.items" :key="row.id" class="adjust-card" @click="openDetail(row)">
          <div class="row--between">
            <div class="row">
              <strong>{{ typeLabel(row.type) }}</strong>
              <span v-if="row.task?.isHistorical" class="chip chip--gray">历史只读</span>
            </div>
            <StatusTag kind="AdjustmentStatus" :value="row.status" />
          </div>
          <div class="small ellipsis">
            <template v-if="row.task"><span class="code">{{ row.task.code }}</span> {{ row.indicator?.name ?? '' }}</template>
            <template v-else>指标级调整 · {{ row.indicator?.name ?? '' }}</template>
          </div>
          <div class="small muted">{{ itemsSummary(row) }}</div>
          <div v-if="isTransferType(row.type)" class="small muted">
            {{ row.fromUnit?.name ?? '—' }} → {{ row.toUnit?.name ?? '—' }}
          </div>
          <div class="row--between adjust-meta">
            <span class="small muted">{{ row.applicant?.name ?? '—' }} · {{ formatDateTime(row.createdAt) }}</span>
            <span v-if="row.currentNode && isPendingStatus(row.status)" class="chip chip--warning">
              {{ nodeLabel(row.currentNode) }}
            </span>
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

    <van-popup v-model:show="detailVisible" position="bottom" round :style="{ height: '92vh' }">
      <div class="detail-sheet">
        <div class="sheet-head">
          <strong>调整详情</strong>
          <button type="button" class="close-btn" @click="detailVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="detail-body">
          <div v-if="detailLoading && !detail" class="skeleton-block">加载详情…</div>
          <template v-else-if="detail">
            <div class="card card--tight">
              <div class="row--between">
                <div class="row">
                  <strong>{{ typeLabel(detail.type) }}</strong>
                  <span v-if="detail.task?.isHistorical" class="chip chip--gray">历史只读</span>
                </div>
                <StatusTag kind="AdjustmentStatus" :value="detail.status" />
              </div>
              <div class="kv-list detail-kv">
                <div class="kv"><span class="kv__label">学院任务</span><span class="kv__value">{{ detail.task?.code ?? '指标级调整' }}</span></div>
                <div class="kv"><span class="kv__label">所属指标</span><span class="kv__value">{{ detail.indicator ? `${detail.indicator.code} ${detail.indicator.name}` : '—' }}</span></div>
                <div v-if="isTransferType(detail.type)" class="kv"><span class="kv__label">转出 → 接收</span><span class="kv__value">{{ detail.fromUnit?.name ?? '—' }} → {{ detail.toUnit?.name ?? '—' }}</span></div>
                <div class="kv"><span class="kv__label">调整明细</span><span class="kv__value">{{ itemsSummary(detail) }}</span></div>
                <div v-if="detailFrozen !== null" class="kv"><span class="kv__label">冻结额度</span><span class="kv__value">{{ detailFrozen }}</span></div>
                <div class="kv"><span class="kv__label">申请人</span><span class="kv__value">{{ detail.applicant?.name ?? '—' }}</span></div>
                <div class="kv"><span class="kv__label">申请时间</span><span class="kv__value">{{ formatDateTime(detail.createdAt) }}</span></div>
                <div v-if="detail.effectiveAt" class="kv"><span class="kv__label">生效时间</span><span class="kv__value">{{ formatDateTime(detail.effectiveAt) }}</span></div>
                <div class="kv"><span class="kv__label">申请原因</span><span class="kv__value">{{ detail.reason ?? '—' }}</span></div>
              </div>
            </div>

            <div v-if="groups.length > 0" class="card card--tight">
              <div class="section-title">调整前后对比</div>
              <p class="small muted">
                {{ detail.status === 'APPROVED' && detail.effectiveAt ? `快照 · 生效于 ${formatDateTime(detail.effectiveAt)}` : '快照（申请时试算）' }}
              </p>
              <div v-for="group in groups" :key="group.taskCode" class="compare-group">
                <div class="row">
                  <strong class="code">{{ group.taskCode }}</strong>
                  <span v-if="group.isNew" class="chip chip--success">新建</span>
                </div>
                <table class="data-table">
                  <thead>
                    <tr>
                      <th>阶段</th>
                      <th v-for="year in snapshotYears" :key="year">{{ year }}</th>
                      <th>五年</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td>前</td>
                      <td v-for="year in snapshotYears" :key="year">
                        {{ formatTargetValue(cellValue(group, year, 'before'), detail.indicator?.statCaliber) }}
                      </td>
                      <td>{{ groupTotalOf(group, 'before') ?? '—' }}</td>
                    </tr>
                    <tr>
                      <td>后</td>
                      <td
                        v-for="year in snapshotYears"
                        :key="year"
                        :class="cellDiffOf(group, year).changed ? 'cell-changed' : ''"
                      >
                        {{ formatTargetValue(cellValue(group, year, 'after'), detail.indicator?.statCaliber) }}
                        <span v-if="cellDiffOf(group, year).increased" class="text-success">↑</span>
                        <span v-else-if="cellDiffOf(group, year).decreased" class="text-danger">↓</span>
                      </td>
                      <td>{{ groupTotalOf(group, 'after') ?? '—' }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-if="conservationLines.length > 0" class="conservation">
                <div
                  v-for="line in conservationLines"
                  :key="line.taskCode"
                  class="small"
                  :class="line.balanced ? 'text-success' : 'text-danger'"
                >
                  {{ line.taskCode }}<template v-if="line.combined">（转出 + 接收合计）</template>
                  五年 {{ line.before ?? '—' }} → {{ line.after ?? '—' }}
                  {{ line.balanced ? '✓ 守恒' : '✗ 不守恒（70002）' }}
                </div>
              </div>
            </div>

            <div class="card card--tight">
              <div class="section-title">审批进度</div>
              <div class="approval-list">
                <div class="approval-step is-approved">
                  <div class="row--between">
                    <strong>发起申请</strong>
                    <span class="small text-success">已提交</span>
                  </div>
                  <div class="small muted">{{ detail.applicant?.name ?? '—' }} · {{ formatDateTime(detail.createdAt) }}</div>
                  <div v-if="detail.reason" class="small">“{{ detail.reason }}”</div>
                </div>
                <div
                  v-for="step in approvalSteps"
                  :key="step.node"
                  class="approval-step"
                  :class="`is-${step.state.toLowerCase()}`"
                >
                  <div class="row--between">
                    <strong>{{ step.label }}</strong>
                    <span
                      class="small"
                      :class="step.state === 'APPROVED' ? 'text-success' : step.state === 'REJECTED' ? 'text-danger' : step.state === 'CURRENT' ? 'text-warning' : 'muted'"
                    >
                      {{ step.state === 'APPROVED' ? '已同意' : step.state === 'REJECTED' ? '已驳回' : step.state === 'CURRENT' ? '当前环节' : '待处理' }}
                    </span>
                  </div>
                  <div class="small muted">{{ step.decidedBy }}<template v-if="step.decidedAt"> · {{ step.decidedAt }}</template></div>
                  <div v-if="step.comment" class="small">“{{ step.comment }}”</div>
                </div>
              </div>
            </div>

            <div v-if="(detail.files ?? []).length > 0" class="card card--tight">
              <div class="section-title">附件（{{ detail.files?.length ?? 0 }}）</div>
              <AttachmentList :files="detail.files ?? []" />
            </div>

            <div v-if="canDecide || showWithdraw" class="card card--tight">
              <div class="section-title">处理</div>
              <template v-if="canDecide">
                <div class="field">
                  <label>审批意见（驳回必填）</label>
                  <van-field v-model="decisionComment" type="textarea" rows="2" maxlength="500" show-word-limit />
                </div>
                <div class="sheet-actions">
                  <van-button block type="danger" plain :loading="deciding === 'REJECT'" @click="handleDecision('REJECT')">驳回</van-button>
                  <van-button block type="primary" :loading="deciding === 'APPROVE'" @click="handleDecision('APPROVE')">同意</van-button>
                </div>
              </template>
              <van-button
                v-if="showWithdraw"
                block
                plain
                type="warning"
                :loading="withdrawing"
                @click="withdrawVisible = true"
              >撤回申请</van-button>
            </div>
          </template>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="withdrawVisible" position="bottom" round safe-area-inset-bottom>
      <div class="simple-sheet">
        <h3>撤回申请</h3>
        <p class="small muted">撤回后申请结束，已冻结的额度将解除。确认撤回？</p>
        <div class="sheet-actions">
          <van-button block @click="withdrawVisible = false">取消</van-button>
          <van-button block type="warning" :loading="withdrawing" @click="submitWithdraw">确认撤回</van-button>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin: 2px 0 10px;
}
.page-head h1 {
  margin: 0;
  font-size: 21px;
}
.page-head p {
  margin: 3px 0 0;
}
.adjust-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.adjust-card {
  padding: 10px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.adjust-card .small {
  margin-top: 3px;
}
.adjust-meta {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed var(--line);
}
.code {
  color: var(--brand);
  font-family: Consolas, Menlo, monospace;
  font-weight: 600;
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
  overflow-y: auto;
  padding: 10px 12px calc(16px + var(--safe-bottom));
}
.detail-kv {
  margin-top: 8px;
}
.compare-group {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--line);
}
.cell-changed {
  background: #fdf6ec;
}
.conservation {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.approval-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.approval-step {
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8fafd;
}
.approval-step.is-current {
  background: #fdf3e5;
}
.approval-step.is-rejected {
  background: #fdecec;
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
.simple-sheet {
  padding: 16px 16px calc(16px + var(--safe-bottom));
}
.simple-sheet h3 {
  margin: 0 0 8px;
  font-size: 16px;
}
</style>
