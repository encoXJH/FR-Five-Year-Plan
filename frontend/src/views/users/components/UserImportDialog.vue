<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadFile, UploadInstance } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import ResponsiveRecordView from '@/components/ResponsiveRecordView.vue'
import {
  confirmUserImport,
  downloadUserImportTemplate,
  previewUserImport,
} from '@/api/system'
import { useUserStore } from '@/stores/user'
import { enumLabel } from '@/utils/enumLabels'
import { triggerFileDownload } from '@/utils/file'
import { newIdempotencyKey } from '@/utils/idempotency'
import {
  USER_IMPORT_MAX_ROWS,
  userImportPreviewError,
  validateUserImportFile,
  validateUserImportPassword,
} from '@/utils/userImport'
import type {
  UserImportConfirmResult,
  UserImportPreviewResult,
} from '@/types/system'

const props = withDefaults(defineProps<{
  modelValue: boolean
  staffContext?: boolean
}>(), {
  staffContext: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  imported: [result: UserImportConfirmResult]
}>()

const userStore = useUserStore()
const uploadRef = ref<UploadInstance>()
const step = ref(0)
const downloading = ref(false)
const uploading = ref(false)
const confirming = ref(false)
const selectedFile = ref<File | null>(null)
const preview = ref<UserImportPreviewResult | null>(null)
const importResult = ref<UserImportConfirmResult | null>(null)
const initialPassword = ref('')
const idempotencyKey = ref(newIdempotencyKey())

const previewError = computed(() => preview.value ? userImportPreviewError(preview.value) : null)
const hasErrors = computed(() => Boolean(previewError.value))
const passwordError = computed(() => validateUserImportPassword(initialPassword.value))
const canConfirm = computed(() => Boolean(
  preview.value && !hasErrors.value && !passwordError.value && !confirming.value,
))
const hasStaffImport = computed(() => props.staffContext || Boolean(
  preview.value?.rows.some((row) => row.valid && row.roles.includes('STAFF')),
))
const previewAlertTitle = computed(() => {
  if (!preview.value) return ''
  return previewError.value ?? `共 ${preview.value.totalRows} 行，${preview.value.validRows} 行全部通过校验`
})
const resultMessage = computed(() => {
  const base = `成功创建 ${importResult.value?.createdCount ?? 0} 个用户`
  return hasStaffImport.value ? `${base}，新干事尚未分配任务` : base
})

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      resetForReupload()
    } else {
      initialPassword.value = ''
    }
  },
)

function clearFileList(): void {
  void nextTick(() => uploadRef.value?.clearFiles())
}

function resetForReupload(): void {
  if (confirming.value) return
  step.value = 0
  selectedFile.value = null
  preview.value = null
  importResult.value = null
  initialPassword.value = ''
  idempotencyKey.value = newIdempotencyKey()
  clearFileList()
}

