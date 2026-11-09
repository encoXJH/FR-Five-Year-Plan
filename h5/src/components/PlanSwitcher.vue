<script setup lang="ts">
import { computed, ref } from 'vue'
import StatusTag from '@/components/StatusTag.vue'
import type { PlanStatus } from '@/types/enums'

interface PlanOption {
  id: string
  name: string
  status?: PlanStatus | string
  startYear?: number
  endYear?: number
}

const props = withDefaults(
  defineProps<{
    modelValue: string
    plans: PlanOption[]
    label?: string
    placeholder?: string
  }>(),
  { label: '当前计划', placeholder: '请选择计划' },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: [plan: PlanOption]
}>()

const visible = ref(false)
const keyword = ref('')

const current = computed(() => props.plans.find((plan) => plan.id === props.modelValue) ?? null)
const filtered = computed(() => {
  const key = keyword.value.trim()
  return key ? props.plans.filter((plan) => plan.name.includes(key)) : props.plans
})

function choose(plan: PlanOption): void {
  emit('update:modelValue', plan.id)
  emit('change', plan)
  visible.value = false
  keyword.value = ''
}
</script>

<template>
  <button type="button" class="plan-switcher" @click="visible = true">
    <span class="plan-switcher__label">{{ label }}</span>
    <span class="plan-switcher__name ellipsis">{{ current?.name ?? placeholder }}</span>
    <van-icon name="arrow-down" size="12" class="plan-switcher__arrow" />
  </button>

  <van-popup v-model:show="visible" position="bottom" round safe-area-inset-bottom>
    <div class="plan-sheet">
      <div class="plan-sheet__header">
        <strong>切换计划</strong>
        <button type="button" class="close-btn" @click="visible = false">
          <van-icon name="cross" size="18" />
        </button>
      </div>
      <van-search v-model="keyword" placeholder="搜索计划名称" shape="round" />
      <div class="plan-sheet__list">
        <button
          v-for="plan in filtered"
          :key="plan.id"
          type="button"
          class="plan-option"
          :class="{ 'is-active': plan.id === modelValue }"
          @click="choose(plan)"
        >
          <div class="plan-option__main">
            <div class="plan-option__name">{{ plan.name }}</div>
            <div class="plan-option__meta">
              <span v-if="plan.startYear">{{ plan.startYear }}–{{ plan.endYear }}</span>
              <StatusTag v-if="plan.status" kind="PlanStatus" :value="String(plan.status)" />
            </div>
          </div>
          <van-icon v-if="plan.id === modelValue" name="success" color="#2B5BC0" size="18" />
        </button>
        <div v-if="filtered.length === 0" class="empty-state">没有匹配的计划</div>
      </div>
    </div>
  </van-popup>
</template>

<style scoped>
.plan-switcher {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 42px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--text);
  text-align: left;
}
.plan-switcher__label {
  flex-shrink: 0;
  color: var(--text-secondary);
  font-size: 12px;
}
.plan-switcher__name {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
}
.plan-switcher__arrow {
  color: var(--text-secondary);
  flex-shrink: 0;
}
.plan-sheet {
  padding: 14px 14px calc(14px + var(--safe-bottom));
}
.plan-sheet__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-size: 16px;
}
.close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background: #f4f6fa;
  color: var(--text-secondary);
}
.plan-sheet__list {
  max-height: 55vh;
  overflow-y: auto;
  margin-top: 8px;
}
.plan-option {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 56px;
  padding: 10px 8px;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  text-align: left;
  color: var(--text);
}
.plan-option.is-active {
  background: #f5f8ff;
}
.plan-option__main {
  flex: 1;
  min-width: 0;
}
.plan-option__name {
  font-size: 14px;
  font-weight: 600;
  line-height: 20px;
}
.plan-option__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 3px;
  color: var(--text-secondary);
  font-size: 12px;
}
</style>
