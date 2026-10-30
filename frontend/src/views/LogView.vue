<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { CopyDocument, Download, Refresh, Search, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorMessage, governanceApi, optionsApi } from '@/api'
import type { ID, LogMeta, OperationLog, Organization } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { actionLabels, objectTypeLabels, roleLabels } from '@/utils/dictionaries'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import PlanSelect from '@/components/PlanSelect.vue'
import RemoteState from '@/components/RemoteState.vue'

const router = useRouter()
const auth = useAuthStore()
const rows = ref<OperationLog[]>([])
const colleges = ref<Organization[]>([])
const meta = ref<LogMeta>()
const total = ref(0)
const loading = ref(false)
const metaLoading = ref(false)
const exporting = ref(false)
const error = ref('')
const drawer = ref(false)
const current = ref<OperationLog>()
const query = reactive({
  page: 1,
  page_size: 20,
  dates: [] as string[],
  plan_id: undefined as ID | undefined,
  college_org_id: undefined as ID | undefined,
  operator_user_id: undefined as ID | undefined,
  object_type: '',
  action_code: '',
  keyword: '',
})
const isAdmin = computed(() => auth.roles.includes('ADMIN'))
const isCollegeOnly = computed(() => auth.roles.includes('COLLEGE_OWNER') && !auth.roles.includes('SCHOOL_OWNER') && !auth.roles.includes('SCHOOL_LEADER') && !isAdmin.value)
const canExport = computed(() => !isCollegeOnly.value)
const stats = computed(() => meta.value?.stats || { total: 0, today: 0, last_7_days: 0, operator_count: 0 })
const actionOptions = computed(() => (meta.value?.actions || []).map((item) => ({ value: item.code, label: `${actionLabels[item.code] || item.code}（${item.count}）` })))
const objectOptions = computed(() => (meta.value?.object_types || []).map((item) => ({ value: item.code, label: `${objectTypeLabels[item.code] || item.code}（${item.count}）` })))
const operatorOptions = computed(() => (meta.value?.operators || []).map((item) => ({ value: item.id, label: `${item.user_name || item.id}（${item.account_name || '—'}）· ${item.count}` })))
const params = computed(() => ({
  page: query.page,
  page_size: query.page_size,
  start_time: query.dates?.[0] ? `${query.dates[0]} 00:00:00` : undefined,
  end_time: query.dates?.[1] ? `${query.dates[1]} 23:59:59` : undefined,
  plan_id: query.plan_id,
  college_org_id: query.college_org_id,
  operator_user_id: query.operator_user_id,
  object_type: query.object_type || undefined,
  action_code: query.action_code || undefined,
  keyword: query.keyword || undefined,
}))
async function load() {
  loading.value = true; error.value = ''
  try { const result = await governanceApi.logs(params.value); rows.value = result.items; total.value = result.total } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
async function loadMeta() {
  metaLoading.value = true
  try { meta.value = await governanceApi.logMeta() } catch { meta.value = undefined } finally { metaLoading.value = false }
}
function search() { query.page = 1; load() }
function showDetail(row: OperationLog) { current.value = row; drawer.value = true }
function objectLink(row?: OperationLog | null) {
  if (!row) return null
  if (row.object_type === 'PLAN') return `/planning/indicators?plan_id=${row.object_id}`
  if (row.object_type === 'INDICATOR') return `/planning/indicators/${row.object_id}`
  if (row.object_type === 'TASK') return `/execution/tasks/${row.object_id}`
  if (row.object_type === 'TRANSFER') return `/adjustments/transfers/${row.object_id}`
  if (row.object_type === 'IMPORT_FILE') return `/planning/imports/${row.object_id}`
  if (row.object_type === 'CARRYOVER') return '/adjustments/carryovers'
  if (row.object_type === 'REVIEW' && row.related_order_id) return `/execution/reviews/${row.related_order_id}`
  if (row.object_type === 'USER' && isAdmin.value) return '/admin/users'
  if (row.object_type === 'ORG' && isAdmin.value) return '/admin/organizations'
  if (row.object_type === 'PROJECT_MEMBER' && isAdmin.value) return '/admin/members'
  return null
}
function relatedLink(row?: OperationLog | null) {
  if (!row?.related_order_type || !row.related_order_id) return null
  if (row.related_order_type === 'TRANSFER') return `/adjustments/transfers/${row.related_order_id}`
  if (row.related_order_type === 'REVIEW') return `/execution/reviews/${row.related_order_id}`
  if (row.related_order_type === 'CARRYOVER') return '/adjustments/carryovers'
  return null
}
function jump(path: string | null) { if (path) { drawer.value = false; router.push(path) } }
async function copyLogNo() {
  if (!current.value) return
  try { await navigator.clipboard.writeText(current.value.log_no); ElMessage.success('日志编号已复制') } catch { ElMessage.warning('复制失败，请手动选择日志编号') }
}
async function exportCsv() {
  exporting.value = true
  try {
    const blob = await governanceApi.exportLogs({ ...params.value, page: undefined, page_size: undefined })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `操作日志_${new Date().toISOString().slice(0, 19).replaceAll(':', '-')}.csv`; document.body.appendChild(anchor); anchor.click(); anchor.remove(); URL.revokeObjectURL(url)
    ElMessage.success('已按当前筛选导出 CSV，导出动作已留痕')
    await Promise.all([load(), loadMeta()])
  } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { exporting.value = false }
}
function clear() {
  Object.assign(query, { page: 1, dates: [], plan_id: undefined, college_org_id: undefined, operator_user_id: undefined, object_type: '', action_code: '', keyword: '' })
  load()
}
function snapshotText(value?: Record<string, unknown> | null) {
  if (!value || !Object.keys(value).length) return '无变更快照'
  return JSON.stringify(value, null, 2)
}
onMounted(async () => {
  await Promise.all([load(), loadMeta()])
  if (!isCollegeOnly.value) { try { colleges.value = (await optionsApi.organizations({ org_type: 'COLLEGE' })).items } catch { colleges.value = [] } }
})
</script>
<template>
  <div class="page-container">
    <PageHeader title="全链路操作日志" description="记录动作发生时的操作人、角色、业务对象、业务原因及前后摘要。日志不可修改或删除，查询与导出范围不超出当前数据权限。">
      <template #actions><el-button :loading="metaLoading" @click="loadMeta"><el-icon><Refresh /></el-icon>刷新统计</el-button><el-button v-if="canExport" type="primary" :loading="exporting" @click="exportCsv"><el-icon><Download /></el-icon>导出 CSV</el-button></template>
    </PageHeader>
    <div class="stat-grid">
      <div class="stat-card" style="--stat-color:#e8f2ff"><div class="stat-label">日志总数</div><div class="stat-value">{{ stats.total }}</div><div class="stat-note">当前数据权限内全部记录</div></div>
      <div class="stat-card" style="--stat-color:#e9f7f0"><div class="stat-label">今日操作</div><div class="stat-value">{{ stats.today }}</div><div class="stat-note">今日 00:00 起的日志数</div></div>
      <div class="stat-card" style="--stat-color:#fff4e6"><div class="stat-label">近 7 天操作</div><div class="stat-value">{{ stats.last_7_days }}</div><div class="stat-note">最近 7 天日志数</div></div>
      <div class="stat-card" style="--stat-color:#f2edff"><div class="stat-label">涉及操作人</div><div class="stat-value">{{ stats.operator_count }}</div><div class="stat-note">去重后的操作账号数</div></div>
    </div>
    <div class="filter-bar">
      <el-date-picker v-model="query.dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px" />
      <div style="width:240px"><PlanSelect v-model="query.plan_id" placeholder="全部计划" @change="search" /></div>
      <el-select v-if="!isCollegeOnly" v-model="query.college_org_id" placeholder="学院" clearable filterable style="width:160px"><el-option v-for="org in colleges" :key="org.id" :value="org.id" :label="org.org_name" /></el-select>
      <el-select v-model="query.operator_user_id" placeholder="操作人" clearable filterable style="width:190px"><el-option v-for="item in operatorOptions" :key="item.value" :value="item.value" :label="item.label" /></el-select>
      <el-select v-model="query.object_type" placeholder="对象层级" clearable style="width:160px"><el-option v-for="item in objectOptions" :key="item.value" :value="item.value" :label="item.label" /></el-select>
      <el-select v-model="query.action_code" placeholder="操作类型" clearable filterable style="width:160px"><el-option v-for="item in actionOptions" :key="item.value" :value="item.value" :label="item.label" /></el-select>
      <el-input v-model="query.keyword" placeholder="日志号／对象／操作人／单号" clearable style="width:220px" @keyup.enter="search"><template #prefix><el-icon><Search /></el-icon></template></el-input>
      <el-button type="primary" plain @click="search">查询</el-button>
      <el-button @click="clear">清除</el-button>
    </div>
    <RemoteState :loading="loading" :error="error" :empty="!rows.length" empty-text="当前筛选下无日志" @retry="load">
      <el-table :data="rows">
        <el-table-column label="操作时间" width="150"><template #default="{ row }">{{ formatDateTime(row.operated_at) }}</template></el-table-column>
        <el-table-column label="操作人／角色" width="150"><template #default="{ row }">{{ row.operator_user_name || row.operator_user_id }}<div class="subtle small">{{ row.operator_account || '—' }} · {{ roleLabels[row.operator_role_code] || row.operator_role_code }}</div></template></el-table-column>
        <el-table-column label="所属计划" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ row.plan_name || '平台级操作' }}</template></el-table-column>
        <el-table-column label="学院" width="110" show-overflow-tooltip><template #default="{ row }">{{ row.college_org_name || '—' }}</template></el-table-column>
        <el-table-column label="业务对象" min-width="200"><template #default="{ row }"><el-tag size="small" effect="plain">{{ objectTypeLabels[row.object_type] || row.object_type }}</el-tag><span style="margin-left:6px">{{ row.object_name || `#${row.object_id}` }}</span></template></el-table-column>
        <el-table-column label="动作" width="120"><template #default="{ row }"><el-tag size="small" :type="row.action_code === 'DELETE' || row.action_code === 'DELETE_DRAFT' ? 'danger' : row.action_code === 'LOGIN' ? 'info' : 'primary'" effect="light">{{ actionLabels[row.action_code] || row.action_code }}</el-tag></template></el-table-column>
        <el-table-column prop="operation_reason" label="业务原因" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ row.operation_reason || '—' }}</template></el-table-column>
        <el-table-column label="关联单据" width="130"><template #default="{ row }">{{ row.related_order_type ? `${row.related_order_type} #${row.related_order_id}` : '—' }}</template></el-table-column>
        <el-table-column label="操作" width="64" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="showDetail(row)"><el-icon><View /></el-icon>详情</el-button></template></el-table-column>
      </el-table>
      <div class="table-footer"><el-pagination v-model:current-page="query.page" v-model:page-size="query.page_size" :total="total" :page-sizes="[20, 50, 100, 200]" layout="total, sizes, prev, pager, next" @change="load" /></div>
    </RemoteState>
    <el-drawer v-model="drawer" title="日志完整记录" size="620px">
      <template v-if="current">
        <div class="log-head"><span class="mono">{{ current.log_no }}</span><el-button link type="primary" @click="copyLogNo"><el-icon><CopyDocument /></el-icon>复制</el-button></div>
        <dl class="description-grid two-cols">
          <div class="description-item"><dt>操作时间</dt><dd>{{ formatDateTime(current.operated_at) }}</dd></div>
          <div class="description-item"><dt>操作时角色</dt><dd>{{ roleLabels[current.operator_role_code] || current.operator_role_code }}</dd></div>
          <div class="description-item"><dt>操作人</dt><dd>{{ current.operator_user_name || current.operator_user_id }}</dd></div>
          <div class="description-item"><dt>操作账号</dt><dd class="mono">{{ current.operator_account || '—' }}</dd></div>
          <div class="description-item"><dt>所属计划</dt><dd>{{ current.plan_name || '平台级操作' }}</dd></div>
          <div class="description-item"><dt>学院</dt><dd>{{ current.college_org_name || '—' }}</dd></div>
          <div class="description-item"><dt>对象层级</dt><dd>{{ objectTypeLabels[current.object_type] || current.object_type }}</dd></div>
          <div class="description-item"><dt>对象标识</dt><dd>#{{ current.object_id }}</dd></div>
          <div class="description-item"><dt>对象名称</dt><dd>{{ current.object_name || '—' }}</dd></div>
          <div class="description-item"><dt>操作动作</dt><dd>{{ actionLabels[current.action_code] || current.action_code }}</dd></div>
          <div class="description-item"><dt>关联单据</dt><dd>{{ current.related_order_type ? `${current.related_order_type} #${current.related_order_id}` : '—' }}</dd></div>
          <div class="description-item"><dt>导出批次</dt><dd class="mono">{{ current.export_batch_no || '—' }}</dd></div>
          <div class="description-item span-2"><dt>业务原因</dt><dd>{{ current.operation_reason || '—' }}</dd></div>
        </dl>
        <div class="log-actions"><el-button v-if="objectLink(current)" type="primary" plain @click="jump(objectLink(current))">查看业务对象</el-button><el-button v-if="relatedLink(current)" plain @click="jump(relatedLink(current))">查看关联单据</el-button></div>
        <h4 class="section-title" style="margin-top:18px">调整前摘要</h4>
        <pre class="snapshot-box">{{ snapshotText(current.before_snapshot_json) }}</pre>
        <h4 class="section-title" style="margin-top:15px">调整后摘要</h4>
        <pre class="snapshot-box">{{ snapshotText(current.after_snapshot_json) }}</pre>
      </template>
    </el-drawer>
  </div>
</template>
<style scoped>
.two-cols{grid-template-columns:1fr 1fr}
.log-head{display:flex;align-items:center;gap:10px;margin-bottom:14px;color:#4a5a75;font-size:13px}
.log-actions{display:flex;gap:10px;margin-top:14px}
.snapshot-box{max-height:320px}
</style>
