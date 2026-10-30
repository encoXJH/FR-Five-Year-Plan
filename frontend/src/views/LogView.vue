<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Download, Search, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorMessage, governanceApi, optionsApi } from '@/api'
import type { ID, OperationLog, Organization } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { actionLabels, objectTypeLabels, roleLabels } from '@/utils/dictionaries'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import PlanSelect from '@/components/PlanSelect.vue'
import RemoteState from '@/components/RemoteState.vue'
const auth = useAuthStore()
const rows = ref<OperationLog[]>([])
const colleges = ref<Organization[]>([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const error = ref('')
const drawer = ref(false)
const current = ref<OperationLog>()
const query = reactive({ page: 1, page_size: 20, dates: [] as string[], plan_id: undefined as ID | undefined, college_org_id: undefined as ID | undefined, object_type: '', action_code: '', keyword: '' })
const canExport = computed(() => !auth.roles.includes('COLLEGE_OWNER'))
const params = computed(() => ({ page: query.page, page_size: query.page_size, start_time: query.dates?.[0] ? `${query.dates[0]} 00:00:00` : undefined, end_time: query.dates?.[1] ? `${query.dates[1]} 23:59:59` : undefined, plan_id: query.plan_id, college_org_id: query.college_org_id, object_type: query.object_type, action_code: query.action_code, keyword: query.keyword }))
async function load() { loading.value = true; error.value = ''; try { const result = await governanceApi.logs(params.value); rows.value = result.items; total.value = result.total } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
function showDetail(row: OperationLog) { current.value = row; drawer.value = true }
async function exportCsv() {
  exporting.value = true
  try {
    const blob = await governanceApi.exportLogs({ ...params.value, page: undefined, page_size: undefined })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `操作日志_${new Date().toISOString().slice(0, 19).replaceAll(':', '-')}.csv`; document.body.appendChild(anchor); anchor.click(); anchor.remove(); URL.revokeObjectURL(url)
    ElMessage.success('权限范围内日志已导出；导出动作本身已留痕'); await load()
  } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { exporting.value = false }
}
function clear() { Object.assign(query, { page: 1, dates: [], plan_id: undefined, college_org_id: undefined, object_type: '', action_code: '', keyword: '' }); load() }
onMounted(async () => { await load(); if (!auth.roles.includes('COLLEGE_OWNER')) { try { colleges.value = (await optionsApi.organizations({ org_type: 'COLLEGE' })).items } catch { colleges.value = [] } } })
</script>
<template>
  <div class="page-container"><PageHeader title="全链路操作日志" description="记录动作发生时的操作人、角色、业务原因及前后摘要。普通业务用户不可修改或删除日志，导出范围不超出当前数据权限。"><template #actions><el-button v-if="canExport" type="primary" :loading="exporting" @click="exportCsv"><el-icon><Download /></el-icon>导出 CSV</el-button></template></PageHeader>
    <div class="filter-bar"><el-date-picker v-model="query.dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:250px" /><div v-if="!auth.roles.includes('ADMIN')" style="width:240px"><PlanSelect v-model="query.plan_id" placeholder="全部计划" /></div><el-select v-if="!auth.roles.includes('COLLEGE_OWNER')" v-model="query.college_org_id" placeholder="学院" clearable style="width:160px"><el-option v-for="org in colleges" :key="org.id" :value="org.id" :label="org.org_name" /></el-select><el-select v-model="query.object_type" placeholder="对象层级" clearable style="width:135px"><el-option v-for="(label,value) in objectTypeLabels" :key="value" :value="value" :label="label" /></el-select><el-select v-model="query.action_code" placeholder="操作类型" clearable filterable style="width:135px"><el-option v-for="(label,value) in actionLabels" :key="value" :value="value" :label="label" /></el-select><el-input v-model="query.keyword" placeholder="对象名称／操作人／单号" clearable style="width:210px" @keyup.enter="load"><template #prefix><el-icon><Search /></el-icon></template></el-input><el-button type="primary" plain @click="load">查询</el-button><el-button @click="clear">清除</el-button></div>
    <RemoteState :loading="loading" :error="error" :empty="!rows.length" empty-text="当前筛选下无日志" @retry="load"><el-table :data="rows"><el-table-column label="操作时间" width="160"><template #default="{ row }">{{ formatDateTime(row.operated_at) }}</template></el-table-column><el-table-column label="操作人／角色" width="150"><template #default="{ row }">{{ row.operator_user_name || row.operator_user_id }}<div class="subtle small">{{ roleLabels[row.operator_role_code] || row.operator_role_code }}</div></template></el-table-column><el-table-column prop="plan_name" label="所属计划" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.plan_name || '平台级操作' }}</template></el-table-column><el-table-column label="对象" min-width="190"><template #default="{ row }"><el-tag size="small" effect="plain">{{ objectTypeLabels[row.object_type] || row.object_type }}</el-tag><span style="margin-left:6px">{{ row.object_name || row.object_id }}</span></template></el-table-column><el-table-column label="动作" width="110"><template #default="{ row }">{{ actionLabels[row.action_code] || row.action_code }}</template></el-table-column><el-table-column prop="operation_reason" label="业务原因" min-width="170" show-overflow-tooltip><template #default="{ row }">{{ row.operation_reason || '—' }}</template></el-table-column><el-table-column label="关联单据" width="150"><template #default="{ row }">{{ row.related_order_type ? `${row.related_order_type} #${row.related_order_id}` : '—' }}</template></el-table-column><el-table-column label="操作" width="70" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="showDetail(row)"><el-icon><View /></el-icon>详情</el-button></template></el-table-column></el-table><div class="table-footer"><el-pagination v-model:current-page="query.page" v-model:page-size="query.page_size" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div></RemoteState>
    <el-drawer v-model="drawer" title="日志完整记录" size="570px"><template v-if="current"><dl class="description-grid two-cols"><div class="description-item"><dt>日志编号</dt><dd class="mono">{{ current.log_no }}</dd></div><div class="description-item"><dt>操作时间</dt><dd>{{ formatDateTime(current.operated_at) }}</dd></div><div class="description-item"><dt>操作人</dt><dd>{{ current.operator_user_name || current.operator_user_id }}</dd></div><div class="description-item"><dt>操作时角色</dt><dd>{{ roleLabels[current.operator_role_code] }}</dd></div><div class="description-item"><dt>对象层级</dt><dd>{{ objectTypeLabels[current.object_type] || current.object_type }}</dd></div><div class="description-item"><dt>操作动作</dt><dd>{{ actionLabels[current.action_code] || current.action_code }}</dd></div><div class="description-item span-4"><dt>业务原因</dt><dd>{{ current.operation_reason || '—' }}</dd></div></dl><h4 class="section-title" style="margin-top:20px">调整前摘要</h4><pre class="snapshot-box">{{ JSON.stringify(current.before_snapshot_json || {}, null, 2) }}</pre><h4 class="section-title" style="margin-top:15px">调整后摘要</h4><pre class="snapshot-box">{{ JSON.stringify(current.after_snapshot_json || {}, null, 2) }}</pre></template></el-drawer>
  </div>
</template>
<style scoped>.two-cols{grid-template-columns:1fr 1fr}</style>
