<script setup lang="ts">
import { ref } from 'vue'
import { Document, Picture, Refresh, Upload, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { errorMessage, executionApi } from '@/api'
import type { EvidenceMaterial, ID } from '@/types/api'
import { formatDateTime, formatFileSize } from '@/utils/format'
import StatusTag from './StatusTag.vue'
import MaterialPreview from './MaterialPreview.vue'
const props = withDefaults(defineProps<{ submissionId: ID; materials?: EvidenceMaterial[]; readonly?: boolean; compact?: boolean }>(), { materials: () => [], readonly: false })
const emit = defineEmits<{ changed: [] }>()
const uploading = ref(false)
const previewVisible = ref(false)
const currentMaterial = ref<EvidenceMaterial | null>(null)
const description = ref('')
function openPreview(material: EvidenceMaterial) { currentMaterial.value = material; previewVisible.value = true }
async function upload(file: UploadFile) {
  if (!file.raw || props.readonly || uploading.value) return
  uploading.value = true
  try {
    await executionApi.uploadMaterial(props.submissionId, file.raw, description.value)
    ElMessage.success('材料已上传，正在检查预览状态')
    description.value = ''
    emit('changed')
  } catch (error) { ElMessage.error(errorMessage(error)) } finally { uploading.value = false }
}
async function retry(material: EvidenceMaterial) {
  try { await executionApi.retryMaterialPreview(material.id); ElMessage.success('已重新发起预览处理'); emit('changed') } catch (error) { ElMessage.error(errorMessage(error)) }
}
async function remove(material: EvidenceMaterial) {
  await ElMessageBox.confirm(`确认删除材料“${material.material_name}”？已提交版本的材料不可删除。`, '删除材料', { type: 'warning' })
  try { await executionApi.deleteMaterial(material.id); ElMessage.success('材料已删除'); emit('changed') } catch (error) { ElMessage.error(errorMessage(error)) }
}
</script>
<template>
  <div>
    <div v-if="!readonly" class="upload-row">
      <el-input v-model="description" placeholder="材料说明（选填）" maxlength="1000" style="max-width:330px" />
      <el-upload :auto-upload="false" :show-file-list="false" :disabled="uploading" :on-change="upload" accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.jpeg,.webp,.mp4,.webm">
        <el-button type="primary" :loading="uploading"><el-icon><Upload /></el-icon>上传佐证</el-button>
      </el-upload>
      <span class="form-tip">文档、图片、PDF、视频；上传失败不会清除已保存的文字草稿</span>
    </div>
    <div v-if="materials.length" class="material-list">
      <div v-for="material in materials" :key="material.id" class="material-item">
        <div class="material-icon"><el-icon><Picture v-if="['png','jpg','jpeg','webp'].includes(material.file_extension)" /><VideoPlay v-else-if="['mp4','webm','mov'].includes(material.file_extension)" /><Document v-else /></el-icon></div>
        <div class="material-info"><div class="material-name" :title="material.material_name">{{ material.material_name }}</div><div class="material-meta">{{ formatFileSize(material.file_size_bytes) }} · {{ formatDateTime(material.uploaded_at) }}<span v-if="material.material_desc"> · {{ material.material_desc }}</span></div></div>
        <StatusTag :status="material.preview_status" />
        <el-button link type="primary" :disabled="material.preview_status !== 'READY'" @click="openPreview(material)">预览</el-button>
        <el-button v-if="material.preview_status === 'FAILED'" link type="warning" @click="retry(material)"><el-icon><Refresh /></el-icon>重试</el-button>
        <el-button v-if="!readonly" link type="danger" @click="remove(material)">删除</el-button>
      </div>
    </div>
    <el-empty v-else description="尚未上传佐证材料" :image-size="65" />
    <MaterialPreview v-model="previewVisible" :material="currentMaterial" />
  </div>
</template>
<style scoped>.upload-row { margin-bottom: 14px; display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }</style>