async function handleDownloadTemplate(): Promise<void> {
  if (downloading.value) return
  downloading.value = true
  try {
    const blob = await downloadUserImportTemplate()
    const url = URL.createObjectURL(blob)
    triggerFileDownload(url, '用户导入模板.xlsx')
    window.setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch {
    // 拦截器已提示
  } finally {
    downloading.value = false
  }
}

function handleFileChange(file: UploadFile): void {
  if (!file.raw) return
  const error = validateUserImportFile(file.raw)
  if (error) {
    selectedFile.value = null
    clearFileList()
    ElMessage.error(error)
    return
  }
  selectedFile.value = file.raw
  preview.value = null
  importResult.value = null
  initialPassword.value = ''
  idempotencyKey.value = newIdempotencyKey()
}

function handleFileRemove(): void {
  selectedFile.value = null
}

async function handleUploadPreview(): Promise<void> {
  if (uploading.value) return
  if (!selectedFile.value) {
    ElMessage.warning('请先选择要上传的 Excel 文件')
    return
  }
  const error = validateUserImportFile(selectedFile.value)
  if (error) {
    ElMessage.error(error)
    return
  }
  uploading.value = true
  try {
    preview.value = await previewUserImport(selectedFile.value)
    importResult.value = null
    step.value = 1
  } catch {
    // 拦截器已提示
  } finally {
    uploading.value = false
  }
}

async function handleConfirm(): Promise<void> {
  if (!preview.value || hasErrors.value || confirming.value) return
  const error = validateUserImportPassword(initialPassword.value)
  if (error) {
    ElMessage.warning(error)
    return
  }
  confirming.value = true
  try {
    const result = await confirmUserImport({
      importId: preview.value.importId,
      initialPassword: initialPassword.value,
    }, idempotencyKey.value)
    importResult.value = result
    step.value = 2
    ElMessage.success(`导入完成，共创建 ${result.createdCount} 个用户${hasStaffImport.value ? '，新干事尚未分配任务' : ''}`)
    emit('imported', result)
  } catch {
    // 拦截器已提示；确认重试继续复用当前 importId 和幂等键
  } finally {
    confirming.value = false
  }
}

function close(): void {
  if (uploading.value || confirming.value) return
  emit('update:modelValue', false)
}

function handleDialogUpdate(visible: boolean): void {
  if (visible) {
    emit('update:modelValue', true)
  } else {
    close()
  }
}

function roleLabels(roles: string[]): string {
  return roles.length > 0
    ? roles.map((role) => enumLabel('Role', role, userStore.enums)).join('、')
    : '—'
}

function unitCodes(codes: string[]): string {
  return codes.length > 0 ? codes.join('、') : '—'
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="批量导入用户"
    width="min(1080px, calc(100vw - 32px))"
    top="5vh"
    class="user-import-dialog"
    :close-on-click-modal="false"
    :close-on-press-escape="!uploading && !confirming"
    :show-close="!uploading && !confirming"
    @update:model-value="handleDialogUpdate"
  >
    <el-steps :active="step" align-center class="import-steps">
      <el-step title="上传文件" />
      <el-step title="预览并确认" />
      <el-step title="导入结果" />
    </el-steps>

    <section v-if="step === 0" class="upload-step">
      <div class="template-line">
        <el-link type="primary" :disabled="downloading" @click="handleDownloadTemplate">
          {{ downloading ? '正在下载模板…' : '下载 Excel 模板' }}
        </el-link>
        <span class="tip">支持 .xlsx，文件 ≤ 10MB，单次最多 {{ USER_IMPORT_MAX_ROWS }} 行</span>
      </div>
      <el-upload
        ref="uploadRef"
        drag
        :auto-upload="false"
        :show-file-list="true"
        :limit="1"
        :disabled="uploading"
        accept=".xlsx"
        :on-change="handleFileChange"
        :on-remove="handleFileRemove"
      >
        <el-icon :size="40" class="upload-icon"><UploadFilled /></el-icon>
        <div class="el-upload__text">将文件拖到此处，或 <em>点击上传</em></div>
        <template #tip>
          <div class="el-upload__tip">选择文件后点击下方「上传并校验」，初始密码在下一步统一设置</div>
        </template>
      </el-upload>
    </section>

    <section v-else-if="step === 1" class="preview-step">
      <el-alert
        :type="hasErrors ? 'error' : 'success'"
        :closable="false"
        class="preview-alert"
        :title="previewAlertTitle"
        show-icon
      />
      <ResponsiveRecordView v-if="preview">
        <template #table>
          <el-table :data="preview.rows" size="small" border max-height="360">
            <el-table-column prop="rowNum" label="行号" width="64" align="center" fixed="left" />
            <el-table-column prop="name" label="姓名" width="100">
              <template #default="{ row }">{{ row.name || '—' }}</template>
            </el-table-column>
            <el-table-column prop="username" label="工号" width="110">
              <template #default="{ row }">{{ row.username || '—' }}</template>
            </el-table-column>
            <el-table-column prop="phone" label="手机号" width="120">
              <template #default="{ row }">{{ row.phone || '—' }}</template>
            </el-table-column>
            <el-table-column label="角色" min-width="150">
              <template #default="{ row }">{{ roleLabels(row.roles) }}</template>
            </el-table-column>
            <el-table-column label="主学院编码" width="110">
              <template #default="{ row }">{{ row.primaryUnitCode || '—' }}</template>
            </el-table-column>
            <el-table-column label="其他学院编码" min-width="140">
              <template #default="{ row }">{{ unitCodes(row.otherUnitCodes) }}</template>
            </el-table-column>
            <el-table-column label="校验结果" min-width="220" fixed="right">
              <template #default="{ row }">
                <span v-if="row.valid" class="result-ok">通过</span>
                <div v-else class="result-errors">
                  <div v-for="(error, index) in row.errors" :key="index">{{ error }}</div>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <template #cards>
          <div class="import-row-list">
            <article
              v-for="row in preview.rows"
              :key="row.rowNum"
              class="import-row-card"
              :class="{ 'is-invalid': !row.valid }"
            >
              <div class="import-row-header">
                <strong>第 {{ row.rowNum }} 行 · {{ row.name || row.username || '未填写姓名' }}</strong>
                <el-tag :type="row.valid ? 'success' : 'danger'" size="small">
                  {{ row.valid ? '通过' : '有错误' }}
                </el-tag>
              </div>
              <div class="import-row-fields">
                <div><span>工号</span>{{ row.username || '—' }}</div>
                <div><span>手机号</span>{{ row.phone || '—' }}</div>
                <div><span>角色</span>{{ roleLabels(row.roles) }}</div>
                <div><span>主学院</span>{{ row.primaryUnitCode || '—' }}</div>
                <div class="wide-field"><span>其他学院</span>{{ unitCodes(row.otherUnitCodes) }}</div>
              </div>
              <div v-if="!row.valid" class="result-errors card-errors">
                <div v-for="(error, index) in row.errors" :key="index">{{ error }}</div>
              </div>
            </article>
          </div>
        </template>
      </ResponsiveRecordView>

      <el-form label-position="top" class="password-form" @submit.prevent>
        <el-form-item
          label="统一初始密码"
          required
          :error="initialPassword ? (passwordError ?? undefined) : undefined"
        >
          <el-input
            v-model="initialPassword"
            type="password"
            minlength="8"
            maxlength="64"
            show-password
            autocomplete="new-password"
            placeholder="请输入 8–64 位统一初始密码"
            @keyup.enter="handleConfirm"
          />
          <div class="field-tip">8–64 位，将应用到本次批量创建的全部账号。</div>
        </el-form-item>
      </el-form>
    </section>

    <section v-else class="result-step">
      <el-result icon="success" title="导入完成" :sub-title="resultMessage" />
      <el-alert
        v-if="hasStaffImport"
        type="info"
        :closable="false"
        title="新干事暂无任务，可在任务详情中为其分配任务。"
        show-icon
      />
    </section>

    <template #footer>
      <div class="dialog-footer">
        <template v-if="step === 0">
          <el-button :disabled="uploading" @click="close">取消</el-button>
          <el-button type="primary" :loading="uploading" @click="handleUploadPreview">
            上传并校验
          </el-button>
        </template>
        <template v-else-if="step === 1">
          <el-button :disabled="confirming" @click="resetForReupload">重新上传</el-button>
          <el-tooltip
            :disabled="!hasErrors"
            :content="previewError ?? ''"
            placement="top"
          >
            <span class="confirm-button-wrap">
              <el-button
                type="primary"
                :disabled="!canConfirm"
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
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.import-steps {
  margin-bottom: var(--space-5);
}

.template-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.tip,
.field-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 20px;
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
  overflow-wrap: anywhere;
}

.password-form {
  max-width: 480px;
  margin: var(--space-4) auto 0;
}

.field-tip {
  width: 100%;
  margin-top: 4px;
}

.import-row-list {
  display: grid;
  gap: var(--space-3);
  max-height: 360px;
  overflow-y: auto;
}

.import-row-card {
  min-width: 0;
  padding: var(--space-3);
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--radius-card);
}

