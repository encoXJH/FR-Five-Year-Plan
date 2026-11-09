<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { showToast } from 'vant'
import {
  downloadImportTemplate,
  importConfirm,
  importPreview,
} from '@/api/indicator'
import { newIdempotencyKey } from '@/utils/idempotency'
import { triggerFileDownload } from '@/utils/file'
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
const fileInput = ref<HTMLInputElement | null>(null)
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
    triggerFileDownload(url, '指标导入模板.xlsx')
    window.setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch {
    // 拦截器已提示
  }
}

function onPick(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!file.name.endsWith('.xlsx')) {
    showToast('仅支持 .xlsx 格式文件')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    showToast('文件不能超过 10MB')
    return
  }
  selectedFile.value = file
}

async function handleUploadPreview(): Promise<void> {
  if (!selectedFile.value) {
    showToast('请先选择要上传的 Excel 文件')
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
    showToast(`导入完成，共创建 ${result.aCount} 条指标`)
    emit('imported')
  } catch {
    // 拦截器已提示
  } finally {
    confirming.value = false
  }
}

function close(): void {
  emit('update:modelValue', false)
}
</script>

<template>
  <van-popup
    :show="modelValue"
    position="bottom"
    round
    :style="{ height: '86vh' }"
    @update:show="emit('update:modelValue', $event)"
  >
    <div class="import-sheet">
      <div class="sheet-head">
        <strong>导入 Excel</strong>
        <button type="button" class="close-btn" @click="close">
          <van-icon name="cross" size="18" />
        </button>
      </div>

      <div class="sheet-body">
        <van-steps :active="step" active-color="#2B5BC0">
          <van-step>上传文件</van-step>
          <van-step>预览校验</van-step>
          <van-step>导入结果</van-step>
        </van-steps>

        <div v-if="step === 0" class="step-pane">
          <button type="button" class="text-link" @click="handleDownloadTemplate">
            下载导入模板（.xlsx）
          </button>
          <button type="button" class="upload-box" @click="fileInput?.click()">
            <van-icon name="upgrade" size="30" />
            <span>{{ selectedFile?.name ?? '点击选择 .xlsx 文件' }}</span>
            <small>支持 .xlsx，不超过 10MB</small>
            <input ref="fileInput" type="file" accept=".xlsx" hidden @change="onPick" />
          </button>
        </div>

        <div v-else-if="step === 1" class="step-pane">
          <div v-if="preview" class="preview-tip" :class="hasErrors ? 'is-error' : 'is-success'">
            {{ hasErrors
              ? `共 ${preview.totalRows} 行，${preview.invalidRows} 行存在错误，修正后请重新上传`
              : `共 ${preview.totalRows} 行，全部通过校验` }}
          </div>
          <div class="preview-list">
            <div v-for="row in preview?.rows ?? []" :key="row.rowNum" class="preview-row">
              <div class="row--between">
                <strong>第 {{ row.rowNum }} 行 · {{ row.code ?? '—' }}</strong>
                <span :class="row.valid ? 'text-success' : 'text-danger'">{{ row.valid ? '通过' : '错误' }}</span>
              </div>
              <div class="small">{{ row.name ?? '—' }}</div>
              <div v-if="!row.valid" class="small text-danger">
                <div v-for="(error, index) in row.errors" :key="index">{{ error }}</div>
              </div>
            </div>
          </div>
        </div>

        <div v-else class="step-pane result-pane">
          <van-icon name="passed" size="52" color="#1FA971" />
          <strong>导入完成</strong>
          <p class="muted">指标数据已写入，可在指标总表中查看</p>
        </div>
      </div>

      <div class="sheet-footer">
        <template v-if="step === 0">
          <van-button block @click="close">取消</van-button>
          <van-button block type="primary" :loading="uploading" @click="handleUploadPreview">上传并校验</van-button>
        </template>
        <template v-else-if="step === 1">
          <van-button block @click="step = 0">重新上传</van-button>
          <van-button
            block
            type="primary"
            :disabled="hasErrors"
            :loading="confirming"
            @click="handleConfirm"
          >确认导入</van-button>
        </template>
        <van-button v-else block type="primary" @click="close">完成</van-button>
      </div>
    </div>
  </van-popup>
</template>

<style scoped>
.import-sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
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
.sheet-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px;
}
.sheet-footer {
  display: flex;
  gap: 10px;
  padding: 10px 16px calc(10px + var(--safe-bottom));
  border-top: 1px solid var(--line);
}
.step-pane {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 14px;
}
.text-link {
  align-self: flex-start;
  min-height: 36px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--brand);
  font-size: 13px;
}
.upload-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 130px;
  border: 1px dashed #c6d2e4;
  border-radius: 12px;
  background: #f8fafd;
  color: var(--brand);
  font-size: 13px;
}
.upload-box small {
  color: var(--text-placeholder);
  font-size: 11px;
}
.preview-tip {
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 12px;
}
.preview-tip.is-success {
  background: #e8f7f0;
  color: var(--success);
}
.preview-tip.is-error {
  background: #fdecec;
  color: var(--danger);
}
.preview-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.preview-row {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
}
.result-pane {
  align-items: center;
  padding: 40px 0;
  text-align: center;
}
.result-pane p {
  margin: 0;
  font-size: 13px;
}
</style>
