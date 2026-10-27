<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadFile, UploadRawFile } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import {
  downloadImportTemplate,
  importConfirm,
  importPreview,
} from '@/api/indicator'
import { newIdempotencyKey } from '@/utils/idempotency'
import type { ImportPreviewResult } from '@/types/indicator'

const props = defineProps<{
  modelValue: boolean
  planId: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  imported: []
}>()

const step = ref(0)
const uploading = ref(false)
const confirming = ref(false)
const preview = ref<ImportPreviewResult | null>(null)
const selectedFile = ref<File | null>(null)
const idempotencyKey = ref('')

const hasErrors = computed(() => (preview.value?.invalidRows ?? 0) > 0)

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      step.value = 0
      preview.value = null
      selectedFile.value = null
      idempotencyKey.value = newIdempotencyKey()
    }
  },
)

async function handleDownloadTemplate(): Promise<void> {
  try {
    const blob = await downloadImportTemplate(props.planId)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = '指标导入模板.xlsx'
    link.click()
    URL.revokeObjectURL(url)
  } catch {
    // 拦截器已提示
  }
}

function beforeUpload(rawFile: UploadRawFile): boolean {
  const isXlsx =
    rawFile.name.endsWith('.xlsx') ||
    rawFile.type === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
  if (!isXlsx) {
    ElMessage.error('仅支持 .xlsx 格式文件')
    return false
  }
  if (rawFile.size > 10 * 1024 * 1024) {
    ElMessage.error('文件不能超过 10MB')
    return false
  }
  selectedFile.value = rawFile
  return false
}

function onFileChange(file: UploadFile): void {
  if (file.raw) {
    selectedFile.value = file.raw
  }
}

async function handleUploadPreview(): Promise<void> {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择要上传的 Excel 文件')
    return
  }
  uploading.value = true
  try {
    preview.value = await importPreview(props.planId, selectedFile.value)
    step.value = 1
  } catch {
    // 拦截器已提示
  } finally {
    uploading.value = false
  }
}

async function handleConfirm(): Promise<void> {
  if (!preview.value) return
  confirming.value = true
  try {
    const result = await importConfirm(
      props.planId,
      { importId: preview.value.importId },
      idempotencyKey.value,
    )
    step.value = 2
    ElMessage.success(`导入完成，共创建 ${result.createdCount} 条指标`)
    emit('imported')
  } catch {
    // 拦截器已提示；40001 等错误信息已由拦截器展示
  } finally {
    confirming.value = false
  }
}

function close(): void {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="导入 Excel"
    width="760px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-steps :active="step" align-center class="import-steps">
      <el-step title="上传文件" />
      <el-step title="预览校验" />
      <el-step title="导入结果" />
    </el-steps>

    <div v-show="step === 0">
      <div class="template-line">
        <el-link type="primary" @click="handleDownloadTemplate">下载导入模板（.xlsx）</el-link>
        <span class="tip">支持 .xlsx，不超过 10MB</span>
      </div>
      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="true"
        :limit="1"
        accept=".xlsx"
        :before-upload="beforeUpload"
        :on-change="onFileChange"
      >
        <el-icon :size="40" class="upload-icon"><UploadFilled /></el-icon>
        <div class="el-upload__text">将文件拖到此处，或 <em>点击上传</em></div>
        <template #tip>
          <div class="el-upload__tip">选择文件后点击下方「上传并校验」</div>
        </template>
      </el-upload>
    </div>

    <div v-show="step === 1">
      <el-alert
        v-if="preview"
        :type="hasErrors ? 'error' : 'success'"
        :closable="false"
        class="preview-alert"
        :title="
          hasErrors
            ? `共 ${preview.totalRows} 行，${preview.invalidRows} 行存在错误，修正后请重新上传`
            : `共 ${preview.totalRows} 行，全部通过校验`
        "
      />
      <el-table v-if="preview" :data="preview.rows" size="small" border max-height="360">
        <el-table-column prop="row" label="行号" width="64" align="center" />
        <el-table-column prop="level" label="层级" width="70" align="center" />
        <el-table-column prop="code" label="编号" width="100" />
        <el-table-column prop="parentCode" label="上级编号" width="100" />
        <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="totalTarget" label="五年目标" width="90" align="right" />
        <el-table-column label="校验结果" min-width="220">
          <template #default="{ row }">
            <span v-if="row.valid" class="result-ok">通过</span>
            <div v-else class="result-errors">
              <div v-for="(error, index) in row.errors" :key="index">{{ error }}</div>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-show="step === 2" class="result-step">
      <el-result icon="success" title="导入完成" sub-title="指标数据已写入，可在指标总表中查看" />
    </div>

    <template #footer>
      <template v-if="step === 0">
        <el-button @click="close">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="handleUploadPreview">
          上传并校验
        </el-button>
      </template>
      <template v-else-if="step === 1">
        <el-button @click="step = 0">重新上传</el-button>
        <el-tooltip
          :disabled="!hasErrors"
          content="存在错误行，请修正后重新上传"
          placement="top"
        >
          <span>
            <el-button
              type="primary"
              :disabled="hasErrors"
              :loading="confirming"
              @click="handleConfirm"
            >
              确认导入
            </el-button>
          </span>
        </el-tooltip>
      </template>
      <template v-else>
        <el-button type="primary" @click="close">完成</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.import-steps {
  margin-bottom: var(--space-5);
}

.template-line {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.upload-icon {
  color: var(--el-text-color-secondary);
}

.preview-alert {
  margin-bottom: var(--space-3);
}

.result-ok {
  color: var(--el-color-success);
}

.result-errors {
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 20px;
}

.result-step {
  padding: var(--space-4) 0;
}
</style>
