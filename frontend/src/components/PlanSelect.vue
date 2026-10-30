<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { planningApi } from '@/api'
import type { ConstructionPlan } from '@/types/api'
const props = withDefaults(defineProps<{ modelValue?: string | number; status?: string; placeholder?: string }>(), { placeholder: '请选择建设计划' })
const emit = defineEmits<{ 'update:modelValue': [value: string]; loaded: [plans: ConstructionPlan[]] }>()
const plans = ref<ConstructionPlan[]>([])
const loading = ref(false)
async function load() {
  loading.value = true
  try {
    const result = await planningApi.plans({ page_size: 100, plan_status: props.status })
    plans.value = result.items
    emit('loaded', plans.value)
  } finally { loading.value = false }
}
function change(value: string) { emit('update:modelValue', value) }
onMounted(load)
defineExpose({ load, plans })
</script>
<template><el-select :model-value="modelValue === undefined ? '' : String(modelValue)" :loading="loading" filterable clearable :placeholder="placeholder" style="width:100%" @change="change"><el-option v-for="plan in plans" :key="plan.id" :value="String(plan.id)" :label="plan.plan_name"><span>{{ plan.plan_name }}</span><span class="option-note">{{ plan.start_year }}—{{ plan.end_year }}</span></el-option></el-select></template>
