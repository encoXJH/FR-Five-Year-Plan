<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchFeishuBotStatus, listFeishuBotLogs, runFeishuBot } from '@/api/feishu'
import { getSettings, updateSettings } from '@/api/system'
import type {
  FeishuBotJob,
  FeishuBotLog,
  FeishuBotLogQuery,
  FeishuBotPushStatus,
  FeishuBotRunResult,
  FeishuBotStatus,
} from '@/types/feishu'
import { useUserStore } from '@/stores/user'
import { enumLabel, enumOptions } from '@/utils/enumLabels'
import { formatCount, formatDateTimeFull } from '@/utils/format'
import { newIdempotencyKey } from '@/utils/idempotency'
import { useAutoRefresh } from '@/utils/autoRefresh'
import { formatFeishuBotSchedule } from '@/utils/feishu'

const userStore = useUserStore()
const status = ref<FeishuBotStatus | null>(null)
const statusLoading = ref(false)
const statusError = ref('')
const pushSaving = ref(false)
const pushConfirming = ref(false)
const botSaving = ref(false)
const botConfirming = ref(false)
const switchBusy = computed(() => pushSaving.value || pushConfirming.value || botSaving.value || botConfirming.value)
const logs = ref<FeishuBotLog[]>([])
const logsLoading = ref(false)
const logsError = ref('')
const runningJob = ref<FeishuBotJob | null>(null)
const runResult = ref<(FeishuBotRunResult & { job: FeishuBotJob }) | null>(null)
const runError = ref('')
const detailVisible = ref(false)
const selectedLog = ref<FeishuBotLog | null>(null)

const filters = reactive({
  status: '' as FeishuBotPushStatus | '',
  type: '',
  createdDate: '',
  keyword: '',
})
const page = reactive({ page: 1, pageSize: 20, total: 0 })
const notificationTypeOptions = computed(() => enumOptions('NotificationType', userStore.enums))
const pushStatuses: Array<{ value: FeishuBotPushStatus; label: string }> = [
  { value: 'PENDING', label: '待发送' },
  { value: 'SENT', label: '已发送' },
  { value: 'FAILED', label: '发送失败' },
  { value: 'SKIPPED', label: '已跳过' },
]

