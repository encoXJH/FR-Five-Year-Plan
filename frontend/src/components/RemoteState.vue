<script setup lang="ts">
import { Document, Loading, Warning } from '@element-plus/icons-vue'
withDefaults(defineProps<{ loading?: boolean; error?: string; empty?: boolean; emptyText?: string; emptyDescription?: string; showClear?: boolean }>(), { emptyText: '暂无数据', emptyDescription: '当前条件下没有可显示的记录' })
defineEmits<{ retry: []; clear: [] }>()
</script>
<template>
  <div v-if="loading" class="state-block" v-loading="true"><el-icon class="state-icon"><Loading /></el-icon><h3>正在加载</h3><p>正在读取平台数据，请稍候</p></div>
  <div v-else-if="error" class="state-block"><el-icon class="state-icon danger-text"><Warning /></el-icon><h3>数据加载失败</h3><p>{{ error }}</p><el-button type="primary" @click="$emit('retry')">重新加载</el-button></div>
  <div v-else-if="empty" class="state-block"><el-icon class="state-icon"><Document /></el-icon><h3>{{ emptyText }}</h3><p>{{ emptyDescription }}</p><slot name="empty-action"><el-button v-if="showClear" @click="$emit('clear')">清除筛选</el-button></slot></div>
  <slot v-else />
</template>
