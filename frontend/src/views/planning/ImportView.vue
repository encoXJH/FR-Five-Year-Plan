<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Upload, Search } from '@element-plus/icons-vue'
import { ElMessage, type UploadFile } from 'element-plus'
import { errorMessage, planningApi } from '@/api'
import type { ID, ImportBatch } from '@/types/api'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import PlanSelect from '@/components/PlanSelect.vue'
import StatusTag from '@/components/StatusTag.vue'
import RemoteState from '@/components/RemoteState.vue'
const router = useRouter()
const rows = ref<ImportBatch[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const query = reactive({ page: 1, page_size: 20, plan_id: undefined as ID | undefined, import_status: '' })
const dialog = ref(false)
const uploading = ref(false)
const uploadPlanId = ref('')
const selectedFile = ref<File>()
async function load() { loading.value = true; error.value = ''; try { const result = await planningApi.imports(query); rows.value = result.items; total.value = result.total } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
function chooseFile(file: UploadFile) { selectedFile.value = file.raw }
async function upload() {
  if (!uploadPlanId.value || !selectedFile.value) { ElMessage.warning('请选择目标计划和 Excel 文件'); return }
  uploading.value = true
  try { const batch = await planningApi.uploadImport(uploadPlanId.value, selectedFile.value); ElMessage.success('文件已上传并完成初步解析，尚未写入正式计划'); dialog.value = false; selectedFile.value = undefined; router.push(`/planning/imports/${batch.id}`) } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { uploading.value = false }
}
onMounted(load)
</script>
<template>
  <div class="page-container">
    <PageHeader title="Excel 导入" description="上传后先预览工作表、16 个源字段与校验结果，异常逐项确认；可导入项和已确认项才进入计划草稿。"><template #actions><el-button type="primary" @click="dialog = true"><el-icon><Upload /></el-icon>上传清单</el-button></template></PageHeader>
    <div class="filter-bar"><div style="width:320px"><PlanSelect v-model="query.plan_id" placeholder="按目标计划筛选" /></div><el-select v-model="query.import_status" clearable placeholder="导入状态" style="width:150px"><el-option label="待预览" value="PREVIEW" /><el-option label="部分确认" value="PARTIAL_CONFIRMED" /><el-option label="已完成" value="COMPLETED" /><el-option label="失败" value="FAILED" /></el-select><el-button type="primary" plain @click="load"><el-icon><Search /></el-icon>查询</el-button></div>
    <RemoteState :loading="loading" :error="error" :empty="!rows.length" empty-text="暂无导入批次" @retry="load"><el-table :data="rows"><el-table-column prop="file_name" label="源文件" min-width="270" show-overflow-tooltip><template #default="{ row }"><button class="link-button" @click="router.push(`/planning/imports/${row.id}`)">{{ row.file_name }}</button></template></el-table-column><el-table-column prop="plan_name" label="目标计划" min-width="200" show-overflow-tooltip /><el-table-column label="工作表／数据行" width="135" align="center"><template #default="{ row }">{{ row.sheet_count }} / {{ row.data_row_count }}</template></el-table-column><el-table-column label="校验分布" min-width="210"><template #default="{ row }"><span class="success-text small">可导入 {{ row.ready_count ?? '—' }}</span><span class="warning-text small" style="margin:0 10px">需确认 {{ row.confirm_count ?? '—' }}</span><span class="danger-text small">不可导入 {{ row.invalid_count ?? '—' }}</span></template></el-table-column><el-table-column label="状态" width="115"><template #default="{ row }"><StatusTag :status="row.import_status" /></template></el-table-column><el-table-column label="导入时间" width="165"><template #default="{ row }">{{ formatDateTime(row.imported_at) }}</template></el-table-column><el-table-column label="操作" width="100" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="router.push(`/planning/imports/${row.id}`)">预览确认</el-button></template></el-table-column></el-table><div class="table-footer"><el-pagination v-model:current-page="query.page" v-model:page-size="query.page_size" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div></RemoteState>
    <el-dialog v-model="dialog" title="上传建设目标量化清单" width="560px" destroy-on-close><el-alert title="上传和预览不会直接生成学院任务" description="系统按“计划 + 来源文件 + 来源序号”识别重复，重复项必须显式选择跳过或重新生成草稿。" type="info" :closable="false" style="margin-bottom:18px" /><el-form label-width="90px"><el-form-item label="目标计划" required><PlanSelect v-model="uploadPlanId" /></el-form-item><el-form-item label="Excel 文件" required><el-upload :auto-upload="false" :limit="1" :show-file-list="true" :on-change="chooseFile" accept=".xlsx,.xls,.xlsm" style="width:100%"><el-button><el-icon><Upload /></el-icon>选择文件</el-button><template #tip><div class="form-tip">保留原始单元格、文件哈希、工作表与真实行号；单文件仅用于源清单导入。</div></template></el-upload></el-form-item></el-form><template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="uploading" @click="upload">上传并预览</el-button></template></el-dialog>
  </div>
</template>