function errorText(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

let statusGeneration = 0

async function loadStatus(silent = false): Promise<void> {
  const generation = ++statusGeneration
  if (!silent) statusLoading.value = true
  try {
    const result = await fetchFeishuBotStatus()
    if (generation !== statusGeneration || (silent && switchBusy.value)) return
    status.value = result
    statusError.value = ''
  } catch (error) {
    if (generation !== statusGeneration) return
    statusError.value = errorText(error, '机器人状态加载失败，请重试')
  } finally {
    if (!silent && generation === statusGeneration) statusLoading.value = false
  }
}

function buildQuery(): FeishuBotLogQuery {
  return {
    page: page.page,
    pageSize: page.pageSize,
    status: filters.status || undefined,
    type: filters.type || undefined,
    createdDate: filters.createdDate || undefined,
    keyword: filters.keyword.trim() || undefined,
  }
}

async function loadLogs(silent = false): Promise<void> {
  if (!silent) logsLoading.value = true
  try {
    const result = await listFeishuBotLogs(buildQuery())
    logs.value = result.items
    page.page = result.page
    page.pageSize = result.pageSize
    page.total = result.total
    logsError.value = ''
  } catch (error) {
    logsError.value = errorText(error, '推送日志加载失败，请重试')
  } finally {
    if (!silent) logsLoading.value = false
  }
}

function loadAll(silent = false): Promise<[void, void]> {
  return Promise.all([loadStatus(silent), loadLogs(silent)])
}

function search(): void {
  page.page = 1
  void loadLogs()
}

function resetFilters(): void {
  filters.status = ''
  filters.type = ''
  filters.createdDate = ''
  filters.keyword = ''
  search()
}

function changePage(value: number): void {
  page.page = value
  void loadLogs()
}

function changePageSize(value: number): void {
  page.pageSize = value
  page.page = 1
  void loadLogs()
}

function pushStatusLabel(value: string): string {
  return pushStatuses.find((item) => item.value === value)?.label ?? value
}

function pushStatusType(value: string): 'success' | 'warning' | 'danger' | 'info' {
  if (value === 'SENT') return 'success'
  if (value === 'FAILED') return 'danger'
  if (value === 'PENDING') return 'warning'
  return 'info'
}

function showDetail(row: FeishuBotLog): void {
  selectedLog.value = row
  detailVisible.value = true
}

async function handlePushToggle(): Promise<void> {
  if (!status.value || switchBusy.value) return
  const enabling = !status.value.pushEnabled
  pushConfirming.value = true
  try {
    await ElMessageBox.confirm(
      enabling
        ? '开启后将立即向已绑定用户真实发送待推送消息。确认开启？'
        : '关闭后立即停止后续飞书外发，待推送消息会保留。确认关闭？',
      enabling ? '开启消息推送' : '关闭消息推送',
      { confirmButtonText: enabling ? '确认开启' : '确认关闭', cancelButtonText: '取消',
        type: enabling ? 'warning' : 'info' },
    )
  } catch {
    return
  } finally {
    pushConfirming.value = false
  }

  pushSaving.value = true
  statusGeneration++
  let succeeded = false
  try {
    const settings = await getSettings()
    const setting = settings.items.find((item) => item.key === 'feishu.pushEnabled')
    if (!setting) throw new Error('消息推送开关参数不存在，请先执行数据库迁移')
    await updateSettings([{ key: setting.key, value: String(enabling), version: setting.version }])
    succeeded = true
  } catch {
    // 拦截器已提示；finally 中重新读取有效状态与版本。
  } finally {
    pushSaving.value = false
    await loadStatus(true)
  }
  if (succeeded) ElMessage.success(enabling ? '消息推送已开启' : '消息推送已关闭')
}

async function handleBotToggle(): Promise<void> {
  if (!status.value || switchBusy.value) return
  const enabling = !status.value.botEnabled
  botConfirming.value = true
  try {
    await ElMessageBox.confirm(
      enabling
        ? '启动后将按设定时间自动生成进度和校级汇总提醒，但不会自动开启消息推送。确认启动？'
        : '停用后将不再自动生成进度和校级汇总提醒，已生成的通知保留。确认停用？',
      enabling ? '启动机器人' : '停用机器人',
      { confirmButtonText: enabling ? '确认启动' : '确认停用', cancelButtonText: '取消',
        type: enabling ? 'info' : 'warning' },
    )
  } catch {
    return
  } finally {
    botConfirming.value = false
  }

  botSaving.value = true
  statusGeneration++
  let succeeded = false
  try {
    const settings = await getSettings()
    const setting = settings.items.find((item) => item.key === 'feishu.botEnabled')
    if (!setting) throw new Error('机器人开关参数不存在，请先执行数据库迁移')
    await updateSettings([{ key: setting.key, value: String(enabling), version: setting.version }])
    succeeded = true
  } catch {
    // 拦截器已提示；finally 中重新读取有效状态与版本。
  } finally {
    botSaving.value = false
    await loadStatus(true)
  }
  if (succeeded) ElMessage.success(enabling ? '机器人已启动' : '机器人已停用')
}

async function handleRun(job: FeishuBotJob): Promise<void> {
  const progress = job === 'PROGRESS'
  const action = progress ? '立即检查进度' : '立即生成校级汇总'
  const message = progress
    ? '将立即检查任务进度并生成应发送的提醒。确认执行？'
    : '将立即生成校级待办汇总并按推送规则处理。确认执行？'
  try {
    await ElMessageBox.confirm(message, action, { confirmButtonText: action, cancelButtonText: '取消' })
  } catch {
    return
  }

  runningJob.value = job
  runResult.value = null
  runError.value = ''
  try {
    const result = await runFeishuBot(job, newIdempotencyKey())
    runResult.value = { ...result, job }
    ElMessage.success(`${action}已执行`)
    void loadAll(true)
  } catch (error) {
    runError.value = errorText(error, `${action}失败，请重试`)
  } finally {
    runningJob.value = null
  }
}

onMounted(() => {
  void loadAll()
})

useAutoRefresh(() => loadAll(true), {
  skip: () => runningJob.value !== null || switchBusy.value,
})
</script>

<template>
  <div class="bot-panel">
    <section class="settings-group status-section">
      <div class="group-title">机器人状态</div>
      <el-alert
        v-if="statusError"
        type="error"
        :title="statusError"
        :closable="false"
        show-icon
        class="panel-alert"
      >
        <el-button link type="primary" @click="loadStatus()">重新加载</el-button>
      </el-alert>
      <div v-loading="statusLoading" class="status-content">
        <template v-if="status">
          <div class="status-grid">
            <div class="status-card">
              <span class="status-label">飞书连接</span>
              <el-tag :type="status.configured ? 'success' : 'danger'" effect="light">
                {{ status.configured ? '已配置' : '未配置' }}
              </el-tag>
            </div>
            <div class="status-card">
              <span class="status-label">消息推送</span>
              <div class="push-controls">
                <el-tag :type="status.pushEnabled ? 'success' : 'info'" effect="light">
                  {{ status.pushEnabled ? '已开启' : '已关闭' }}
                </el-tag>
                <el-button
                  size="small"
                  :type="status.pushEnabled ? 'danger' : 'success'"
                  plain
                  :loading="pushSaving"
                  :disabled="switchBusy"
                  @click="handlePushToggle"
                >{{ status.pushEnabled ? '关闭推送' : '开启推送' }}</el-button>
              </div>
            </div>
            <div class="status-card">
              <span class="status-label">机器人开关</span>
              <div class="push-controls">
                <el-tag :type="status.botEnabled ? 'success' : 'info'" effect="light">
                  {{ status.botEnabled ? '已启用' : '已停用' }}
                </el-tag>
                <el-button
                  size="small"
                  :type="status.botEnabled ? 'danger' : 'success'"
                  plain
                  :loading="botSaving"
                  :disabled="switchBusy"
                  @click="handleBotToggle"
                >{{ status.botEnabled ? '停用机器人' : '启动机器人' }}</el-button>
              </div>
            </div>
            <div class="status-card">
              <span class="status-label">服务器时区</span>
              <strong class="mono">{{ status.timezone }}</strong>
            </div>
            <div class="status-card schedule-card">
              <span class="status-label">进度检查</span>
              <strong>{{ formatFeishuBotSchedule(status.progressCron) }}</strong>
            </div>
            <div class="status-card schedule-card">
              <span class="status-label">校级汇总</span>
              <strong>{{ formatFeishuBotSchedule(status.leaderDigestCron) }}</strong>
            </div>
          </div>
        </template>
        <el-empty
          v-else-if="!statusLoading && !statusError"
          :image-size="70"
          description="暂无机器人状态"
        />
      </div>
    </section>

    <template v-if="status">
      <section class="settings-group">
        <div class="group-title">推送规则</div>
        <div v-if="status.rules.length > 0" class="rules-list">
          <div v-for="rule in status.rules" :key="rule.code" class="rule-item">
            <div class="rule-heading">
              <span class="rule-name">{{ rule.name }}</span>
              <el-tag size="small" effect="plain">{{ rule.code }}</el-tag>
            </div>
            <p>{{ rule.description }}</p>
          </div>
        </div>
        <el-empty v-else :image-size="60" description="暂无推送规则" />
      </section>

      <section class="settings-group">
        <div class="group-title">今日推送</div>
        <div class="today-grid">
          <div v-for="item in pushStatuses" :key="item.value" class="today-card">
            <span>{{ item.label }}</span>
            <strong>{{ formatCount(status.todayPush[item.value]) }}</strong>
          </div>
        </div>
      </section>
    </template>

    <section class="settings-group">
      <div class="group-title">手动执行</div>
      <div class="run-actions">
        <el-button
          type="primary"
          :loading="runningJob === 'PROGRESS'"
          :disabled="runningJob !== null"
          @click="handleRun('PROGRESS')"
        >立即检查进度</el-button>
        <el-button
          type="primary"
          plain
          :loading="runningJob === 'LEADER_DIGEST'"
          :disabled="runningJob !== null"
          @click="handleRun('LEADER_DIGEST')"
        >立即生成校级汇总</el-button>
        <span class="muted">手动执行与定时任务使用相同的去重规则</span>
      </div>
      <el-alert
        v-if="runResult"
        type="success"
        :closable="false"
        show-icon
        class="panel-alert result-alert"
      >
        <template #title>
          {{ runResult.job === 'PROGRESS' ? '进度检查' : '校级汇总' }}执行完成：
          扫描 {{ runResult.scanned }} 项，新建 {{ runResult.created }} 条，去重 {{ runResult.deduplicated }} 条
        </template>
      </el-alert>
      <el-alert
        v-else-if="runError"
        type="error"
        :title="runError"
        :closable="false"
        show-icon
        class="panel-alert result-alert"
      />
    </section>

    <section class="settings-group log-section">
      <div class="group-title">推送日志</div>
      <el-form inline class="filter-form" @submit.prevent>
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部" class="filter-control status-filter">
            <el-option
              v-for="item in pushStatuses"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="通知类型">
          <el-select v-model="filters.type" clearable filterable placeholder="全部" class="filter-control type-filter">
            <el-option
              v-for="item in notificationTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="创建日期">
          <el-date-picker
            v-model="filters.createdDate"
            type="date"
            value-format="YYYY-MM-DD"
            clearable
            placeholder="选择日期"
            class="filter-control date-filter"
          />
        </el-form-item>
        <el-form-item label="关键字">
          <el-input
            v-model="filters.keyword"
            clearable
            placeholder="标题、内容或失败原因"
            class="filter-control keyword-filter"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="logsError"
        type="error"
        :title="logsError"
        :closable="false"
        show-icon
        class="panel-alert"
      >
        <el-button link type="primary" @click="loadLogs()">重新加载</el-button>
      </el-alert>
      <el-table v-loading="logsLoading" :data="logs" border>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTimeFull(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="接收人" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.receiverName ?? row.userId }}</template>
        </el-table-column>
        <el-table-column label="通知类型" min-width="140">
          <template #default="{ row }">{{ enumLabel('NotificationType', row.type, userStore.enums) }}</template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column prop="content" label="内容" min-width="220" show-overflow-tooltip />
        <el-table-column label="推送状态" width="105" align="center">
          <template #default="{ row }">
            <el-tag :type="pushStatusType(row.feishuStatus)" effect="light">
              {{ pushStatusLabel(row.feishuStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="feishuRetryCount" label="重试次数" width="90" align="right" />
        <el-table-column label="失败原因" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'failure-text': row.feishuLastError }">{{ row.feishuLastError ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">查看内容</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            :image-size="80"
            :description="logsError ? '日志加载失败，请重新加载' : '暂无机器人推送日志'"
          />
        </template>
      </el-table>
      <div class="pagination-bar">
        <el-pagination
          background
          :current-page="page.page"
          :page-size="page.pageSize"
          :page-sizes="[20, 50, 100]"
          :total="page.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="changePage"
          @size-change="changePageSize"
        />
      </div>
    </section>

    <el-dialog v-model="detailVisible" title="推送内容" width="min(640px, 92vw)">
      <template v-if="selectedLog">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="接收人">
            {{ selectedLog.receiverName ?? selectedLog.userId }}
          </el-descriptions-item>
          <el-descriptions-item label="通知类型">
            {{ enumLabel('NotificationType', selectedLog.type, userStore.enums) }}
          </el-descriptions-item>
          <el-descriptions-item label="标题">{{ selectedLog.title }}</el-descriptions-item>
          <el-descriptions-item label="推送状态">
            <el-tag :type="pushStatusType(selectedLog.feishuStatus)" effect="light">
              {{ pushStatusLabel(selectedLog.feishuStatus) }}
            </el-tag>
            <span class="retry-text">重试 {{ selectedLog.feishuRetryCount }} 次</span>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTimeFull(selectedLog.createdAt) }}</el-descriptions-item>
        </el-descriptions>
        <div class="detail-block">
          <div class="detail-label">完整内容</div>
          <div class="detail-content">{{ selectedLog.content }}</div>
        </div>
        <div v-if="selectedLog.feishuLastError" class="detail-block failure-detail">
          <div class="detail-label">失败原因</div>
          <div class="detail-content">{{ selectedLog.feishuLastError }}</div>
        </div>
        <div v-if="selectedLog.link" class="detail-link">
          <el-link :href="selectedLog.link" target="_blank" rel="noopener noreferrer" type="primary">
            打开关联页面
          </el-link>
        </div>
      </template>
      <template #footer>
        <el-button type="primary" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bot-panel {
  max-width: 1280px;
}

