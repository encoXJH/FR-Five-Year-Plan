<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElNotification } from 'element-plus'
import { listAuditLogs } from '@/api/audit'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { getUserOptions } from '@/api/user'
import { createExport, pollExport } from '@/api/export'
import { useUserStore } from '@/stores/user'
import type { AuditLogItem, AuditLogQuery } from '@/types/audit'
import type { UserOption } from '@/types/user'
import { enumLabel, enumOptions } from '@/utils/enumLabels'
import { formatDateTimeFull } from '@/utils/format'
import { useAutoRefresh } from '@/utils/autoRefresh'
import { newIdempotencyKey } from '@/utils/idempotency'
import {
  diffObjects,
  formatDiffValue,
  parseSnapshot,
  prettyJsonString,
} from '@/utils/jsonDiff'

const userStore = useUserStore()
const loading = ref(false)
const exporting = ref(false)
const logs = ref<AuditLogItem[]>([])
const units = ref<OrgUnit[]>([])
const users = ref<UserOption[]>([])

const filters = reactive({
  timeRange: null as [string, string] | null,
  unitId: '',
  actions: [] as string[],
  operatorId: '',
  resourceId: '',
})
const page = reactive({ page: 1, pageSize: 20, total: 0 })
const actionOptions = computed(() => enumOptions('AuditAction', userStore.enums))

function buildQuery(withPage = true): AuditLogQuery {
  return {
    occurredAtFrom: filters.timeRange?.[0],
    occurredAtTo: filters.timeRange?.[1],
    unitId: filters.unitId || undefined,
    action: filters.actions.length > 0 ? filters.actions.join(',') : undefined,
    operatorId: filters.operatorId || undefined,
    resourceType: filters.resourceId ? 'indicator' : undefined,
    resourceId: filters.resourceId.trim() || undefined,
    page: withPage ? page.page : undefined,
    pageSize: withPage ? page.pageSize : undefined,
  }
}

async function loadLogs(silent = false): Promise<void> {
  if (!silent) loading.value = true
  try {
    const result = await listAuditLogs(buildQuery())
    logs.value = result.items
    page.page = result.page
    page.pageSize = result.pageSize
    page.total = result.total
  } finally {
    if (!silent) loading.value = false
  }
}

function search(): void {
  page.page = 1
  void loadLogs()
}

