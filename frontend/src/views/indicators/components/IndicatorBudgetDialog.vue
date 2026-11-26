<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
import FilePreviewDrawer from '@/components/FilePreviewDrawer.vue'
import { getIndicatorBudget, putIndicatorBudget } from '@/api/indicator'
import { downloadFile } from '@/api/file'
import { useUserStore } from '@/stores/user'
import { formatDateTime, formatMoney } from '@/utils/format'
import { newIdempotencyKey } from '@/utils/idempotency'
import { ApiError } from '@/utils/request'
import type { Indicator, IndicatorBudgetView } from '@/types/indicator'
import type { FileInfo, FileView } from '@/types/file'

const props = withDefaults(defineProps<{
  modelValue: boolean
  indicator: Indicator | null
  years: number[]
  planArchived?: boolean
  manageOnly?: boolean
}>(), {
  planArchived: false,
  manageOnly: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
}>()

const userStore = useUserStore()
const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const loading = ref(false)
const budgetView = ref<IndicatorBudgetView | null>(null)
const yearFilter = ref<number | ''>('')
const page = ref(1)
const pageSize = ref(10)
let budgetRequestId = 0
let saveRequestId = 0

const budgetVersion = ref(0)
const budgetInput = ref<number | null>(null)
const budgetReason = ref('')
const saving = ref(false)

const previewVisible = ref(false)
const previewFile = ref<Pick<FileInfo, 'id' | 'fileName' | 'size' | 'category' | 'transcodeStatus'> | null>(null)

const canMaintain = computed(() =>
  userStore.can('indicator:budget') && props.indicator?.level === 'A' && !props.planArchived,
)

const summaryTotal = computed(() => {
  if (budgetView.value) return budgetView.value.totalBudget
  return props.indicator?.totalBudget ?? null
})
const summarySpent = computed(() => {
  if (budgetView.value) return budgetView.value.spentBudget ?? 0
  return props.indicator?.spentBudget ?? 0
})
const summaryRemaining = computed(() =>
  summaryTotal.value === null ? null : summaryTotal.value - summarySpent.value,
)

async function loadBudget(): Promise<void> {
  const indicator = props.indicator
  if (!indicator || !props.modelValue) return
  const requestId = ++budgetRequestId
  loading.value = true
  try {
    const result = await getIndicatorBudget(indicator.id, {
      page: page.value,
      pageSize: pageSize.value,
      year: yearFilter.value === '' ? undefined : yearFilter.value,
    })
    if (requestId !== budgetRequestId) return
    budgetView.value = result
  } catch {
    if (requestId === budgetRequestId) budgetView.value = null
  } finally {
    if (requestId === budgetRequestId) loading.value = false
  }
}

watch(() => props.modelValue, (value) => {
  if (value) {
    budgetRequestId += 1
    saveRequestId += 1
    loading.value = false
    saving.value = false
    budgetView.value = null
    yearFilter.value = ''
    page.value = 1
    budgetVersion.value = props.indicator?.version ?? 0
    budgetInput.value = props.indicator?.totalBudget ?? null
    budgetReason.value = ''
    previewVisible.value = false
    previewFile.value = null
    void loadBudget()
  } else {
    budgetRequestId += 1
    loading.value = false
  }
})

function changeYearFilter(): void {
  page.value = 1
  void loadBudget()
}

function changePageSize(): void {
  page.value = 1
  void loadBudget()
}

function expenseText(value: number | null): string {
  return value === null ? '—' : `${formatMoney(value)} 万`
}

async function saveBudget(): Promise<void> {
  const indicator = props.indicator
  if (!indicator || saving.value || !canMaintain.value) return
  const reason = budgetReason.value.trim()
  if (!reason) {
    ElMessage.warning('请填写预算调整原因')
    return
  }
  const requestId = ++saveRequestId
  saving.value = true
  try {
    const detail = await putIndicatorBudget(indicator.id, {
      version: budgetVersion.value,
      totalBudget: budgetInput.value,
      reason,
    }, newIdempotencyKey())
    if (requestId !== saveRequestId) return
    budgetVersion.value = detail.version
    budgetInput.value = detail.totalBudget ?? null
    budgetReason.value = ''
    ElMessage.success('预算已保存')
    emit('saved')
    await loadBudget()
  } catch (error) {
    if (requestId === saveRequestId) {
      ElMessage.error(error instanceof ApiError && error.message ? error.message : '预算保存失败')
    }
  } finally {
    if (requestId === saveRequestId) saving.value = false
  }
}

function previewBudgetFile(file: FileView): void {
  previewFile.value = { ...file, transcodeStatus: null }
  previewVisible.value = true
}

async function downloadBudgetFile(file: FileView): Promise<void> {
  await downloadFile(file.id, file.fileName, file.size)
}
</script>

<template>
  <el-dialog v-model="visible" :width="manageOnly ? '560px' : '1000px'" align-center class="indicator-budget-dialog">
    <template #header>
      <div class="dialog-heading">
        <h3>{{ manageOnly ? '设置总预算' : '预算明细' }}</h3>
        <p><span>{{ indicator?.code }}</span>{{ indicator?.name }}</p>
      </div>
    </template>
    <div v-loading="loading" class="budget-dialog-content">
      <div class="budget-summary">
        <div>
          <span>总预算</span>
          <strong class="is-primary">{{ summaryTotal === null ? '未设置' : `${formatMoney(summaryTotal)} 万` }}</strong>
        </div>
        <div>
          <span>已花预算</span>
          <strong>{{ formatMoney(summarySpent) }} 万</strong>
        </div>
        <div>
          <span>剩余预算</span>
          <strong :class="{ 'is-danger': summaryRemaining !== null && summaryRemaining < 0 }">
            {{ summaryRemaining === null ? '未设置' : `${formatMoney(summaryRemaining)} 万` }}
          </strong>
        </div>
      </div>

      <template v-if="!manageOnly">
      <div class="budget-toolbar">
        <span class="budget-section-title">支出明细</span>
        <el-select v-model="yearFilter" class="budget-year-filter" aria-label="按年度筛选支出" @change="changeYearFilter">
          <el-option label="全部年度" value="" />
          <el-option v-for="year in years" :key="year" :label="`${year} 年`" :value="year" />
        </el-select>
      </div>
      <el-table :data="budgetView?.expenses.items ?? []" border height="320">
        <el-table-column label="任务编号" width="105" class-name="mono">
          <template #default="{ row }">{{ row.taskCode ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="学院" min-width="120">
          <template #default="{ row }">{{ row.unit?.name ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="年度" width="64" align="center">
          <template #default="{ row }">{{ row.year }}</template>
        </el-table-column>
        <el-table-column label="本次支出" width="100" align="right">
          <template #default="{ row }">{{ expenseText(row.expenseAmount) }}</template>
        </el-table-column>
        <el-table-column label="认定支出" width="100" align="right">
          <template #default="{ row }">{{ expenseText(row.approvedExpenseAmount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }"><StatusTag kind="SubmissionStatus" :value="row.status" /></template>
        </el-table-column>
        <el-table-column label="提交人" width="84">
          <template #default="{ row }">{{ row.submittedBy?.name ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="提交时间" width="140">
          <template #default="{ row }">{{ formatDateTime(row.submittedAt) }}</template>
        </el-table-column>
        <el-table-column label="佐证" min-width="150">
          <template #default="{ row }">
            <template v-if="row.budgetFiles.length > 0">
              <div v-for="file in row.budgetFiles" :key="file.id" class="budget-file-row">
                <span class="budget-file-name" :title="file.fileName">{{ file.fileName }}</span>
                <span class="budget-file-actions">
                  <el-button link type="primary" @click="previewBudgetFile(file)">预览</el-button>
                  <el-button link type="primary" @click="downloadBudgetFile(file)">下载</el-button>
                </span>
              </div>
            </template>
            <span v-else class="budget-empty">—</span>
          </template>
        </el-table-column>
        <template #empty><el-empty :image-size="80" description="暂无支出记录" /></template>
      </el-table>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        class="budget-pagination"
        background
        layout="total, sizes, prev, pager, next"
        :total="budgetView?.expenses.total ?? 0"
        :page-sizes="[10, 20, 50]"
        @current-change="loadBudget"
        @size-change="changePageSize"
      />
      </template>

      <div v-if="canMaintain" class="budget-maintain">
        <div class="budget-section-title">维护总预算</div>
        <div class="budget-maintain__controls">
          <el-input-number
            v-model="budgetInput"
            :min="0"
            :precision="2"
            :value-on-clear="null"
            controls-position="right"
            placeholder="清空表示清除预算"
            class="budget-input"
          />
          <el-button type="primary" :loading="saving" @click="saveBudget">保存预算</el-button>
        </div>
        <el-input
          v-model="budgetReason"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          placeholder="预算调整原因（必填，500 字以内）"
          class="budget-reason"
        />
        <div class="budget-tip">预算不能低于已认定支出 {{ formatMoney(summarySpent) }} 万元；清除预算后填报不再受超支限制。</div>
      </div>
    </div>
  </el-dialog>
  <FilePreviewDrawer v-model="previewVisible" :file="previewFile" />
</template>

<style scoped>
.budget-dialog-content {
  min-height: 200px;
}

.dialog-heading h3 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 18px;
  line-height: 26px;
}