.settings-group {
  margin-bottom: var(--space-5);
}

.group-title {
  margin-bottom: var(--space-3);
  padding-left: 8px;
  border-left: 3px solid var(--el-color-primary);
  font-size: 14px;
  font-weight: 600;
}

.panel-alert {
  margin-bottom: var(--space-3);
}

.status-content {
  min-height: 54px;
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: var(--space-3);
}

.status-card {
  display: flex;
  min-width: 0;
  min-height: 72px;
  padding: var(--space-3);
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: var(--space-2);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-blank);
}

.push-controls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.status-label,
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.schedule-card {
  grid-column: span 2;
}

.mono {
  font-family: var(--app-font-mono);
  font-variant-numeric: tabular-nums;
}

.rules-list {
  display: grid;
  gap: var(--space-2);
}

.rule-item {
  padding: var(--space-3);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
}

.rule-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.rule-name {
  font-weight: 600;
}

.rule-item p {
  margin: var(--space-2) 0 0;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.6;
}

.today-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-3);
}

.today-card {
  display: flex;
  padding: var(--space-3) var(--space-4);
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  color: var(--el-text-color-secondary);
}

.today-card strong {
  color: var(--el-text-color-primary);
  font-family: var(--app-font-mono);
  font-size: 24px;
  font-variant-numeric: tabular-nums;
}

