<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Check, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorMessage, planningApi } from '@/api'
import type { ImportBatch, ImportRow } from '@/types/api'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'

const route = useRoute()
const id = String(route.params.id)
const batch = ref<ImportBatch & { field_mapping?: Record<string, string>; sheets?: string[] }>()
const rows = ref<ImportRow[]>([])
const total = ref(0)
const loading = ref(true)
const error = ref('')
const query = reactive({ page: 1, page_size: 30, validation_status: '', keyword: '' })
const detailRow = ref<ImportRow>()
const detailDrawer = ref(false)
const confirmDialog = ref(false)
const confirming = ref(false)
const confirmRow = ref<ImportRow>()
const confirmForm = reactive({ standard_unit_code: '', org_mapping: '', calc_method: '', confirmation_note: '', duplicate_action: 'SKIP' })
const commitDialog = ref(false)
const duplicateAction = ref<'SKIP' | 'RECREATE'>('SKIP')
const committing = ref(false)
const sourceFields: Array<{ key: keyof ImportRow; label: string }> = [
  { key: 'source_seq_raw', label: '序号' }, { key: 'direction_raw', label: '建设任务/主要建设方向' }, { key: 'indicator_raw', label: '关键产出指标' }, { key: 'indicator_type_raw', label: '指标类型' }, { key: 'indicator_unit_raw', label: '指标单位' }, { key: 'professional_five_year_raw', label: '专业群5年量化目标' }, { key: 'professional_2026_raw', label: '专业群2026年量化目标' }, { key: 'professional_five_year_allocation_raw', label: '专业群五年指标分配' }, { key: 'professional_2026_allocation_raw', label: '专业群2026年指标分配' }, { key: 'school_five_year_raw', label: '学校5年量化目标' }, { key: 'school_2026_raw', label: '学校2026年量化目标' }, { key: 'school_2026_division_raw', label: '学校2026年任务分工' }, { key: 'school_2027_division_raw', label: '学校2027年任务分工' }, { key: 'school_2028_division_raw', label: '学校2028年任务分工' }, { key: 'school_2029_division_raw', label: '学校2029年任务分工' }, { key: 'school_2030_division_raw', label: '学校2030年任务分工' },
]
const counts = computed(() => ({ ready: batch.value?.ready_count ?? rows.value.filter((item) => status(item) === 'READY' || status(item) === 'PARSED').length, confirm: batch.value?.confirm_count ?? rows.value.filter((item) => ['NEED_CONFIRM', 'PENDING'].includes(status(item))).length, invalid: batch.value?.invalid_count ?? rows.value.filter((item) => ['INVALID', 'FAILED'].includes(status(item))).length, duplicate: batch.value?.duplicate_count ?? rows.value.filter((item) => item.is_duplicate).length }))
function status(row: ImportRow) { return row.validation_status || row.parse_status }
async function load() {
  loading.value = true; error.value = ''
  try { const [b, r] = await Promise.all([planningApi.importBatch(id), planningApi.importRows(id, query)]); batch.value = b; rows.value = r.items; total.value = r.total } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
function showDetail(row: ImportRow) { detailRow.value = row; detailDrawer.value = true }
function openConfirm(row: ImportRow) { confirmRow.value = row; Object.assign(confirmForm, { standard_unit_code: '', org_mapping: '', calc_method: '', confirmation_note: '', duplicate_action: row.duplicate_action || 'SKIP' }); confirmDialog.value = true }
async function confirm() {
  if (!confirmRow.value) return
  confirming.value = true
  try { await planningApi.confirmImportRow(id, confirmRow.value.id, { duplicate_action: confirmForm.duplicate_action, lock_version: confirmRow.value.lock_version, confirmation: { standard_unit_code: confirmForm.standard_unit_code || undefined, org_mapping: confirmForm.org_mapping || undefined, calc_method: confirmForm.calc_method || undefined, note: confirmForm.confirmation_note || undefined } }); ElMessage.success('该源行的人工确认已保存'); confirmDialog.value = false; await load() } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { confirming.value = false }
}
async function commit() {
  committing.value = true
  try { await planningApi.commitImport(id, duplicateAction.value); ElMessage.success('已写入并自动发布计划，未生成学院任务'); commitDialog.value = false; await load() } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { committing.value = false }
}
onMounted(load)
</script>
<template>
  <div class="page-container" v-loading="loading">
    <PageHeader :title="batch?.file_name || '导入预览与确认'" :description="batch ? `目标计划：${batch.plan_name || batch.plan_id} · 导入时间 ${formatDateTime(batch.imported_at)} · 写入后自动发布计划，不生成学院任务` : '读取源文件与解析结果'"><template #actions><el-button type="primary" :disabled="!batch || batch.import_status === 'COMPLETED'" @click="commitDialog = true"><el-icon><Check /></el-icon>写入并发布</el-button></template></PageHeader>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"><el-button link @click="load">重新加载</el-button></el-alert>
    <template v-if="batch">
      <div class="stat-grid"><div class="stat-card"><div class="stat-label">可导入</div><div class="stat-value success-text">{{ counts.ready }}</div><div class="stat-note">必填、字典与数值校验通过</div></div><div class="stat-card"><div class="stat-label">需人工确认</div><div class="stat-value warning-text">{{ counts.confirm }}</div><div class="stat-note">别名、复合单位、口径或分配差异</div></div><div class="stat-card"><div class="stat-label">不可导入</div><div class="stat-value danger-text">{{ counts.invalid }}</div><div class="stat-note">必须处理异常后重新导入</div></div><div class="stat-card"><div class="stat-label">重复源行</div><div class="stat-value">{{ counts.duplicate }}</div><div class="stat-note">禁止静默重复生成草稿</div></div></div>
      <div class="inline-meta" style="margin-bottom:14px"><StatusTag :status="batch.import_status" /><span>{{ batch.sheet_count }} 个工作表</span><span>{{ batch.data_row_count }} 条数据</span><span v-if="batch.sheets?.length">工作表：{{ batch.sheets.join('、') }}</span><span class="mono">Hash：{{ batch.file_hash || '—' }}</span></div>
      <div class="filter-bar"><el-radio-group v-model="query.validation_status" @change="query.page = 1; load()"><el-radio-button value="">全部</el-radio-button><el-radio-button value="READY">可导入</el-radio-button><el-radio-button value="NEED_CONFIRM">需确认</el-radio-button><el-radio-button value="INVALID">不可导入</el-radio-button></el-radio-group><el-input v-model="query.keyword" placeholder="来源序号／指标原文" clearable style="width:260px" @keyup.enter="load" /><el-button @click="load">查询</el-button><span class="form-tip">“无”仅表示未分配，不等于数值 0；空值保持为空</span></div>
      <el-table :data="rows" max-height="650"><el-table-column label="Excel 位置" width="120"><template #default="{ row }"><span class="mono">{{ row.sheet_name }}!{{ row.row_number }}</span><div class="subtle small">序号 {{ row.source_seq_raw || '—' }}</div></template></el-table-column><el-table-column prop="direction_raw" label="建设方向原文" min-width="160" show-overflow-tooltip /><el-table-column prop="indicator_raw" label="关键产出指标原文" min-width="260" show-overflow-tooltip /><el-table-column label="类型／单位" width="115"><template #default="{ row }">{{ row.indicator_type_raw || '未提供' }}<div class="subtle small">{{ row.indicator_unit_raw || '未提供' }}</div></template></el-table-column><el-table-column label="五年／2026 目标原文" min-width="150"><template #default="{ row }"><div class="text-truncate" :title="row.professional_five_year_raw">{{ row.professional_five_year_raw ?? '未提供' }}</div><div class="subtle text-truncate" :title="row.professional_2026_raw">2026：{{ row.professional_2026_raw ?? '未提供' }}</div></template></el-table-column><el-table-column label="校验结果" min-width="180"><template #default="{ row }"><StatusTag :status="status(row)" /><el-tag v-if="row.is_duplicate" type="warning" size="small" style="margin-left:4px">重复</el-tag><div v-if="row.error_message" class="danger-text small long-text" style="margin-top:4px">{{ row.error_message }}</div></template></el-table-column><el-table-column label="操作" width="130" fixed="right"><template #default="{ row }"><el-button link @click="showDetail(row)"><el-icon><View /></el-icon>16 字段</el-button><el-button v-if="!['INVALID','FAILED','CONFIRMED'].includes(status(row))" link type="primary" @click="openConfirm(row)">确认</el-button></template></el-table-column></el-table><div class="table-footer"><el-pagination v-model:current-page="query.page" v-model:page-size="query.page_size" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div>
    </template>
    <el-drawer v-model="detailDrawer" title="源表 16 字段原文" size="600px"><template v-if="detailRow"><el-alert :title="`工作表 ${detailRow.sheet_name} · Excel 第 ${detailRow.row_number} 行`" type="info" :closable="false" style="margin-bottom:14px" /><dl class="source-fields"><div v-for="field in sourceFields" :key="field.key"><dt>{{ field.label }}</dt><dd :class="{ 'value-empty': detailRow[field.key] === null || detailRow[field.key] === undefined }">{{ detailRow[field.key] ?? '未提供' }}</dd></div></dl></template></el-drawer>
    <el-dialog v-model="confirmDialog" title="人工确认导入异常" width="600px"><el-alert v-if="confirmRow?.error_message" :title="confirmRow.error_message" type="warning" :closable="false" style="margin-bottom:15px" /><div class="required-tip" style="margin-bottom:15px">确认仅修正结构化解析口径，不覆盖源表原文。未知学院别名不得擅自映射，尤其“数据产业学院／大数据产业学院”。</div><el-form label-width="120px"><el-form-item v-if="confirmRow?.is_duplicate" label="重复处理" required><el-radio-group v-model="confirmForm.duplicate_action"><el-radio value="SKIP">跳过已有项</el-radio><el-radio value="RECREATE">重新生成独立草稿</el-radio></el-radio-group></el-form-item><el-form-item label="标准单位代码"><el-input v-model="confirmForm.standard_unit_code" placeholder="复合单位确认后填写" /></el-form-item><el-form-item label="学院映射确认"><el-input v-model="confirmForm.org_mapping" type="textarea" :rows="2" placeholder="如：源别名 = 标准组织代码；未确认请留空" /></el-form-item><el-form-item label="核算方式"><el-select v-model="confirmForm.calc_method" clearable style="width:100%"><el-option label="累计型" value="CUMULATIVE" /><el-option label="年度定额型" value="ANNUAL_QUOTA" /><el-option label="期末型" value="FINAL" /><el-option label="比率型" value="RATIO" /><el-option label="增长率型" value="GROWTH" /><el-option label="是否型" value="BOOLEAN" /><el-option label="外部数据型" value="EXTERNAL" /></el-select></el-form-item><el-form-item label="确认说明"><el-input v-model="confirmForm.confirmation_note" type="textarea" :rows="3" placeholder="说明分配差异、比率口径或其他确认依据" /></el-form-item></el-form><template #footer><el-button @click="confirmDialog = false">取消</el-button><el-button type="primary" :loading="confirming" @click="confirm">保存人工确认</el-button></template></el-dialog>
    <el-dialog v-model="commitDialog" title="确认写入并自动发布" width="520px"><el-alert title="仅可导入项与已确认项写入指标，写入后自动发布计划，不会直接下发学院任务。" type="warning" :closable="false" /><el-form label-width="110px" style="margin-top:18px"><el-form-item label="重复项处理"><el-radio-group v-model="duplicateAction"><el-radio value="SKIP">跳过重复</el-radio><el-radio value="RECREATE">重新生成草稿</el-radio></el-radio-group></el-form-item></el-form><template #footer><el-button @click="commitDialog = false">取消</el-button><el-button type="primary" :loading="committing" @click="commit">确认写入并发布</el-button></template></el-dialog>
  </div>
</template>
<style scoped>.source-fields{margin:0}.source-fields>div{padding:10px 0;border-bottom:1px solid #edf0f5}.source-fields dt{margin-bottom:5px;color:#8793a6;font-size:11px}.source-fields dd{margin:0;color:#2c3b53;font-size:12px;line-height:1.7;white-space:pre-wrap;word-break:break-word}</style>