.dialog-heading p {
  margin: 5px 24px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 20px;
}

.dialog-heading p span {
  margin-right: 8px;
  color: var(--el-color-primary);
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}

.budget-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 18px;
}

.budget-summary > div {
  padding: 13px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-light);
}

.budget-summary span {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.budget-summary strong {
  display: block;
  margin-top: 5px;
  font-size: 22px;
  line-height: 30px;
  font-variant-numeric: tabular-nums;
}

.budget-summary strong.is-primary {
  color: var(--el-color-primary);
}

.budget-summary strong.is-danger {
  color: var(--el-color-danger);
}

.budget-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.budget-section-title {
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 600;
}

.budget-year-filter {
  width: 140px;
}

.mono {
  font-family: var(--app-font-mono);
  font-variant-numeric: tabular-nums;
}

.budget-file-row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 2px 0;
}

.budget-file-name {
  display: block;
  max-width: 100%;
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.budget-file-actions {
  display: inline-flex;
  gap: 8px;
}

.budget-file-actions :deep(.el-button) {
  height: auto;
  margin: 0;
  padding: 0;
}

.budget-empty {
  color: var(--el-text-color-secondary);
}

.budget-pagination {
  justify-content: flex-end;
  margin-top: 16px;
}

.budget-maintain {
  margin-top: 18px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}

.budget-maintain .budget-section-title {
  margin-bottom: 10px;
}

.budget-maintain__controls {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.budget-input {
  width: 220px;
}

.budget-reason {
  margin-bottom: 8px;
}

.budget-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 20px;
}

@media (max-width: 768px) {
  .budget-summary {
    grid-template-columns: minmax(0, 1fr);
  }

  .budget-maintain__controls {
    flex-wrap: wrap;
  }
}
</style>
