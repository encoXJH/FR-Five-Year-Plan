<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { Numeric, ValueKind } from '@/types/api'

type EditMode = 'VALUE' | 'EMPTY' | 'NA'
const props = withDefaults(defineProps<{
  value?: Numeric | null
  rawValue?: string | null
  textValue?: string | null
  boolValue?: boolean | number | null
  valueKind?: ValueKind
  unit?: string
  disabled?: boolean
  placeholder?: string
}>(), { valueKind: 'DECIMAL', placeholder: '请输入目标值', boolValue: null })
const emit = defineEmits<{
  'update:value': [value: Numeric | null]
  'update:rawValue': [value: string | null]
  'update:textValue': [value: string | null]
  'update:boolValue': [value: number | null]
}>()
function hasValue() { return props.value !== null && props.value !== undefined && props.value !== '' }
function hasText() { return typeof props.textValue === 'string' && props.textValue.trim().length > 0 }
function hasBool() { return props.boolValue !== null && props.boolValue !== undefined }
function hasRaw() { return typeof props.rawValue === 'string' && props.rawValue.trim().length > 0 }
function deriveMode(): EditMode {
  if (props.valueKind === 'NONE' || props.rawValue?.trim() === '—') return 'NA'
  return hasValue() || hasText() || hasBool() || hasRaw() ? 'VALUE' : 'EMPTY'
}
const mode = ref<EditMode>(deriveMode())
let syncingMode: EditMode | null = null
watch(() => [props.value, props.rawValue, props.textValue, props.boolValue, props.valueKind], () => {
  if (syncingMode) { mode.value = syncingMode; return }
  const nextMode = deriveMode()
  // 用户主动选择“填写值”后，清空或输入过程中的空值不应再把编辑器切回“未提供”。
  if (mode.value === 'VALUE' && nextMode === 'EMPTY') return
  mode.value = nextMode
})
function clearValues() {
  emit('update:value', null)
  emit('update:textValue', null)
  emit('update:boolValue', null)
}
function changeMode(value: EditMode) {
  syncingMode = value
  mode.value = value
  clearValues()
  emit('update:rawValue', value === 'NA' ? '—' : null)
  void nextTick(() => { syncingMode = null })
}
function updateNumericText(input: string) {
  const raw = input.trim()
  emit('update:rawValue', raw || null)
  if (!raw) {
    emit('update:value', null)
    return
  }
  const valid = /^-?(?:\d+(?:\.\d*)?|\.\d+)$/.test(raw)
  emit('update:value', valid ? raw : null)
}
function updateText(value: string) {
  emit('update:textValue', value || null)
  emit('update:rawValue', value || null)
}
function updateBool(value: number) {
  emit('update:boolValue', value)
  emit('update:rawValue', value === 1 ? '是' : '否')
}
</script>
<template>
  <div class="value-editor">
    <template v-if="mode === 'VALUE'">
      <el-radio-group v-if="valueKind === 'BOOLEAN'" :model-value="boolValue === null || boolValue === undefined ? undefined : Number(boolValue)" :disabled="disabled" @change="updateBool(Number($event))">
        <el-radio-button :value="1">是</el-radio-button><el-radio-button :value="0">否</el-radio-button>
      </el-radio-group>
      <el-input v-else-if="['RATIO', 'COMPOUND_DECIMAL'].includes(valueKind)" :model-value="textValue || rawValue || ''" :disabled="disabled" :placeholder="valueKind === 'RATIO' ? '如 1:350' : '请输入复合口径'" @update:model-value="updateText" />
      <el-input v-else :model-value="rawValue ?? value?.toString() ?? ''" :disabled="disabled" :placeholder="placeholder" inputmode="decimal" @update:model-value="updateNumericText" />
    </template>
    <div v-else class="semantic-placeholder" :class="{ 'is-na': mode === 'NA' }">{{ mode === 'NA' ? '不适用' : '未提供' }}</div>
    <div class="value-semantics">
      <el-select :model-value="mode" size="small" :disabled="disabled || valueKind === 'NONE'" style="width: 91px" @change="changeMode">
        <el-option label="填写值" value="VALUE" /><el-option label="未提供" value="EMPTY" /><el-option label="不适用" value="NA" />
      </el-select>
      <span v-if="unit && unit !== '—'" class="subtle small">{{ unit }}</span>
    </div>
  </div>
</template>
<style scoped>
.semantic-placeholder { height: 32px; padding: 0 11px; display: flex; align-items: center; color: #99a4b5; background: #f5f6f8; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px; }
.semantic-placeholder.is-na { color: #758297; font-style: italic; }
</style>
