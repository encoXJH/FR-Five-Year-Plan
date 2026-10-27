<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * 敏感操作原因弹窗（界面设计规范 7.5）：
 * 顶部警告条 + 原因必填（≤500 字，显示字数）+ 原因为空时确认按钮置灰。
 */
const props = defineProps<{
  modelValue: boolean
  /** 标题 = 动作，如「解锁 2026 年度数据」 */
  title: string
  /** 对象说明，如「2026–2030 五年发展规划 · 2026 年度」 */
  target?: string
  /** 确认按钮文字，如「确认解锁」 */
  confirmText?: string
  loading?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: [reason: string]
}>()

const reason = ref('')

watch(
  () => props.modelValue,
  (open) => {
    if (open) reason.value = ''
  },
)

const trimmed = computed(() => reason.value.trim())

function close(): void {
  emit('update:modelValue', false)
}

function submit(): void {
  if (!trimmed.value) return
  emit('confirm', trimmed.value)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="480px"
    @update:model-value="close"
  >
    <el-alert type="warning" :closable="false" show-icon class="reason-alert">
      该操作属于敏感操作，将记录操作人、时间和原因，并通知相关干系人。
    </el-alert>
    <p v-if="target" class="reason-target">对象：{{ target }}</p>
    <el-form label-position="top">
      <el-form-item label="原因" required>
        <el-input
          v-model="reason"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
          placeholder="请填写原因（必填，≤ 500 字）"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button
        type="primary"
        :disabled="!trimmed"
        :loading="loading"
        @click="submit"
      >
        {{ confirmText ?? '确认' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.reason-alert {
  margin-bottom: var(--space-3);
}

.reason-target {
  margin: 0 0 var(--space-2);
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
