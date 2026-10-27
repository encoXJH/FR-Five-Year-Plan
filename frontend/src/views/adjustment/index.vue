<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
import FilePreviewDrawer from '@/components/FilePreviewDrawer.vue'
import AdjustmentCompareTable from './components/AdjustmentCompareTable.vue'
import {
  decideAdjustment,
  getAdjustment,
  listAdjustments,
  withdrawAdjustment,
} from '@/api/adjustment'
import { getTask } from '@/api/task'
import { useUserStore } from '@/stores/user'
import { newIdempotencyKey } from '@/utils/idempotency'
import { ApiError } from '@/utils/request'
import { enumLabel } from '@/utils/enumLabels'
import { formatDateTime } from '@/utils/format'
import { formatFileSize, FILE_CATEGORY_COLORS } from '@/utils/file'
import {
  canWithdraw,
  isPendingStatus,
  isTransferType,
  nodeOfStatus,
  summarizeItems,
} from '@/utils/adjustment'
import type { PageResult } from '@/types/api'
import type { AdjustmentFileRef } from '@/types/adjustment'
import type {
  AdjustmentDetail,
  AdjustmentListItem,
  AdjustmentScope,
} from '@/types/adjustment'
import type { ApprovalNode, Decision } from '@/types/enums'

const router = useRouter()
const userStore = useUserStore()

interface ScopeTab {
  key: AdjustmentScope
  label: string
}

/** 全部记录仅管理员 / 校领导（数据范围为全校）可见 */
const tabs = computed<ScopeTab[]>(() => {
  const base: ScopeTab[] = [
    { key: 'TODO', label: '待我处理' },
    { key: 'MINE', label: '我发起的' },
  ]
  if (userStore.roles.includes('ADMIN') || userStore.roles.includes('LEADER')) {
    base.push({ key: 'ALL', label: '全部记录' })
  }
  return base
})

const activeTab = ref<AdjustmentScope>('TODO')

const loading = ref(false)
const page = ref<PageResult<AdjustmentListItem>>({ items: [], total: 0, page: 1, pageSize: 20 })

async function loadList(): Promise<void> {
  loading.value = true
  try {
    page.value = await listAdjustments({
      scope: activeTab.value,
      page: page.value.page,
      pageSize: page.value.pageSize,
    })
  } finally {
    loading.value = false
  }
}

function switchTab(): void {
  page.value.page = 1
  closeDrawer()
  loadList()
}

function changePage(next: number): void {
  page.value.page = next
  loadList()
}

function changePageSize(next: number): void {
  page.value.pageSize = next
  page.value.page = 1
  loadList()
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

// ---------- 详情抽屉 ----------
const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<AdjustmentDetail | null>(null)
/** 快照年度列（优先任务计划起止年，否则快照中出现过的年份） */
const snapshotYears = ref<number[]>([])

async function openDetail(row: AdjustmentListItem): Promise<void> {
  drawerVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const data = await getAdjustment(row.id)
    detail.value = data
    decisionForm.comment = ''
    decisionKey.value = newIdempotencyKey()
    withdrawKey.value = newIdempotencyKey()
    const years = new Set<number>()
    for (const item of data.snapshot?.before ?? []) {
      if (item.year != null) years.add(item.year)
    }
    for (const item of data.snapshot?.after ?? []) {
      if (item.year != null) years.add(item.year)
    }
    snapshotYears.value = [...years].sort((a, b) => a - b)
    if (data.task?.id) {
      const task = await getTask(data.task.id).catch(() => null)
      const plan = task?.plan
      if (plan) {
        const list: number[] = []
        for (let year = plan.startYear; year <= plan.endYear; year += 1) list.push(year)
        if (list.length > 0) snapshotYears.value = list
      }
    }
  } catch {
    // 拦截器已提示
  } finally {
    detailLoading.value = false
  }
}

function closeDrawer(): void {
  drawerVisible.value = false
  detail.value = null
}

const snapshotCaption = computed(() => {
  const current = detail.value
  if (!current) return ''
  return current.status === 'APPROVED' && current.effectiveAt
    ? `快照 · 生效于 ${formatDateTime(current.effectiveAt)}`
    : '快照（申请时试算）'
})

/** 当前环节（优先 currentNode，缺省按状态推断） */
const currentNode = computed(() => {
  const current = detail.value
  if (!current) return null
  return current.currentNode ?? nodeOfStatus(current.status)
})

