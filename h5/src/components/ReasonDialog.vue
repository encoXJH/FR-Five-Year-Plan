<script setup lang="ts">
import { ref, watch } from 'vue'
import { showToast } from 'vant'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title?: string
    label?: string
    placeholder?: string
    required?: boolean
    loading?: boolean
    confirmText?: string
    danger?: boolean
  }>(),
  {
    title: '填写原因',
    label: '原因',
    placeholder: '请填写原因（将记录到审计日志）',
    required: true,
    loading: false,
    confirmText: '确认',
    danger: false,
  },
)

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

function submit(): void {
  const value = reason.value.trim()
  if (props.required && !value) {
    showToast(`请填写${props.label}`)
    return
  }
  emit('confirm', value)
}
</script>

<template>
  <van-popup
    :show="modelValue"
    position="bottom"
    round
    safe-area-inset-bottom
    @update:show="emit('update:modelValue', $event)"
  >
    <div class="reason-sheet">
      <div class="reason-sheet__header">
        <strong>{{ title }}</strong>
        <button type="button" class="close-btn" @click="emit('update:modelValue', false)">
          <van-icon name="cross" size="18" />
        </button>
      </div>
      <label class="reason-sheet__label">{{ label }}<span v-if="required" class="required">*</span></label>
      <van-field
        v-model="reason"
        type="textarea"
        :placeholder="placeholder"
        :rows="4"
        maxlength="500"
        show-word-limit
        autosize
      />
      <div class="reason-sheet__actions">
        <van-button block :disabled="loading" @click="emit('update:modelValue', false)">取消</van-button>
        <van-button
          block
          :type="danger ? 'danger' : 'primary'"
          :loading="loading"
          @click="submit"
        >{{ confirmText }}</van-button>
      </div>
    </div>
  </van-popup>
</template>

<style scoped>
.reason-sheet {
  padding: 16px 16px calc(16px + var(--safe-bottom));
}
.reason-sheet__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
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
.reason-sheet__label {
  display: block;
  margin-bottom: 6px;
  color: var(--text-secondary);
  font-size: 13px;
}
.required {
  color: var(--danger);
}
.reason-sheet :deep(.van-field) {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  padding: 10px;
}
.reason-sheet__actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}
</style>
