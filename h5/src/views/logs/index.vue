<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { showToast } from 'vant'
import PageState from '@/components/PageState.vue'
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
import { newIdempotencyKey } from '@/utils/idempotency'
import { buildAuditDetail } from '@/utils/auditDetail'
import { triggerFileDownload } from '@/utils/file'

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

const filterVisible = ref(false)
const unitPickerVisible = ref(false)
const operatorPickerVisible = ref(false)
const calendarVisible = ref(false)
const unitLabel = computed(() => units.value.find((item) => item.id === filters.unitId)?.name ?? '全部单元')
const operatorLabel = computed(() => users.value.find((item) => item.id === filters.operatorId)?.name ?? '全部操作人')
const timeLabel = computed(() =>
  filters.timeRange ? `${filters.timeRange[0].slice(0, 10)} 至 ${filters.timeRange[1].slice(0, 10)}` : '全部时间')

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

async function loadLogs(): Promise<void> {
  loading.value = true
  try {
    const result = await listAuditLogs(buildQuery())
    logs.value = result.items
    page.page = result.page
    page.pageSize = result.pageSize
    page.total = result.total
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.page = 1
  filterVisible.value = false
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

function changePage(next: number): void {
  page.page = next
  void loadLogs()
}

function roleText(roles: string | null): string {
  if (!roles) return '—'
  return roles.split(',').map((role) => enumLabel('Role', role, userStore.enums)).join('、')
}

function toggleAction(value: string): void {
  filters.actions = filters.actions.includes(value)
    ? filters.actions.filter((item) => item !== value)
    : [...filters.actions, value]
}

const detailVisible = ref(false)
const selectedLog = ref<AuditLogItem | null>(null)
const detail = computed(() => (selectedLog.value ? buildAuditDetail(selectedLog.value) : null))

function openDetail(row: AuditLogItem): void {
  selectedLog.value = row
  detailVisible.value = true
}

async function copyRequestId(requestId: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(requestId)
      showToast('requestId 已复制')
      return
    } catch {
      // 降级
    }
  }
  const input = document.createElement('textarea')
  input.value = requestId
  document.body.appendChild(input)
  input.select()
  document.execCommand('copy')
  input.remove()
  showToast('requestId 已复制')
}

async function handleExport(): Promise<void> {
  exporting.value = true
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
    if (job.status === 'SUCCEEDED' && job.downloadUrl) {
      triggerFileDownload(job.downloadUrl, '操作日志.xlsx')
      showToast('导出完成，已开始下载')
    } else {
      showToast(job.errorMessage ?? '导出任务失败')
    }
  } catch (error) {
    showToast(error instanceof Error ? error.message : '导出任务失败')
  } finally {
    exporting.value = false
  }
}