.import-row-card.is-invalid {
  border-color: var(--el-color-danger-light-5);
  background: var(--el-color-danger-light-9);
}

.import-row-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.import-row-header strong {
  min-width: 0;
  overflow-wrap: anywhere;
}

.import-row-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2) var(--space-4);
  font-size: 13px;
  line-height: 22px;
}

.import-row-fields > div {
  min-width: 0;
  overflow-wrap: anywhere;
}

.import-row-fields span {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.import-row-fields .wide-field,
.card-errors {
  grid-column: 1 / -1;
}

.card-errors {
  margin-top: var(--space-2);
}

.result-step {
  padding: var(--space-4) 0 0;
}

.dialog-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--space-2);
}

.dialog-footer :deep(.el-button) {
  margin-left: 0;
}

.confirm-button-wrap {
  display: inline-flex;
}

:deep(.user-import-dialog) {
  display: flex;
  max-height: calc(100vh - 32px);
  max-height: calc(100dvh - 32px);
  flex-direction: column;
  overflow: hidden;
}

:deep(.user-import-dialog .el-dialog__header),
:deep(.user-import-dialog .el-dialog__footer) {
  flex-shrink: 0;
}

:deep(.user-import-dialog .el-dialog__body) {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}

@media (max-width: 600px) {
  .import-steps {
    margin-bottom: var(--space-4);
  }

  .import-steps :deep(.el-step__title) {
    font-size: 12px;
    line-height: 18px;
  }

  .template-line {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--space-1);
  }

  .dialog-footer > .el-button,
  .dialog-footer > .el-tooltip,
  .dialog-footer > span,
  .confirm-button-wrap,
  .confirm-button-wrap .el-button {
    flex: 1 1 0;
    min-width: 0;
  }

  .dialog-footer .el-button {
    width: 100%;
    min-height: 44px;
  }

  .password-form {
    max-width: none;
  }

  :deep(.user-import-dialog) {
    top: 16px;
    margin-bottom: 16px;
  }

  :deep(.user-import-dialog .el-dialog__header),
  :deep(.user-import-dialog .el-dialog__body),
  :deep(.user-import-dialog .el-dialog__footer) {
    padding-right: var(--space-4);
    padding-left: var(--space-4);
  }

  :deep(.user-import-dialog .el-dialog__body) {
    padding-top: var(--space-3);
    padding-bottom: var(--space-3);
  }
}

@media (max-width: 400px) {
  .import-row-fields {
    grid-template-columns: minmax(0, 1fr);
  }

  .import-row-fields .wide-field {
    grid-column: auto;
  }
}
</style>