function resetFilters(): void {
  filters.timeRange = null
  filters.unitId = ''
  filters.actions = []
  filters.operatorId = ''
  filters.resourceId = ''
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

function roleText(roles: string | null): string {
  if (!roles) return '—'
  return roles.split(',').map((role) => enumLabel('Role', role, userStore.enums)).join('、')
}

function logDiff(row: AuditLogItem) {
  return diffObjects(parseSnapshot(row.before), parseSnapshot(row.after))
}

async function copyRequestId(requestId: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(requestId)
    ElMessage.success('requestId 已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

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
      {
        type: 'AUDIT_LOGS',
        params: {
          ...buildQuery(false),
          action: filters.actions.length > 0 ? filters.actions : undefined,
        },
      },
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

async function loadOptions(): Promise<void> {
  const [unitResult, userResult] = await Promise.allSettled([listOrgUnits(), getUserOptions()])
  if (unitResult.status === 'fulfilled') units.value = unitResult.value
  if (userResult.status === 'fulfilled') users.value = userResult.value
}

onMounted(() => {
  void loadLogs()
  void loadOptions()
})

useAutoRefresh(() => loadLogs(true), { intervalMs: 60_000 })
</script>

<template>
  <div class="page-header">
    <div>
      <h1 class="page-title">操作日志</h1>
      <p class="page-subtitle">日志只能追加，任何人不能修改或删除</p>
    </div>
    <el-button
      v-if="userStore.can('audit-log:export')"
      type="primary"
      :loading="exporting"
      @click="handleExport"
    >
      导出 Excel
    </el-button>
  </div>

  <el-card shadow="never" class="filter-card">
    <el-form inline @submit.prevent>
      <el-form-item label="时间范围">
        <el-date-picker
          v-model="filters.timeRange"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ssZ"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 340px"
        />
      </el-form-item>
      <el-form-item label="执行单元">
        <el-select v-model="filters.unitId" clearable filterable placeholder="全部" style="width: 190px">
          <el-option
            v-for="unit in units"
            :key="unit.id"
            :label="`${unit.code} ${unit.name}`"
            :value="unit.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="操作类型">
        <el-select
          v-model="filters.actions"
          multiple
          collapse-tags
          :max-collapse-tags="2"
          clearable
          placeholder="全部"
          style="width: 220px"
        >
          <el-option
            v-for="option in actionOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="操作人">
        <el-select v-model="filters.operatorId" clearable filterable placeholder="全部" style="width: 150px">
          <el-option
            v-for="user in users"
            :key="user.id"
            :label="`${user.name}（${user.username}）`"
            :value="user.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="指标 ID">
        <el-input v-model="filters.resourceId" clearable placeholder="按指标精确查询" style="width: 190px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>
  </el-card>

  <el-card shadow="never">
    <el-table v-loading="loading" :data="logs" border>
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="log-detail">
            <template v-if="logDiff(row).length > 0">
              <div class="detail-title">字段差异</div>
              <div v-for="entry in logDiff(row)" :key="entry.field" class="diff-row">
                <span class="mono diff-field">{{ entry.field }}</span>
                <span class="diff-before">{{ formatDiffValue(entry.before) }}</span>
                <span>→</span>
                <span class="diff-after">{{ formatDiffValue(entry.after) }}</span>
              </div>
            </template>
            <div class="snapshot-grid">
              <div>
                <div class="detail-title">变更前 JSON</div>
                <pre>{{ prettyJsonString(row.before) || '—' }}</pre>
              </div>
              <div>
                <div class="detail-title">变更后 JSON</div>
                <pre>{{ prettyJsonString(row.after) || '—' }}</pre>
              </div>
            </div>
            <div v-if="row.reason" class="reason"><strong>原因：</strong>{{ row.reason }}</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatDateTimeFull(row.occurredAt) }}</template>
      </el-table-column>
      <el-table-column label="操作人" width="100">
        <template #default="{ row }">{{ row.operator?.name ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="角色" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ roleText(row.roles) }}</template>
      </el-table-column>
      <el-table-column label="所属单元" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.unit ? `${row.unit.code} ${row.unit.name}` : '—' }}</template>
      </el-table-column>
      <el-table-column label="操作类型" width="120" align="center">
        <template #default="{ row }">
          <el-tag :type="['DELETE', 'UNLOCK', 'PERMISSION_CHANGE'].includes(row.action) ? 'danger' : 'primary'">
            {{ enumLabel('AuditAction', row.action, userStore.enums) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="对象" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ row.resourceCode ?? row.resourceId ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="摘要" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.reason ?? row.resourceCode ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="终端 / IP" min-width="160">
        <template #default="{ row }">
          {{ row.clientType ? enumLabel('ClientType', row.clientType, userStore.enums) : '—' }}
          <span v-if="row.ip"> / {{ row.ip }}</span>
        </template>
      </el-table-column>
      <el-table-column label="requestId" min-width="190">
        <template #default="{ row }">
          <span class="mono request-id">{{ row.requestId }}</span>
          <el-button link type="primary" @click="copyRequestId(row.requestId)">复制</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :image-size="80" description="暂无操作日志" />
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
  </el-card>
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
}

.log-detail {
  padding: var(--space-3) var(--space-6);
}

.detail-title {
  margin-bottom: var(--space-2);
  font-weight: 600;
}

.diff-row {
  display: flex;
  gap: var(--space-2);
  align-items: baseline;
  margin-bottom: var(--space-1);
  font-size: 12px;
}

.diff-field {
  min-width: 180px;
}

.diff-before {
  color: var(--el-color-danger);
  text-decoration: line-through;
}

.diff-after {
  color: var(--el-color-success);
  font-weight: 600;
}

.snapshot-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
  margin-top: var(--space-4);
}

pre {
  max-height: 280px;
  margin: 0;
  padding: var(--space-3);
  overflow: auto;
  background: var(--el-fill-color-light);
  border-radius: var(--el-border-radius-base);
  font-family: var(--app-font-mono);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.reason {
  margin-top: var(--space-3);
}

.mono {
  font-family: var(--app-font-mono);
}

.request-id {
  margin-right: var(--space-2);
  font-size: 12px;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}
</style>