onMounted(async () => {
  void loadLogs()
  const [unitResult, userResult] = await Promise.allSettled([listOrgUnits(), getUserOptions()])
  if (unitResult.status === 'fulfilled') units.value = unitResult.value
  if (userResult.status === 'fulfilled') users.value = userResult.value
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>操作日志</h1>
        <p class="muted small">日志只能追加，任何人不能修改或删除</p>
      </div>
      <van-button
        v-if="userStore.can('audit-log:export')"
        size="small"
        type="primary"
        :loading="exporting"
        @click="handleExport"
      >导出 Excel</van-button>
    </div>

    <div class="filter-bar">
      <button type="button" class="filter-btn" @click="filterVisible = true">
        <van-icon name="filter-o" /> 筛选
        <span v-if="filters.actions.length || filters.unitId || filters.operatorId || filters.resourceId" class="chip">已设</span>
      </button>
      <span class="small muted">{{ timeLabel }}</span>
    </div>

    <PageState :loading="loading" :empty="logs.length === 0" empty-text="暂无操作日志">
      <div class="log-list">
        <article v-for="row in logs" :key="row.id" class="log-card" @click="openDetail(row)">
          <div class="row--between">
            <span class="log-action" :class="{ 'is-danger': ['DELETE', 'UNLOCK', 'PERMISSION_CHANGE'].includes(row.action) }">
              {{ enumLabel('AuditAction', row.action, userStore.enums) }}
            </span>
            <span class="small muted">{{ formatDateTimeFull(row.occurredAt) }}</span>
          </div>
          <div class="small">{{ row.operator?.name ?? '—' }} · {{ roleText(row.roles) }}</div>
          <div class="small muted">
            {{ row.unit ? `${row.unit.code} ${row.unit.name}` : '—' }}
            · {{ row.resourceCode ?? row.resourceId ?? '—' }}
          </div>
          <div class="small ellipsis-2">{{ row.reason ?? '—' }}</div>
        </article>
      </div>
      <div class="pager">
        <van-button size="small" :disabled="page.page <= 1" @click="changePage(page.page - 1)">上一页</van-button>
        <span class="small muted">{{ page.page }} / {{ Math.max(1, Math.ceil(page.total / page.pageSize)) }}（共 {{ page.total }} 条）</span>
        <van-button
          size="small"
          :disabled="page.page >= Math.ceil(page.total / page.pageSize)"
          @click="changePage(page.page + 1)"
        >下一页</van-button>
      </div>
    </PageState>

    <van-popup v-model:show="filterVisible" position="bottom" round :style="{ maxHeight: '86vh' }">
      <div class="sheet">
        <div class="sheet-head">
          <strong>筛选日志</strong>
          <button type="button" class="close-btn" @click="filterVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="sheet-body">
          <div class="field">
            <label>时间范围</label>
            <button type="button" class="select-btn" @click="calendarVisible = true">{{ timeLabel }}</button>
          </div>
          <div class="field">
            <label>执行单元</label>
            <button type="button" class="select-btn" @click="unitPickerVisible = true">{{ unitLabel }}</button>
          </div>
          <div class="field">
            <label>操作类型（可多选）</label>
            <div class="chip-group">
              <button
                v-for="option in actionOptions"
                :key="option.value"
                type="button"
                class="filter-chip"
                :class="{ 'is-active': filters.actions.includes(option.value) }"
                @click="toggleAction(option.value)"
              >{{ option.label }}</button>
            </div>
          </div>
          <div class="field">
            <label>操作人</label>
            <button type="button" class="select-btn" @click="operatorPickerVisible = true">{{ operatorLabel }}</button>
          </div>
          <div class="field">
            <label>指标 ID</label>
            <van-field v-model="filters.resourceId" placeholder="按指标 ID 精确查询" />
          </div>
        </div>
        <div class="sheet-actions">
          <van-button block @click="resetFilters">重置</van-button>
          <van-button block type="primary" @click="search">查询</van-button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="unitPickerVisible" position="bottom" round :style="{ height: '65vh' }">
      <div class="sheet">
        <div class="sheet-head">
          <strong>选择执行单元</strong>
          <button type="button" class="close-btn" @click="unitPickerVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="picker-list">
          <button type="button" class="picker-item" @click="filters.unitId = ''; unitPickerVisible = false">全部单元</button>
          <button
            v-for="unit in units"
            :key="unit.id"
            type="button"
            class="picker-item"
            @click="filters.unitId = unit.id; unitPickerVisible = false"
          >{{ unit.code }} {{ unit.name }}</button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="operatorPickerVisible" position="bottom" round :style="{ height: '65vh' }">
      <div class="sheet">
        <div class="sheet-head">
          <strong>选择操作人</strong>
          <button type="button" class="close-btn" @click="operatorPickerVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="picker-list">
          <button type="button" class="picker-item" @click="filters.operatorId = ''; operatorPickerVisible = false">全部操作人</button>
          <button
            v-for="user in users"
            :key="user.id"
            type="button"
            class="picker-item"
            @click="filters.operatorId = user.id; operatorPickerVisible = false"
          >{{ user.name }}（{{ user.username }}）</button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="calendarVisible" position="bottom" round>
      <van-calendar
        type="range"
        :min-date="new Date(2020, 0, 1)"
        :max-date="new Date(2100, 11, 31)"
        @confirm="(dates: Date[]) => {
          const format = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}T00:00:00+08:00`
          filters.timeRange = [format(dates[0]), format(dates[1])]
          calendarVisible = false
        }"
        @cancel="calendarVisible = false"
      />
    </van-popup>

    <van-popup v-model:show="detailVisible" position="bottom" round :style="{ height: '80vh' }">
      <div class="sheet">
        <div class="sheet-head">
          <strong>日志详情</strong>
          <button type="button" class="close-btn" @click="detailVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div v-if="selectedLog" class="sheet-body">
          <div class="log-summary">{{ detail?.summary }}</div>
          <div class="kv-list">
            <div class="kv"><span class="kv__label">时间</span><span class="kv__value">{{ formatDateTimeFull(selectedLog.occurredAt) }}</span></div>
            <div class="kv"><span class="kv__label">操作人</span><span class="kv__value">{{ selectedLog.operator?.name ?? '—' }}</span></div>
            <div class="kv"><span class="kv__label">角色</span><span class="kv__value">{{ roleText(selectedLog.roles) }}</span></div>
            <div class="kv"><span class="kv__label">所属单元</span><span class="kv__value">{{ selectedLog.unit ? `${selectedLog.unit.code} ${selectedLog.unit.name}` : '—' }}</span></div>
            <div class="kv"><span class="kv__label">操作类型</span><span class="kv__value">{{ enumLabel('AuditAction', selectedLog.action, userStore.enums) }}</span></div>
            <div class="kv"><span class="kv__label">对象</span><span class="kv__value">{{ selectedLog.resourceType ?? '—' }} / {{ selectedLog.resourceCode ?? selectedLog.resourceId ?? '—' }}</span></div>
            <div class="kv"><span class="kv__label">终端 / IP</span><span class="kv__value">{{ selectedLog.clientType ? enumLabel('ClientType', selectedLog.clientType, userStore.enums) : '—' }}{{ selectedLog.ip ? ` / ${selectedLog.ip}` : '' }}</span></div>
            <div class="kv"><span class="kv__label">requestId</span><span class="kv__value mono">{{ selectedLog.requestId }}</span></div>
          </div>
          <van-button size="small" plain type="primary" @click="copyRequestId(selectedLog.requestId)">复制 requestId</van-button>

          <template v-if="detail && detail.changes.length > 0">
            <div class="section-title">具体变更</div>
            <div class="change-list">
              <div class="change-row change-head"><span>字段</span><span>变更前</span><span>变更后</span></div>
              <div v-for="entry in detail.changes" :key="entry.field" class="change-row">
                <span class="strong">{{ entry.label }}</span>
                <span class="text-danger">{{ entry.before }}</span>
                <span class="text-success">{{ entry.after }}</span>
              </div>
            </div>
          </template>
          <template v-else-if="detail && detail.facts.length > 0">
            <div class="section-title">操作内容</div>
            <div class="kv-list">
              <div v-for="(fact, index) in detail.facts" :key="index" class="kv">
                <span class="kv__label">{{ fact.label }}</span><span class="kv__value">{{ fact.value }}</span>
              </div>
            </div>
          </template>
          <div v-if="detail?.note" class="info-box">{{ detail.note }}</div>
          <div v-if="selectedLog.reason" class="reason-box"><strong>原因或意见：</strong>{{ selectedLog.reason }}</div>
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
  font-size: 22px;
}
.page-head p {
  margin: 3px 0 0;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.filter-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 17px;
  background: #fff;
  color: var(--text);
  font-size: 12px;
}
.log-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.log-card {
  padding: 10px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.log-action {
  color: var(--brand);
  font-size: 13px;
  font-weight: 600;
}
.log-action.is-danger {
  color: var(--danger);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 12px 0;
}
.sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 14px 16px calc(14px + var(--safe-bottom));
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
.sheet-body {
  flex: 1;
  overflow-y: auto;
}
.field {
  margin-bottom: 12px;
}
.field label {
  display: block;
  margin-bottom: 4px;
  color: var(--text-secondary);
  font-size: 13px;
}
.field :deep(.van-field),
.select-btn {
  width: 100%;
  min-height: 42px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  padding: 10px 12px;
  color: var(--text);
  text-align: left;
  font-size: 13px;
}
.chip-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.sheet-actions {
  display: flex;
  gap: 10px;
  margin-top: 12px;
}
.picker-list {
  flex: 1;
  overflow-y: auto;
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
.log-summary {
  margin-bottom: 10px;
  padding: 10px;
  border-left: 3px solid var(--brand);
  border-radius: 8px;
  background: #f8fafd;
  font-size: 13px;
  font-weight: 600;
  line-height: 20px;
}
.mono {
  font-family: Consolas, Menlo, monospace;
  font-size: 11px;
  word-break: break-all;
}
.change-list {
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
}
.change-row {
  display: grid;
  grid-template-columns: 80px 1fr 1fr;
  gap: 8px;
  padding: 8px;
  border-top: 1px solid var(--line);
  font-size: 11px;
  word-break: break-all;
}
.change-row:first-child {
  border-top: none;
}
.change-head {
  background: #f8fafd;
  color: var(--text-secondary);
  font-weight: 600;
}
.info-box,
.reason-box {
  margin-top: 10px;
  padding: 10px;
  border-radius: 8px;
  background: #f8fafd;
  font-size: 12px;
  line-height: 18px;
}
</style>