/** 冻结额度标记（UI 规范 10.2：审批中转出方额度被冻结；后端返回 frozenAmount 时展示） */
const detailFrozen = computed(() => {
  const value = detail.value?.frozenAmount
  return typeof value === 'number' && value > 0 ? value : null
})

// ---------- 审批时间线（UI 规范 7.8） ----------
type TimelineState = 'APPROVED' | 'REJECTED' | 'CURRENT' | 'PENDING' | 'SKIPPED'

interface TimelineNode {
  key: string
  label: string
  state: TimelineState
  actor: string
  time: string
  comment: string | null
  summary: string | null
  waitDays: number | null
}

function waitText(days: number): string {
  return days < 1 ? '等待处理 · 不足 1 天' : `等待处理 · 已 ${days} 天`
}

/** 完整审批链节点：发起申请 → 初审 →（转移类）接收方确认 → 校领导审批 */
const timelineNodes = computed<TimelineNode[]>(() => {
  const current = detail.value
  if (!current) return []
  const steps = current.steps ?? []
  const nodes: TimelineNode[] = [
    {
      key: 'APPLY',
      label: '发起申请',
      state: 'APPROVED',
      actor: current.applicant?.name ?? '—',
      time: current.createdAt ? formatDateTime(current.createdAt) : '',
      comment: current.reason ?? null,
      summary: itemsSummary(current),
      waitDays: null,
    },
  ]
  // 延期类无接收方环节，不显示该节点（UI 规范 7.8）
  const chain: ApprovalNode[] = isTransferType(current.type)
    ? ['OWNER_REVIEW', 'RECEIVER_CONFIRM', 'LEADER_APPROVAL']
    : ['OWNER_REVIEW', 'LEADER_APPROVAL']
  const decidedTimes = steps
    .map((step) => step.decidedAt)
    .filter((time): time is string => typeof time === 'string' && time.length > 0)
    .sort()
  const anchor = decidedTimes[decidedTimes.length - 1] ?? current.createdAt
  const waitDays = Math.max(0, Math.floor((Date.now() - new Date(anchor).getTime()) / 86400000))
  let afterReject = false
  for (const node of chain) {
    const step = steps.find((item) => item.node === node)
    const expected = step?.expectedApprover?.name ?? step?.expectedUnit?.name ?? '—'
    const base = {
      key: node,
      label: nodeLabel(node),
      summary: null,
      waitDays: null,
    }
    if (afterReject) {
      nodes.push({ ...base, state: 'SKIPPED', actor: expected, time: '', comment: null })
    } else if (step?.decision === 'APPROVE') {
      nodes.push({
        ...base,
        state: 'APPROVED',
        actor: step.decidedBy?.name ?? expected,
        time: step.decidedAt ? formatDateTime(step.decidedAt) : '',
        comment: step.comment ?? null,
      })
    } else if (step?.decision === 'REJECT') {
      nodes.push({
        ...base,
        state: 'REJECTED',
        actor: step.decidedBy?.name ?? expected,
        time: step.decidedAt ? formatDateTime(step.decidedAt) : '',
        comment: step.comment ?? null,
      })
      afterReject = true
    } else if (isPendingStatus(current.status) && currentNode.value === node) {
      nodes.push({ ...base, state: 'CURRENT', actor: expected, time: '', comment: null, waitDays })
    } else {
      nodes.push({ ...base, state: 'PENDING', actor: expected, time: '', comment: null })
    }
  }
  return nodes
})

const detailHistorical = computed(() => detail.value?.task?.isHistorical === true)

/** 处理区可见：待我处理 Tab + 审批中 + 有审批权限（70005 由服务端兜底） */
const canDecide = computed(
  () =>
    activeTab.value === 'TODO' &&
    detail.value !== null &&
    !detailHistorical.value &&
    isPendingStatus(detail.value.status) &&
    userStore.can('adjustment:decide'),
)

const showWithdraw = computed(() => {
  const current = detail.value
  if (!current || current.task?.isHistorical === true) return false
  return canWithdraw(current.status, current.applicant?.id, userStore.userInfo?.id)
})

// ---------- 审批操作 ----------
const decisionForm = reactive({ comment: '' })
const deciding = ref<Decision | null>(null)

/** 幂等键（UI 规范 10.4）：成功后重新生成，失败重试沿用同一个 */
const decisionKey = ref(newIdempotencyKey())
const withdrawKey = ref(newIdempotencyKey())

