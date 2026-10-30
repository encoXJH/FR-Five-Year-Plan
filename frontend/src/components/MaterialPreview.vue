<script setup lang="ts">
import { computed } from 'vue'
import { Picture, VideoPlay, Document } from '@element-plus/icons-vue'
import type { EvidenceMaterial } from '@/types/api'
import { executionApi } from '@/api'
import { formatFileSize } from '@/utils/format'
import StatusTag from './StatusTag.vue'
const props = defineProps<{ modelValue: boolean; material?: EvidenceMaterial | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const url = computed(() => props.material ? executionApi.materialPreviewUrl(props.material) : '')
const kind = computed(() => {
  const extension = props.material?.file_extension.toLowerCase() || ''
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg'].includes(extension)) return 'image'
  if (['mp4', 'webm', 'ogg', 'mov'].includes(extension)) return 'video'
  if (['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx'].includes(extension)) return 'document'
  return 'other'
})
</script>
<template>
  <el-dialog :model-value="modelValue" :title="material?.material_name || '材料预览'" width="82%" top="4vh" destroy-on-close @update:model-value="emit('update:modelValue', $event)">
    <div v-if="material" class="inline-meta" style="margin-bottom:12px"><span>{{ material.original_file_name }}</span><span>{{ formatFileSize(material.file_size_bytes) }}</span><StatusTag :status="material.preview_status" /><span>预览请求使用当前受保护会话</span></div>
    <template v-if="material?.preview_status === 'READY'">
      <img v-if="kind === 'image'" :src="url" :alt="material.material_name" class="material-preview-image" />
      <video v-else-if="kind === 'video'" :src="url" controls preload="metadata" class="material-preview-video">当前浏览器不支持视频播放</video>
      <iframe v-else-if="kind === 'document'" :src="url" :title="material.material_name" class="material-preview-frame" />
      <el-result v-else icon="info" title="该格式暂不支持内嵌预览" sub-title="请联系管理员检查材料格式配置，审核无需绕过权限下载" />
    </template>
    <el-result v-else-if="material?.preview_status === 'FAILED'" icon="error" title="预览生成失败" :sub-title="material.preview_error || '请重试预览或重新上传材料'" />
    <div v-else class="state-block" v-loading="true"><el-icon class="state-icon"><Document /></el-icon><h3>正在生成预览</h3><p>材料处理完成后方可提交审核</p></div>
    <template #footer><span class="muted small"><el-icon><Picture v-if="kind === 'image'" /><VideoPlay v-else-if="kind === 'video'" /><Document v-else /></el-icon> 文件仅在当前用户数据权限范围内提供</span></template>
  </el-dialog>
</template>
