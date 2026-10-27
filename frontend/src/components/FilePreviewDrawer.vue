<script setup lang="ts">
import { computed } from 'vue'
import FilePreviewPane from './FilePreviewPane.vue'
import type { FileInfo } from '@/types/file'

/** 材料在线预览抽屉（UI 规范 7.7）：渲染逻辑见 FilePreviewPane（审核中心内嵌面板复用同一组件） */
const props = defineProps<{
  modelValue: boolean
  file: Pick<FileInfo, 'id' | 'fileName' | 'size' | 'category' | 'transcodeStatus'> | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})
</script>

<template>
  <el-drawer
    v-model="visible"
    direction="rtl"
    size="60%"
    :with-header="false"
    class="preview-drawer"
  >
    <FilePreviewPane :file="file" @close="visible = false" />
  </el-drawer>
</template>

<style scoped>
:deep(.el-drawer__body) {
  padding: 0;
}
</style>
