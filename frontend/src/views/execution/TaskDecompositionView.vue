<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { errorMessage, planningApi } from '@/api'
import type { ConstructionPlan, Indicator } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import IndicatorAssignmentPanel from '@/components/IndicatorAssignmentPanel.vue'

const route = useRoute()
const router = useRouter()
const indicatorId = String(route.params.id)
const indicator = ref<Indicator>()
const plan = ref<ConstructionPlan>()
const loading = ref(true)
async function load() {
  loading.value = true
  try {
    indicator.value = await planningApi.indicator(indicatorId)
    plan.value = await planningApi.plan(indicator.value.plan_id)
  } catch (cause) { ElMessage.error(errorMessage(cause)); router.back() } finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page-container" v-loading="loading">
    <PageHeader :title="`任务分解 · ${indicator?.indicator_name || ''}`" description="选择接收学院和学院负责人，分配任务目标；确认下发后任务进入待接收。" />
    <section v-if="indicator && plan" class="panel"><div class="panel-body"><IndicatorAssignmentPanel :indicator="indicator" :plan="plan" :can-assign="true" @assigned="load" /></div></section>
  </div>
</template>
