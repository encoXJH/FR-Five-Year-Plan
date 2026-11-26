<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getTrainingStaff, updateTrainingStaff } from '@/api/training'
import type { TrainingStaffView } from '@/types/training'

const props = withDefaults(defineProps<{
  modelValue: boolean
  sheetId: string | null
  planName?: string
}>(), {
  planName: '',
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [view: TrainingStaffView]
}>()

const view = ref<TrainingStaffView | null>(null)
const selectedStaffIds = ref<string[]>([])
const initialStaffIds = ref<string[]>([])
const loading = ref(false)
const loadFailed = ref(false)
const saving = ref(false)
let loadGeneration = 0

const staff = computed(() => view.value?.staff ?? [])
const candidates = computed(() => view.value?.assignableStaff ?? [])
const dirty = computed(() => {
  if (selectedStaffIds.value.length !== initialStaffIds.value.length) return true
  const initial = new Set(initialStaffIds.value)
  return selectedStaffIds.value.some((id) => !initial.has(id))
})

function applyView(value: TrainingStaffView): void {
  view.value = value
  const ids = [...new Set(value.staff.map((item) => item.id))]
  selectedStaffIds.value = [...ids]
  initialStaffIds.value = ids
}

async function load(sheetId: string): Promise<void> {
  const generation = ++loadGeneration
  loading.value = true
  loadFailed.value = false
  view.value = null
  selectedStaffIds.value = []
  initialStaffIds.value = []
  try {
    const result = await getTrainingStaff(sheetId)
    if (generation === loadGeneration) applyView(result)
  } catch {
    if (generation === loadGeneration) loadFailed.value = true
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}

watch(
  () => [props.modelValue, props.sheetId] as const,
  ([visible, sheetId]) => {
    if (visible && sheetId) void load(sheetId)
  },
  { immediate: true },
)

function close(): void {
  if (!saving.value) emit('update:modelValue', false)
}

function reset(): void {
  selectedStaffIds.value = [...initialStaffIds.value]
}

async function save(): Promise<void> {
  const current = view.value
  if (!props.sheetId || !current?.canAssignStaff || saving.value || !dirty.value) return
  saving.value = true
  try {
    const result = await updateTrainingStaff(props.sheetId, {
      version: current.version,
      staffIds: [...new Set(selectedStaffIds.value)],
    })
    applyView(result)
    ElMessage.success('培训干事分配已保存')
    emit('saved', result)
    emit('update:modelValue', false)
  } catch {
    // 拦截器已提示，保留当前选择便于修正后重试
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="培训干事分配"
    width="min(560px, calc(100vw - 32px))"
    :close-on-click-modal="false"
    @update:model-value="!$event && close()"
  >
    <p v-if="planName" class="staff-plan-name">{{ planName }}</p>
    <div v-loading="loading" class="staff-dialog-body">
      <el-alert
        v-if="loadFailed"
        type="error"
        :closable="false"
        title="干事分配信息加载失败，请稍后重试"
      />
      <el-button v-if="loadFailed" link type="primary" @click="sheetId && load(sheetId)">
        重新加载
      </el-button>

      <template v-else-if="view?.canAssignStaff">
        <el-alert
          type="info"
          :closable="false"
          title="可分配本采集表单元内全部启用纯干事；保存后整体生效，移除后该干事立即失去培训及附件访问权限。"
          class="staff-alert"
        />
        <div class="editor-label">已分配干事（{{ selectedStaffIds.length }} 人）</div>
        <el-select
          v-model="selectedStaffIds"
          multiple
          filterable
          collapse-tags
          collapse-tags-tooltip
          :disabled="saving"
          placeholder="请选择干事，清空表示全部移除"
          class="staff-select"
          no-data-text="暂无可分配干事，请先创建本单元纯干事账号"
        >
          <el-option
            v-for="item in candidates"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
        <div class="field-tip">候选人不受干事创建人限制，同一采集表可分配多人。</div>
      </template>

      <template v-else-if="view">
        <div v-if="staff.length > 0" class="staff-list">
          <el-tag
            v-for="item in staff"
            :key="item.id"
            size="large"
            effect="plain"
            class="staff-tag"
          >
            {{ item.name }}
          </el-tag>
        </div>
        <el-empty v-else :image-size="72" description="该采集表尚未分配干事" />
        <div class="readonly-tip">当前采集表仅可查看干事名单。</div>
      </template>
    </div>

    <template #footer>
      <template v-if="view?.canAssignStaff">
        <el-button :disabled="saving" @click="close">取消</el-button>
        <el-button :disabled="!dirty || saving" @click="reset">取消修改</el-button>
        <el-button type="primary" :disabled="!dirty" :loading="saving" @click="save">
          保存分配
        </el-button>
      </template>
      <el-button v-else :disabled="saving" @click="close">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.staff-plan-name {
  margin: 0 0 var(--space-3);
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 22px;
  overflow-wrap: anywhere;
}

.staff-dialog-body {
  min-height: 120px;
}

.staff-alert {
  margin-bottom: var(--space-4);
}

.editor-label {
  margin-bottom: var(--space-2);
  font-size: 14px;
  font-weight: 600;
}

.staff-select {
  width: 100%;
}

.field-tip,
.readonly-tip {
  margin-top: var(--space-2);
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 20px;
}

.staff-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  min-width: 0;
}

.staff-tag {
  max-width: 100%;
}

.readonly-tip {
  text-align: center;
}

@media (max-width: 600px) {
  .staff-dialog-body :deep(.el-button) {
    min-height: 44px;
  }
}
</style>
