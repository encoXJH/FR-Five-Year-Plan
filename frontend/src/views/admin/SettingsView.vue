<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Edit } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorMessage, governanceApi } from '@/api'
import type { PlatformSetting } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import RemoteState from '@/components/RemoteState.vue'
const rows = ref<PlatformSetting[]>([])
const loading = ref(false)
const error = ref('')
const dialog = ref(false)
const saving = ref(false)
const current = ref<PlatformSetting>()
const editValue = ref('')
async function load() { loading.value = true; error.value = ''; try { rows.value = (await governanceApi.settings({ page_size: 200 })).items } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
function open(row: PlatformSetting) { current.value = row; editValue.value = typeof row.setting_value === 'object' ? JSON.stringify(row.setting_value, null, 2) : String(row.setting_value); dialog.value = true }
async function save() {
  if (!current.value) return
  let value: string | number | boolean | Record<string, unknown> = editValue.value
  try {
    if (current.value.value_type === 'NUMBER') value = Number(editValue.value)
    else if (current.value.value_type === 'BOOLEAN') value = ['true', '1', '是'].includes(editValue.value.toLowerCase())
    else if (current.value.value_type === 'JSON') value = JSON.parse(editValue.value) as Record<string, unknown>
  } catch { ElMessage.warning('设置值格式无效，请按值类型填写'); return }
  if (current.value.value_type === 'NUMBER' && !Number.isFinite(value as number)) { ElMessage.warning('请输入有效数值'); return }
  saving.value = true
  try { await governanceApi.updateSetting(current.value.setting_key, { setting_value: value, description: current.value.description, lock_version: current.value.lock_version }); ElMessage.success('运行设置已保存并记录权限变更日志'); dialog.value = false; await load() } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { saving.value = false }
}
onMounted(load)
</script>
<template>
  <div class="page-container"><PageHeader title="平台运行设置" description="维护材料扩展名、大小、数量、视频时长及预览转换等运行参数。配置变更保留操作日志，不在前端硬编码替代后端限制。" />
    <el-alert title="本地默认：文档／图片 50 MB、视频 500 MB、单次 10 个；实际上传限制以服务端当前配置为准。" type="info" :closable="false" style="margin-bottom:14px" />
    <RemoteState :loading="loading" :error="error" :empty="!rows.length" empty-text="暂无运行设置" @retry="load"><el-table :data="rows"><el-table-column prop="setting_key" label="设置键" min-width="230" class-name="mono" /><el-table-column label="当前值" min-width="240"><template #default="{ row }"><span class="mono long-text">{{ typeof row.setting_value === 'object' ? JSON.stringify(row.setting_value) : row.setting_value }}</span></template></el-table-column><el-table-column prop="value_type" label="值类型" width="100"><template #default="{ row }">{{ row.value_type || 'STRING' }}</template></el-table-column><el-table-column prop="description" label="配置说明" min-width="280" show-overflow-tooltip /><el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="primary" @click="open(row)"><el-icon><Edit /></el-icon>修改</el-button></template></el-table-column></el-table></RemoteState>
    <el-dialog v-model="dialog" :title="`修改设置 · ${current?.setting_key || ''}`" width="590px"><el-alert :title="current?.description" type="info" :closable="false" style="margin-bottom:15px" /><el-form label-position="top"><el-form-item :label="`设置值（${current?.value_type || 'STRING'}）`" required><el-input v-model="editValue" :type="current?.value_type === 'JSON' ? 'textarea' : 'text'" :rows="7" /><div class="form-tip">布尔值可填 true/false；JSON 必须为合法对象；数值限制由服务端再次校验。</div></el-form-item></el-form><template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存设置</el-button></template></el-dialog>
  </div>
</template>
