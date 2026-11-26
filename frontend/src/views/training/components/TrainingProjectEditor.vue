<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import MaterialUploader from '@/views/submission/components/MaterialUploader.vue'
import {
  calculateTrainingFunding,
  calculateTrainingValues,
  emptyTrainingProjectData,
  groupTrainingFields,
  isTrainingReadonlyField,
  validateTrainingProject,
} from '@/utils/training'
import type {
  TrainingCellValue,
  TrainingField,
  TrainingProjectData,
  TrainingProjectInput,
} from '@/types/training'
import type { FileInfo } from '@/types/file'

const props = withDefaults(defineProps<{
  modelValue: boolean
  fields: TrainingField[]
  project: TrainingProjectInput | null
  readonly?: boolean
  isNew?: boolean
}>(), {
  readonly: false,
  isNew: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  save: [project: TrainingProjectInput]
}>()

const data = ref<TrainingProjectData>(emptyTrainingProjectData())
const rowNo = ref(1)
const fileIds = ref<string[]>([])
const initialFiles = ref<FileInfo[]>([])
const attachmentKey = ref(0)
const attachmentUploader = ref<InstanceType<typeof MaterialUploader> | null>(null)
const groups = computed(() => groupTrainingFields(props.fields))
const funding = computed(() => calculateTrainingFunding(data.value))
const peopleGroups = new Set(['AUDIENCE', 'INDUSTRY', 'REGION'])

function isPeopleGroup(group: string): boolean {
  return peopleGroups.has(group)
}

function readonlyDisplayValue(group: string, key: string): unknown {
  const value = data.value[key] ?? ''
  if (value !== '' && isPeopleGroup(group) && ['c32', 'c44', 'c54'].includes(key)) {
    return `${value} 人`
  }
  return value
}
const title = computed(() => {
  if (props.readonly) return '查看培训项目'
  return props.isNew ? '新增培训项目' : '编辑培训项目'
})

watch(
  () => [props.modelValue, props.project] as const,
  ([visible, project]) => {
    if (!visible) return
    rowNo.value = project?.rowNo ?? 1
    data.value = calculateTrainingValues(project?.data ?? emptyTrainingProjectData())
    const nextFileIds = [...(project?.fileIds ?? project?.files?.map((file) => file.id) ?? [])]
    fileIds.value = nextFileIds
    initialFiles.value = (project?.files ?? []).filter((file) => nextFileIds.includes(file.id))
    attachmentKey.value += 1
  },
  { immediate: true, deep: true },
)

function setValue(key: string, value: unknown): void {
  const cellValue = value === undefined ? null : value as TrainingCellValue
  data.value = calculateTrainingValues({ ...data.value, [key]: cellValue })
}

function close(): void {
  emit('update:modelValue', false)
}

function confirm(): void {
  const project: TrainingProjectInput = {
    ...props.project,
    rowNo: rowNo.value,
    data: calculateTrainingValues(data.value),
    fileIds: [...fileIds.value],
    files: initialFiles.value.filter((file) => fileIds.value.includes(file.id)),
  }
  const issues = validateTrainingProject(project, props.fields, 'draft')
  if (issues.length > 0) {
    ElMessage.warning(issues[0].message)
    return
  }
  emit('save', project)
  close()
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    size="min(920px, 100%)"
    class="training-project-drawer"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      v-if="!readonly"
      type="info"
      :closable="false"
      title="草稿允许暂缺必填项；人数合计与到账经费自动计算，提交前会统一校验。"
      class="editor-alert"
    />
    <el-alert
      v-else
      type="success"
      :closable="false"
      title="该项目正在审核或已审核通过，内容只读。"
      class="editor-alert"
    />

    <el-form label-position="top" class="training-project-form" @submit.prevent>
      <section
        v-for="group in groups"
        :key="group.group"
        class="training-field-group"
        :aria-label="group.groupLabel"
      >
        <div class="training-field-group__heading">
          <h2 class="training-field-group__title">{{ group.groupLabel }}</h2>
          <span v-if="isPeopleGroup(group.group)" class="training-field-group__unit">人数单位：人</span>
        </div>
        <div class="training-field-grid">
          <el-form-item
            v-for="field in group.fields"
            :key="field.key"
            :label="field.label"
            :required="field.required && !readonly && field.key !== 'c57'"
            class="training-field"
          >
            <el-input
              v-if="isTrainingReadonlyField(field.key)"
              :model-value="readonlyDisplayValue(group.group, field.key)"
              readonly
              :placeholder="field.key === 'c57' ? '保存后由系统填写' : '自动计算'"
            />
            <el-select
              v-else-if="field.type === 'SELECT'"
              :model-value="data[field.key]"
              :disabled="readonly"
              :clearable="!readonly"
              filterable
              :placeholder="`请选择${field.label}`"
              @update:model-value="setValue(field.key, $event)"
            >
              <el-option
                v-for="option in field.options"
                :key="option"
                :label="option"
                :value="option"
              />
            </el-select>
            <el-date-picker
              v-else-if="field.type === 'DATE'"
              :model-value="data[field.key]"
              :disabled="readonly"
              :clearable="!readonly"
              type="date"
              value-format="YYYY-MM-DD"
              :placeholder="`请选择${field.label}`"
              @update:model-value="setValue(field.key, $event)"
            />
            <el-input-number
              v-else-if="field.type === 'DECIMAL' || field.type === 'INTEGER'"
              :model-value="data[field.key] ?? undefined"
              :disabled="readonly"
              :min="0"
              :precision="field.type === 'INTEGER' ? 0 : undefined"
              :controls="false"
              :placeholder="isPeopleGroup(group.group) ? `请输入${field.label}（人）` : `请输入${field.label}`"
              @update:model-value="setValue(field.key, $event)"
            />
            <el-input
              v-else
              :model-value="data[field.key] ?? ''"
              :disabled="readonly"
              clearable
              :placeholder="`请输入${field.label}`"
              @update:model-value="setValue(field.key, $event)"
            />
            <div v-if="isTrainingReadonlyField(field.key)" class="field-tip">
              {{ field.key === 'c57' ? '填报人由服务端按当前用户认定' : '根据本组人数自动汇总' }}
            </div>
          </el-form-item>
        </div>
        <div v-if="group.fields.some((field) => field.key === 'c10')" class="funding-total">
          <span>到账经费（万元，财政资金 + 非财政资金）</span>
          <strong>{{ funding ?? '—' }}</strong>
        </div>
      </section>
    </el-form>

    <section class="training-field-group training-attachments" aria-label="项目附件">
      <div class="training-field-group__heading">
        <h2 class="training-field-group__title">项目附件</h2>
        <el-button v-if="!readonly" type="primary" plain size="small" @click="attachmentUploader?.openPicker()">
          上传附件
        </el-button>
      </div>
      <div class="field-tip attachment-tip">
        {{ readonly ? '项目附件仅支持预览和下载。' : '项目附件随“保存草稿”生效；点击“确定”仅暂存到当前项目。' }}
      </div>
      <MaterialUploader
        ref="attachmentUploader"
        :key="attachmentKey"
        v-model="fileIds"
        :initial-files="initialFiles"
        :disabled="readonly"
        biz-type="TRAINING_ATTACHMENT"
      />
    </section>

    <template #footer>
      <div class="editor-footer">
        <el-button v-if="readonly" type="primary" @click="close">关闭</el-button>
        <template v-else>
          <el-button @click="close">取消</el-button>
          <el-button type="primary" @click="confirm">确定</el-button>
        </template>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.editor-alert {
  margin-bottom: 20px;
}

.training-project-form {
  min-width: 0;
}

.training-field-group {
  padding: 0 0 20px;
  margin: 0 0 20px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.training-field-group:last-child {
  padding-bottom: 0;
  margin-bottom: 0;
  border-bottom: 0;
}

.training-field-group__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0 0 16px;
}

.training-field-group__title {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 16px;
  font-weight: 600;
}

.training-field-group__unit {
  flex: none;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 20px;
}

.training-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
}

.training-field {
  min-width: 0;
}

.training-field :deep(.el-select),
.training-field :deep(.el-date-editor),
.training-field :deep(.el-input-number) {
  width: 100%;
}

.field-tip {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 18px;
}

.training-attachments {
  margin-top: 20px;
}

.attachment-tip {
  margin: -4px 0 12px;
}

.funding-total {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border-radius: 6px;
  font-size: 13px;
}

.funding-total strong {
  flex: none;
  color: var(--el-color-primary);
  font-size: 18px;
  font-variant-numeric: tabular-nums;
}

.editor-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

@media (max-width: 600px) {
  .training-field-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .training-field-group__title {
    font-size: 15px;
  }

  .funding-total {
    align-items: flex-start;
    padding: 12px;
  }

  .editor-footer :deep(.el-button) {
    min-width: 96px;
    min-height: 44px;
  }
}
</style>

<style>
.training-project-drawer .el-drawer__body {
  min-width: 0;
}

@media (max-width: 600px) {
  .training-project-drawer .el-drawer__header {
    padding: 16px;
    margin-bottom: 0;
  }

  .training-project-drawer .el-drawer__body {
    padding: 16px;
  }

  .training-project-drawer .el-drawer__footer {
    padding: 12px 16px;
  }
}
</style>
