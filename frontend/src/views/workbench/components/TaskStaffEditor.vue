<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { updateTaskStaff } from '@/api/task'
import type { TaskDetail } from '@/types/task'

const props = defineProps<{ task: TaskDetail }>()
const emit = defineEmits<{ saved: [] }>()

const selectedStaffIds = ref<string[]>([])
const initialStaffIds = ref<string[]>([])
const saving = ref(false)

const staff = computed(() => props.task.staff ?? [])
const candidates = computed(() => props.task.assignableStaff ?? [])
const dirty = computed(() => {
  if (selectedStaffIds.value.length !== initialStaffIds.value.length) return true
  const initial = new Set(initialStaffIds.value)
  return selectedStaffIds.value.some((id) => !initial.has(id))
})

watch(
  () => props.task,
  (task) => {
    const ids = [...new Set((task.staff ?? []).map((item) => item.id))]
    selectedStaffIds.value = [...ids]
    initialStaffIds.value = ids
    saving.value = false
  },
  { immediate: true, deep: true },
)

function reset(): void {
  selectedStaffIds.value = [...initialStaffIds.value]
}

async function save(): Promise<void> {
  if (!props.task.canAssignStaff || saving.value || !dirty.value) return
  saving.value = true
  try {
    await updateTaskStaff(props.task.id, {
      staffIds: [...new Set(selectedStaffIds.value)],
      version: props.task.version,
    })
    ElMessage.success('干事分配已保存')
    emit('saved')
  } catch {
    // 拦截器已提示，保留当前选择便于修正或刷新后重试
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="task-staff-editor" aria-label="任务干事">
    <template v-if="task.canAssignStaff">
      <el-alert
        type="info"
        :closable="false"
        title="可添加或移除本人干事，保存后一次性生效；移除后该干事立即失去任务访问权限。"
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
        no-data-text="暂无可分配干事，请先在“用户添加”中创建"
      >
        <el-option
          v-for="item in candidates"
          :key="item.id"
          :label="item.name"
          :value="item.id"
        />
      </el-select>
      <div class="field-tip">候选人仅包含您创建的、属于该任务学院的启用干事。</div>
      <div class="staff-actions">
        <el-button :disabled="!dirty || saving" @click="reset">取消修改</el-button>
        <el-button type="primary" :disabled="!dirty" :loading="saving" @click="save">
          保存分配
        </el-button>
      </div>
    </template>

    <template v-else>
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
      <el-empty v-else :image-size="72" description="该任务尚未分配干事" />
      <div class="readonly-tip">当前任务仅可查看干事名单，任务分配仅限负责人本人。</div>
    </template>
  </section>
</template>

<style scoped>
.task-staff-editor {
  min-width: 0;
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

.staff-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-5);
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
  .staff-actions :deep(.el-button) {
    min-height: 44px;
  }
}
</style>
