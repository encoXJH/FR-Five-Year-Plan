<script setup lang="ts">
import { computed } from 'vue'
import { statusLabel, statusTagType } from '@/utils/dictionaries'
const props = withDefaults(defineProps<{ status?: string | null; effect?: 'light' | 'dark' | 'plain'; size?: 'small' | 'default' | 'large' }>(), { effect: 'light', size: 'small' })
const label = computed(() => statusLabel(props.status))
</script>
<template><el-tag :type="statusTagType(status)" :effect="effect" :size="size" disable-transitions>{{ label }}</el-tag></template>
