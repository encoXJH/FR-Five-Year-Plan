<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listPlans } from '@/api/plan'
import { listTrainingSheets } from '@/api/training'
import { useUserStore } from '@/stores/user'
import type { PlanCard } from '@/types/plan'
import type { TrainingSheetSummary } from '@/types/training'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const plans = ref<PlanCard[]>([])
const trainingSheets = ref<TrainingSheetSummary[]>([])
const routePlanId = computed(() =>
  typeof route.params.planId === 'string' ? route.params.planId : '',
)
const routeSheetId = computed(() =>
  typeof route.params.sheetId === 'string' ? route.params.sheetId : '',
)
const currentSheet = computed(() => trainingSheets.value.find((sheet) => sheet.id === routeSheetId.value) ?? null)
const activePlanId = computed(() => routePlanId.value || currentSheet.value?.planId || '')
const currentPlan = computed(() => plans.value.find((plan) => plan.id === activePlanId.value) ?? null)
const planOptions = computed(() => plans.value.filter((plan) =>
  plan.planType !== 'TRAINING' || trainingSheets.value.some((sheet) => sheet.planId === plan.id)))

async function switchPlan(planId: string): Promise<void> {
  if (!planId || planId === activePlanId.value) return
  const target = plans.value.find((plan) => plan.id === planId)
  if (!target) return
  if (target.planType === 'TRAINING') {
    const sheet = trainingSheets.value.find((item) => item.planId === planId)
    if (!sheet) {
      ElMessage.info('该计划尚未初始化培训采集表')
      return
    }
    await router.replace({ path: `/training/${sheet.id}`, query: { from: 'plans' } })
    return
  }
  if (route.name === 'TrainingSheetDetail') {
    await router.replace({ path: `/plans/${planId}` })
    return
  }
  if (route.name) {
    await router.replace({
      name: route.name,
      params: { ...route.params, planId },
      query: route.query,
    })
  } else {
    await router.replace({
      path: route.path.replace(`/plans/${routePlanId.value}`, `/plans/${planId}`),
      query: route.query,
    })
  }
}

onMounted(() => {
  listPlans()
    .then((list) => {
      plans.value = list
    })
    .catch(() => undefined)
  if (userStore.permissions.includes('training:fill') || userStore.permissions.includes('submission:review')) {
    listTrainingSheets()
      .then((list) => {
        trainingSheets.value = list
      })
      .catch(() => undefined)
  }
})
</script>

<template>
  <div v-if="routePlanId || routeSheetId" class="plan-toolbar">
    <span class="plan-toolbar-label">当前计划</span>
    <el-dropdown class="plan-switch" trigger="click" @command="switchPlan">
      <span class="plan-entry" :title="currentPlan?.name ?? '当前计划'">
        <span class="plan-name">{{ currentPlan?.name ?? '当前计划' }}</span>
        <el-tag v-if="currentPlan?.planType === 'TRAINING'" type="success" size="small" effect="plain">
          职业培训
        </el-tag>
        <el-tag v-if="currentPlan?.status === 'ARCHIVED'" type="info" size="small">
          已归档
        </el-tag>
        <span class="plan-switch-action">
          切换计划
          <el-icon :size="12"><ArrowDown /></el-icon>
        </span>
      </span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item
            v-for="p in planOptions"
            :key="p.id"
            :command="p.id"
            :disabled="p.id === activePlanId"
          >
            {{ p.name }}
            <el-tag
              v-if="p.planType === 'TRAINING'"
              type="success"
              size="small"
              effect="plain"
              class="plan-item-tag"
            >
              职业培训
            </el-tag>
            <el-tag
              v-if="p.status === 'ARCHIVED'"
              type="info"
              size="small"
              class="plan-item-tag"
            >
              已归档
            </el-tag>
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
.plan-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
  padding: 10px var(--space-4);
  border: 1px solid var(--el-border-color-light);
  border-left: 4px solid var(--el-color-primary);
  border-radius: var(--radius-card);
  background: var(--el-bg-color);
  box-shadow: 0 1px 4px rgb(0 0 0 / 6%);
}

.plan-toolbar-label {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 600;
  line-height: 20px;
}

.plan-switch {
  min-width: 0;
  max-width: 100%;
}

.plan-entry {
  display: flex;
  align-items: center;
  min-width: 0;
  max-width: 100%;
  gap: var(--space-2);
  padding: 4px 10px;
  border: 1px solid var(--el-color-primary-light-5);
  border-radius: 4px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  cursor: pointer;
  outline: none;
  transition: background-color 0.2s ease, border-color 0.2s ease;
}

.plan-entry:hover {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-8);
}

.plan-entry:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

.plan-name {
  min-width: 0;
  max-width: 520px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 600;
}

.plan-item-tag {
  margin-left: var(--space-2);
}

.plan-switch-action {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 2px;
  padding-left: var(--space-2);
  border-left: 1px solid var(--el-color-primary-light-5);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

@media (max-width: 768px) {
  .plan-toolbar {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3);
  }

  .plan-switch,
  .plan-entry {
    width: 100%;
  }

  .plan-name {
    flex: 1;
    max-width: none;
  }

  .plan-switch-action {
    font-size: 12px;
  }
}
</style>
