<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorMessage, governanceApi } from '@/api'
import type { DictionaryItem, ID } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import RemoteState from '@/components/RemoteState.vue'
const dictType = ref('indicator-types')
const dictOptions = [{ value: 'indicator-types', label: '指标类型' }, { value: 'indicator-units', label: '指标单位（16 类）' }, { value: 'construction-years', label: '建设年度' }, { value: 'material-formats', label: '材料格式' }]
const rows = ref<DictionaryItem[]>([])
const loading = ref(false)
const error = ref('')
const dialog = ref(false)
const saving = ref(false)
const editingId = ref<ID>()
const form = reactive<Partial<DictionaryItem>>({ status: 'ENABLED', sort_no: 1, is_builtin: 0, is_compound: 0, value_kind: 'DECIMAL' })
const isUnit = computed(() => dictType.value === 'indicator-units')
const isType = computed(() => dictType.value === 'indicator-types')
function code(row: DictionaryItem) { return row.type_code || row.unit_code || row.dict_code || row.code }
function name(row: DictionaryItem) { return row.type_name || row.unit_name || row.dict_name || row.name }
async function load() { loading.value = true; error.value = ''; try { rows.value = (await governanceApi.dictionaries(dictType.value, { page_size: 200 })).items } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
function open(row?: DictionaryItem) { editingId.value = row?.id; Object.assign(form, row ? { ...row, code: code(row), name: name(row) } : { code: '', name: '', dict_code: '', dict_name: '', type_code: undefined, type_name: undefined, unit_code: undefined, unit_name: undefined, value_kind: 'DECIMAL', display_suffix: '', is_compound: 0, is_builtin: 0, sort_no: rows.value.length + 1, status: 'ENABLED', description: '', lock_version: undefined }); dialog.value = true }
async function save() {
  if (!form.code || !form.name) { ElMessage.warning('字典代码和显示名称必填'); return }
  saving.value = true
  try {
    const payload: Partial<DictionaryItem> = { ...form, dict_code: form.code, dict_name: form.name }
    if (isType.value) { payload.type_code = form.code; payload.type_name = form.name }
    if (isUnit.value) { payload.unit_code = form.code; payload.unit_name = form.name }
    if (editingId.value) await governanceApi.updateDictionary(dictType.value, editingId.value, payload); else await governanceApi.createDictionary(dictType.value, payload)
    ElMessage.success('字典已保存；被业务引用项仅停用，不物理删除'); dialog.value = false; await load()
  } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { saving.value = false }
}
watch(dictType, load)
onMounted(load)
</script>
<template>
  <div class="page-container"><PageHeader title="业务字典" description="状态、类型和单位保存稳定代码。已被业务引用的字典项不可物理删除，只能停用并保留历史显示。"><template #actions><el-button type="primary" @click="open()"><el-icon><Plus /></el-icon>新增字典项</el-button></template></PageHeader>
    <div class="filter-bar" style="margin-bottom:14px;border-radius:8px"><span class="filter-label">字典分类</span><el-radio-group v-model="dictType"><el-radio-button v-for="item in dictOptions" :key="item.value" :value="item.value">{{ item.label }}</el-radio-button></el-radio-group></div>
    <RemoteState :loading="loading" :error="error" :empty="!rows.length" @retry="load"><el-table :data="rows"><el-table-column label="字典代码" width="170" class-name="mono"><template #default="{ row }">{{ code(row) }}</template></el-table-column><el-table-column label="显示名称" min-width="170"><template #default="{ row }">{{ name(row) }}</template></el-table-column><el-table-column v-if="isUnit" prop="value_kind" label="值类型" width="160" /><el-table-column v-if="isUnit" prop="display_suffix" label="显示后缀" width="110"><template #default="{ row }">{{ row.display_suffix || '—' }}</template></el-table-column><el-table-column v-if="isUnit" label="复合单位" width="95" align="center"><template #default="{ row }">{{ row.is_compound ? '是' : '否' }}</template></el-table-column><el-table-column prop="description" label="使用说明" min-width="250" show-overflow-tooltip /><el-table-column prop="sort_no" label="排序" width="70" align="center" /><el-table-column label="内置" width="70" align="center"><template #default="{ row }">{{ row.is_builtin ? '是' : '否' }}</template></el-table-column><el-table-column label="状态" width="85"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column><el-table-column label="操作" width="80"><template #default="{ row }"><el-button link type="primary" @click="open(row)">编辑</el-button></template></el-table-column></el-table></RemoteState>
    <el-dialog v-model="dialog" :title="`维护${dictOptions.find(item => item.value === dictType)?.label}`" width="580px"><el-form label-width="105px"><el-form-item label="字典代码" required><el-input v-model="form.code" :disabled="Boolean(editingId && form.is_builtin)" placeholder="稳定代码，如 ITEM_COUNT" /></el-form-item><el-form-item label="显示名称" required><el-input v-model="form.name" placeholder="与业务页面及源表名称一致" /></el-form-item><template v-if="isUnit"><el-form-item label="值类型" required><el-select v-model="form.value_kind" style="width:100%"><el-option label="数值" value="DECIMAL" /><el-option label="是否" value="BOOLEAN" /><el-option label="比值" value="RATIO" /><el-option label="百分比" value="PERCENT" /><el-option label="年度百分比" value="PERCENT_YEAR" /><el-option label="复合数值" value="COMPOUND_DECIMAL" /><el-option label="不适用" value="NONE" /></el-select></el-form-item><el-form-item label="显示后缀"><el-input v-model="form.display_suffix" placeholder="仅用于显示，不参与公式" /></el-form-item><el-form-item label="复合单位"><el-switch v-model="form.is_compound" :active-value="1" :inactive-value="0" /><span class="form-tip" style="margin-left:10px">标准化前禁止自动拆分</span></el-form-item></template><el-form-item label="排序号"><el-input-number v-model="form.sort_no" :min="1" /></el-form-item><el-form-item label="状态"><el-radio-group v-model="form.status"><el-radio value="ENABLED">启用</el-radio><el-radio value="DISABLED">停用</el-radio></el-radio-group></el-form-item><el-form-item label="使用说明"><el-input v-model="form.description" type="textarea" :rows="4" /></el-form-item></el-form><template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template></el-dialog>
  </div>
</template>
