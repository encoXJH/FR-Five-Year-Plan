<script setup lang="ts">
withDefaults(
  defineProps<{
    loading?: boolean
    empty?: boolean
    emptyText?: string
    loadingText?: string
    icon?: string
  }>(),
  {
    loading: false,
    empty: false,
    emptyText: '暂无数据',
    loadingText: '加载中…',
    icon: 'orders-o',
  },
)
</script>

<template>
  <div v-if="loading" class="skeleton-block">{{ loadingText }}</div>
  <div v-else-if="empty" class="empty-state">
    <div class="empty-state__icon">
      <van-icon :name="icon" />
    </div>
    <span>{{ emptyText }}</span>
    <slot name="empty-action" />
  </div>
  <slot v-else />
</template>
