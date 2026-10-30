<script setup lang="ts">
import { computed } from 'vue'
import type { Numeric, ValueKind } from '@/types/api'
import { formatBusinessValue, isBlank } from '@/utils/format'
const props = withDefaults(defineProps<{ value?: Numeric | null; raw?: string | null; text?: string | null; boolValue?: boolean | number | null; valueKind?: ValueKind; unit?: string | null; emptyText?: string }>(), { boolValue: null })
const display = computed(() => formatBusinessValue(props.value, props))
const displayClass = computed(() => ({ 'value-empty': display.value === (props.emptyText || '未提供'), 'value-na': ['不适用', '未分配', '待补口径'].includes(display.value), 'value-zero': !isBlank(props.value) && Number(props.value) === 0 }))
</script>
<template><span class="value-display" :class="displayClass">{{ display }}</span></template>