.run-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
}

.run-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.result-alert {
  margin-top: var(--space-3);
}

.filter-form {
  margin-bottom: var(--space-2);
}

.filter-control {
  max-width: 100%;
}

.status-filter {
  width: 130px;
}

.type-filter {
  width: 180px;
}

.date-filter {
  width: 150px;
}

.keyword-filter {
  width: 220px;
}

.log-section {
  min-width: 0;
}

.failure-text,
.failure-detail .detail-label {
  color: var(--el-color-danger);
}

.pagination-bar {
  display: flex;
  margin-top: var(--space-4);
  justify-content: flex-end;
  overflow-x: auto;
}

.retry-text {
  margin-left: var(--space-2);
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.detail-block {
  margin-top: var(--space-4);
}

.detail-label {
  margin-bottom: var(--space-2);
  font-weight: 600;
}

.detail-content {
  padding: var(--space-3);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-light);
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.failure-detail .detail-content {
  border-color: var(--el-color-danger-light-7);
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}

.detail-link {
  margin-top: var(--space-3);
}

@media (max-width: 768px) {
  .schedule-card {
    grid-column: span 1;
  }

  .today-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .run-actions :deep(.el-button),
  .filter-form :deep(.el-button) {
    min-height: 44px;
  }

  .pagination-bar {
    justify-content: flex-start;
  }

  .pagination-bar :deep(.el-pagination) {
    flex-wrap: wrap;
    row-gap: var(--space-2);
  }
}

@media (max-width: 600px) {
  .filter-form,
  .filter-form :deep(.el-form-item),
  .filter-form :deep(.el-form-item__content),
  .filter-control {
    width: 100%;
  }

  .filter-form :deep(.el-form-item) {
    margin-right: 0;
  }
}
</style>