async function handleDecision(decision: Decision): Promise<void> {
  const target = detail.value
  if (!target) return
  if (target.task?.isHistorical === true) {
    ElMessage.warning('历史任务只读，不能审批调整申请')
    return
  }
  if (!canDecide.value) return
  if (decision === 'REJECT' && !decisionForm.comment.trim()) {
    ElMessage.error('驳回时必须填写意见')
    return
  }
  if (decision === 'REJECT') {
    try {
      await ElMessageBox.confirm('驳回后整个申请结束，确认驳回？', '驳回申请', {
        type: 'warning',
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
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
        comment: decisionForm.comment.trim() || undefined,
        version: target.version,
      },
      decisionKey.value,
    )
    decisionKey.value = newIdempotencyKey()
    detail.value = updated
    ElMessage.success(decision === 'APPROVE' ? '已同意' : '已驳回')
    await loadList()
  } catch (error) {
    // 失败重试沿用同一个 Idempotency-Key
    if (error instanceof ApiError) ElMessage.error(`${error.message}（${error.code}）`)
  } finally {
    deciding.value = null
  }
}

// ---------- 撤回 ----------
const withdrawing = ref(false)

async function handleWithdraw(): Promise<void> {
  const target = detail.value
  if (!target) return
  if (target.task?.isHistorical === true) {
    ElMessage.warning('历史任务只读，不能撤回调整申请')
    return
  }
  if (!showWithdraw.value) return
  try {
    await ElMessageBox.confirm('撤回后申请结束，已冻结的额度将解除。确认撤回？', '撤回申请', {
      type: 'warning',
      confirmButtonText: '确认撤回',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  withdrawing.value = true
  try {
    const updated = await withdrawAdjustment(target.id, target.version, withdrawKey.value)
    withdrawKey.value = newIdempotencyKey()
    detail.value = updated
    ElMessage.success('已撤回')
    await loadList()
  } catch (error) {
    // 失败重试沿用同一个 Idempotency-Key
    if (error instanceof ApiError) ElMessage.error(`${error.message}（${error.code}）`)
  } finally {
    withdrawing.value = false
  }
}

// ---------- 附件预览 ----------
const previewVisible = ref(false)
const previewFile = ref<AdjustmentFileRef | null>(null)

function openPreview(file: AdjustmentFileRef): void {
  previewFile.value = file
  previewVisible.value = true
}

function goApply(): void {
  router.push('/adjustments/apply')
}

onMounted(loadList)
</script>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1 class="page-title">调整申请与审批</h1>
        <p class="page-subtitle">延期 / 转移 / 目标调整；审批链：初审 → 接收方确认 → 校领导审批</p>
      </div>
      <el-button
        v-permission="'adjustment:apply'"
        type="primary"
        @click="goApply"
      >
        发起调整申请
      </el-button>
    </div>

    <el-card shadow="never" class="list-card">
      <el-tabs v-model="activeTab" @tab-change="switchTab">
        <el-tab-pane v-for="tab in tabs" :key="tab.key" :label="tab.label" :name="tab.key" />
      </el-tabs>

      <el-table v-loading="loading" :data="page.items" border size="small" @row-click="openDetail">
        <el-table-column label="类型" width="110">
          <template #default="{ row }">{{ typeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column label="B 任务" min-width="180">
          <template #default="{ row }">
            <template v-if="row.task">
              <span class="mono">{{ row.task.code }}</span>
              <span class="indicator-name"> {{ row.indicator?.name ?? '' }}</span>
              <el-tag
                v-if="row.task.isHistorical"
                size="small"
                type="info"
                effect="plain"
                class="history-tag"
              >
                历史只读
              </el-tag>
            </template>
            <span v-else class="empty-text">指标级调整</span>
          </template>
        </el-table-column>
        <el-table-column label="年份 / 数量" min-width="150">
          <template #default="{ row }">{{ itemsSummary(row) }}</template>
        </el-table-column>
        <el-table-column label="转出 → 接收" min-width="160">
          <template #default="{ row }">
            <template v-if="isTransferType(row.type)">
              {{ row.fromUnit?.name ?? '—' }}
              → {{ row.toUnit?.name ?? '—' }}
            </template>
            <span v-else class="empty-text">—</span>
          </template>
        </el-table-column>
        <el-table-column label="申请人" width="100">
          <template #default="{ row }">{{ row.applicant?.name ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="申请时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <StatusTag kind="AdjustmentStatus" :value="row.status" />
          </template>
        </el-table-column>
        <el-table-column v-if="activeTab === 'TODO'" label="当前环节" width="130" align="center">
          <template #default="{ row }">{{ nodeLabel(row.currentNode ?? nodeOfStatus(row.status)) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :image-size="80" description="暂无调整申请记录" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          :current-page="page.page"
          :page-size="page.pageSize"
          :total="page.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="changePage"
          @size-change="changePageSize"
        />
      </div>
    </el-card>

    <!-- 审批详情抽屉 -->
    <el-drawer v-model="drawerVisible" direction="rtl" size="640px" title="调整申请详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-alert
            v-if="detailHistorical"
            type="info"
            :closable="false"
            title="历史任务只读，可查看调整快照、附件和审批记录，不能审批或撤回"
            class="history-alert"
          />
          <el-descriptions :column="2" border size="small" class="detail-desc">
            <el-descriptions-item label="调整类型">{{ typeLabel(detail.type) }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <StatusTag kind="AdjustmentStatus" :value="detail.status" />
            </el-descriptions-item>
            <el-descriptions-item label="B 任务" :span="2">
              <template v-if="detail.task">
                <span class="mono">{{ detail.task.code }}</span>
                {{ detail.indicator?.name ?? '' }}
                <el-tag
                  v-if="detail.task.isHistorical"
                  size="small"
                  type="info"
                  effect="plain"
                  class="history-tag"
                >
                  历史只读
                </el-tag>
                <StatusTag
                  v-if="detail.indicator?.statCaliber"
                  kind="StatCaliber"
                  :value="detail.indicator.statCaliber"
                  class="caliber-tag"
                />
              </template>
              <template v-else>指标级调整（目标调整）</template>
            </el-descriptions-item>
            <el-descriptions-item label="调整内容" :span="2">
              {{ itemsSummary(detail) }}
            </el-descriptions-item>
            <el-descriptions-item v-if="isTransferType(detail.type)" label="转出 → 接收" :span="2">
              {{ detail.fromUnit?.name ?? '—' }}
              → {{ detail.toUnit?.name ?? '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="申请人">{{ detail.applicant?.name ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="申请时间">{{ formatDateTime(detail.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="申请原因" :span="2">{{ detail.reason ?? '—' }}</el-descriptions-item>
          </el-descriptions>

          <template v-if="(detail.files ?? []).length > 0">
            <h3 class="section-title">附件（{{ detail.files!.length }} 份）</h3>
            <ul class="file-list">
              <li v-for="file in detail.files!" :key="file.id" class="file-row">
                <span class="file-dot" :style="{ backgroundColor: FILE_CATEGORY_COLORS[file.category] }" />
                <span class="file-name" :title="file.fileName">{{ file.fileName }}</span>
                <span class="file-size">{{ formatFileSize(file.size) }}</span>
                <el-button link type="primary" @click="openPreview(file)">预览</el-button>
              </li>
            </ul>
          </template>

          <h3 class="section-title">调整前后对比</h3>
          <AdjustmentCompareTable
            :snapshot="detail.snapshot ?? null"
            :years="snapshotYears"
            :caliber="detail.indicator?.statCaliber ?? 'INCREMENT'"
            :caption="snapshotCaption"
          />
          <div v-if="detailFrozen !== null" class="frozen-hint">
            🔒 冻结 {{ detailFrozen }}：审批期间转出方该额度不可再次申请（70004），审批结束后解除
          </div>

          <h3 class="section-title">审批时间线</h3>
          <el-timeline class="approval-timeline">
            <el-timeline-item
              v-for="node in timelineNodes"
              :key="node.key"
              placement="top"
              :timestamp="node.time"
              :class="{ 'tl-future': node.state === 'PENDING' || node.state === 'SKIPPED' }"
            >
              <template #dot>
                <span class="tl-dot" :class="`tl-${node.state.toLowerCase()}`">
                  <template v-if="node.state === 'APPROVED'">✓</template>
                  <template v-else-if="node.state === 'REJECTED'">✕</template>
                </span>
              </template>
              <div class="step-line">
                <span class="step-node" :class="{ 'is-dim': node.state === 'PENDING' || node.state === 'SKIPPED' }">
                  {{ node.label }}
                </span>
                <el-tag v-if="node.state === 'APPROVED' && node.key !== 'APPLY'" size="small" type="success" effect="light">
                  同意
                </el-tag>
                <el-tag v-else-if="node.state === 'REJECTED'" size="small" type="danger" effect="light">驳回</el-tag>
                <el-tag v-else-if="node.state === 'CURRENT'" size="small" type="primary">处理中</el-tag>
                <el-tag v-else-if="node.state === 'SKIPPED'" size="small" type="info" effect="plain">未执行</el-tag>
                <el-tag v-else-if="node.state === 'PENDING'" size="small" type="info" effect="plain">未开始</el-tag>
              </div>
              <div class="step-meta">
                {{ node.actor }}
                <template v-if="node.state === 'CURRENT' && node.waitDays !== null">
                  · {{ waitText(node.waitDays) }}
                </template>
                <template v-if="node.summary"> · {{ node.summary }}</template>
              </div>
              <blockquote v-if="node.comment" class="step-comment">{{ node.comment }}</blockquote>
            </el-timeline-item>
          </el-timeline>

          <template v-if="canDecide">
            <el-divider />
            <h3 class="section-title">处理</h3>
            <el-input
              v-model="decisionForm.comment"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              placeholder="审批意见（驳回时必填，≤ 500 字）"
            />
            <div class="decision-actions">
              <el-button
                type="danger"
                :loading="deciding === 'REJECT'"
                :disabled="deciding === 'APPROVE'"
                @click="handleDecision('REJECT')"
              >
                驳回
              </el-button>
              <el-button
                type="success"
                :loading="deciding === 'APPROVE'"
                :disabled="deciding === 'REJECT'"
                @click="handleDecision('APPROVE')"
              >
                同意
              </el-button>
            </div>
          </template>

          <div v-if="showWithdraw" class="withdraw-bar">
            <el-button type="warning" plain :loading="withdrawing" @click="handleWithdraw">
              撤回申请
            </el-button>
          </div>
        </template>
        <el-empty v-else-if="!detailLoading" :image-size="80" description="加载失败或申请不存在" />
      </div>
    </el-drawer>

    <FilePreviewDrawer v-model="previewVisible" :file="previewFile" />
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

.list-card {
  border-radius: var(--radius-card);
}

.mono {
  font-family: var(--app-font-mono);
}

.indicator-name {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.empty-text {
  color: var(--el-text-color-placeholder);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}

.history-alert,
.detail-desc {
  margin-bottom: var(--space-4);
}

.history-tag {
  margin-left: var(--space-2);
}

.caliber-tag {
  margin-left: var(--space-1);
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  margin: var(--space-4) 0 var(--space-3);
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

.approval-timeline {
  padding-left: var(--space-1);
}

/* 审批时间线节点（UI 规范 7.8） */
.tl-dot {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  box-sizing: border-box;
  font-size: 11px;
  line-height: 1;
  color: #fff;
  background: #fff;
  border: 2px solid var(--el-border-color);
}

.tl-approved {
  background: var(--el-color-success);
  border-color: var(--el-color-success);
}

.tl-rejected {
  background: var(--el-color-danger);
  border-color: var(--el-color-danger);
}

/* 当前环节：主色实心 + 外发光脉冲 */
.tl-current {
  background: var(--el-color-primary);
  border-color: var(--el-color-primary);
  animation: tl-pulse 1.6s ease-out infinite;
}

@keyframes tl-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(43, 91, 192, 0.45);
  }
  70% {
    box-shadow: 0 0 0 8px rgba(43, 91, 192, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(43, 91, 192, 0);
  }
}

/* 未到达 / 未执行：灰色空心 + 虚线连接 */
:deep(.tl-future .el-timeline-item__tail) {
  border-left-style: dashed;
}

.step-node.is-dim {
  color: var(--el-text-color-secondary);
  font-weight: 400;
}

.step-comment {
  margin: var(--space-1) 0 0;
  padding: var(--space-1) var(--space-2);
  background: var(--el-fill-color-light);
  border-left: 3px solid var(--el-border-color);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.frozen-hint {
  margin-top: var(--space-2);
  font-size: 12px;
  color: var(--el-color-warning);
}

.step-line {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.step-node {
  font-weight: 600;
  font-size: 13px;
}

.step-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.decision-actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.withdraw-bar {
  margin-top: var(--space-4);
  display: flex;
  justify-content: flex-end;
}

:deep(.el-table__row) {
  cursor: pointer;
}
</style>
