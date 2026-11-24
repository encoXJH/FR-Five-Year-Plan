<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ResponsiveRecordView from '@/components/ResponsiveRecordView.vue'
import StatusTag from '@/components/StatusTag.vue'
import TrainingProjectEditor from './components/TrainingProjectEditor.vue'
import {
  exportTrainingSheet,
  getTrainingSheet,
  reviewTrainingSheet,
  saveTrainingProjects,
  submitTrainingSheet,
} from '@/api/training'
import { useUserStore } from '@/stores/user'
import { newIdempotencyKey } from '@/utils/idempotency'
import { triggerFileDownload } from '@/utils/file'
import { EMPTY_DISPLAY, formatCount, formatDate, formatDateTime, formatMoney } from '@/utils/format'
import {
  calculateTrainingFunding,
  calculateTrainingValues,
  emptyTrainingProjectData,
  toTrainingProjectInputs,
  trainingNumber,
  validateTrainingProjects,
} from '@/utils/training'
import type {
  TrainingProjectData,
  TrainingProjectInput,
  TrainingSheetDetail,
} from '@/types/training'
import type { ReviewResult } from '@/types/enums'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const sheetId = computed(() => typeof route.params.sheetId === 'string' ? route.params.sheetId : '')

const loading = ref(false)
const loadFailed = ref(false)
const detail = ref<TrainingSheetDetail | null>(null)
const projects = ref<TrainingProjectInput[]>([])
const savedSnapshot = ref('[]')
let loadGeneration = 0

const sheet = computed(() => detail.value?.sheet ?? null)
const fields = computed(() => detail.value?.fields ?? [])
const reviewHistory = computed(() => detail.value?.reviews ?? [])
const previewApprovedOnly = computed(() => route.query.from === 'plans'
  && (userStore.roles.includes('ADMIN') || userStore.roles.includes('LEADER')))
const editable = computed(() => !previewApprovedOnly.value && sheet.value?.canEdit === true)
const reviewable = computed(() => !previewApprovedOnly.value && sheet.value?.canReview === true)
const readonly = computed(() => !editable.value)
const dirty = computed(() => JSON.stringify(toTrainingProjectInputs(projects.value)) !== savedSnapshot.value)
const selectedProjectIds = ref<string[]>([])
const submittableProjects = computed(() => previewApprovedOnly.value ? [] : projects.value.filter((project) =>
  Boolean(project.id) && project.canEdit === true
  && (project.status === 'DRAFT' || project.status === 'REJECTED')))
const approvedCount = computed(() => projects.value.filter((project) => project.status === 'APPROVED').length)
const backLabel = computed(() => route.query.from === 'reviews'
  ? '返回审核中心'
  : route.query.from === 'plans'
    ? '返回计划首页'
    : route.query.from === 'training' ? '返回职业培训填写' : '返回学院工作台')
const submitTargetLabel = computed(() => userStore.roles.includes('STAFF')
  && !userStore.roles.includes('UNIT_OWNER') && !userStore.roles.includes('ADMIN')
  ? '学院负责人审核'
  : '校级负责人审核')

function normalizedProjects(source: TrainingProjectInput[]): TrainingProjectInput[] {
  return source.map((project, index) => ({
    ...project,
    rowNo: index + 1,
    data: calculateTrainingValues(project.data),
  }))
}

function applyDetail(value: TrainingSheetDetail): void {
  detail.value = value
  const next = [...value.projects]
    .filter((project) => !previewApprovedOnly.value || project.status === 'APPROVED')
    .sort((a, b) => a.rowNo - b.rowNo)
    .map((project) => ({ ...project }))
  projects.value = normalizedProjects(next)
  selectedProjectIds.value = selectedProjectIds.value.filter((id) =>
    submittableProjects.value.some((project) => project.id === id))
  savedSnapshot.value = JSON.stringify(toTrainingProjectInputs(projects.value))
}

const editorVisible = ref(false)
const editorProject = ref<TrainingProjectInput | null>(null)
const editorIndex = ref(-1)
const editorIsNew = ref(false)
const editorReadonly = computed(() => readonly.value || editorProject.value?.canEdit === false)

function isSubmittable(project: TrainingProjectInput): boolean {
  return submittableProjects.value.some((item) => item.id === project.id)
}

function toggleProjectSelection(project: TrainingProjectInput, checked: boolean): void {
  if (!project.id || !isSubmittable(project)) return
  selectedProjectIds.value = checked
    ? [...new Set([...selectedProjectIds.value, project.id])]
    : selectedProjectIds.value.filter((id) => id !== project.id)
}

function handleSelectionChange(rows: TrainingProjectInput[]): void {
  selectedProjectIds.value = rows.map((row) => row.id).filter((id): id is string => Boolean(id))
}

async function loadRoute(): Promise<void> {
  const generation = ++loadGeneration
  loading.value = true
  loadFailed.value = false
  editorVisible.value = false
  try {
    if (!sheetId.value) {
      loadFailed.value = true
      return
    }
    const result = await getTrainingSheet(sheetId.value)
    if (generation !== loadGeneration) return
    applyDetail(result)
  } catch {
    if (generation === loadGeneration) loadFailed.value = true
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}

watch(sheetId, () => {
  void loadRoute()
}, { immediate: true })

function backToList(): void {
  const target = route.query.from === 'reviews'
    ? '/reviews'
    : route.query.from === 'plans'
      ? '/plans'
      : route.query.from === 'training' ? '/training' : '/workbench'
  void router.push(target)
}

function openCreate(): void {
  if (readonly.value) return
  editorIndex.value = -1
  editorIsNew.value = true
  editorProject.value = {
    rowNo: projects.value.length + 1,
    data: emptyTrainingProjectData(),
    status: 'DRAFT',
    revisionNo: 0,
    canEdit: true,
    canReview: false,
  }
  editorVisible.value = true
}

function openProject(index: number): void {
  const project = projects.value[index]
  if (!project) return
  editorIndex.value = index
  editorIsNew.value = false
  editorProject.value = {
    ...project,
    data: { ...project.data },
  }
  editorVisible.value = true
}

function handleEditorSave(project: TrainingProjectInput): void {
  const next = [...projects.value]
  if (editorIndex.value < 0) {
    next.push(project)
  } else {
    next.splice(editorIndex.value, 1, project)
  }
  projects.value = normalizedProjects(next)
}

async function removeProject(index: number): Promise<void> {
  if (readonly.value) return
  const project = projects.value[index]
  if (!project || project.canEdit === false) return
  try {
    await ElMessageBox.confirm(
      `确认从当前草稿中移除「${projectName(project.data)}」？点击“保存草稿”后生效。`,
      '移除培训项目',
      { confirmButtonText: '确认移除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  projects.value = normalizedProjects(projects.value.filter((_, itemIndex) => itemIndex !== index))
}

function projectName(data: TrainingProjectData): string {
  const value = data.c01
  const name = value === null || value === undefined ? '' : String(value).trim()
  return name || '未命名培训项目'
}

function projectDates(data: TrainingProjectData): string {
  const start = data.c05 ? formatDate(data.c05) : ''
  const end = data.c06 ? formatDate(data.c06) : ''
  if (!start && !end) return EMPTY_DISPLAY
  return start && end ? `${start} 至 ${end}` : start || end
}

function projectPeople(data: TrainingProjectData): string {
  return formatCount(trainingNumber(data.c14))
}

function projectFunding(data: TrainingProjectData): string {
  return formatMoney(calculateTrainingFunding(data))
}

const saving = ref(false)
const submitting = ref(false)
const exporting = ref(false)

async function persistProjects(showMessage = true): Promise<boolean> {
  const current = sheet.value
  if (!current || readonly.value || saving.value) return false
  const payload = toTrainingProjectInputs(projects.value)
  const issues = validateTrainingProjects(payload, fields.value, 'draft')
  if (issues.length > 0) {
    const issue = issues[0]
    ElMessage.warning(issue.rowNo > 0 ? `第 ${issue.rowNo} 项：${issue.message}` : issue.message)
    return false
  }
  saving.value = true
  try {
    const result = await saveTrainingProjects(current.id, {
      version: current.version,
      projects: payload,
    })
    applyDetail(result)
    if (showMessage) ElMessage.success('培训项目草稿已保存')
    return true
  } catch {
    return false
  } finally {
    saving.value = false
  }
}

async function handleSubmit(): Promise<void> {
  const current = sheet.value
  if (!current || readonly.value || submitting.value) return
  if (dirty.value) {
    ElMessage.warning('请先保存草稿，再选择项目提交审核')
    return
  }
  const selected = submittableProjects.value.filter((project) => selectedProjectIds.value.includes(project.id!))
  if (selected.length === 0) {
    ElMessage.warning('请至少选择一个待提交项目')
    return
  }
  const issues = validateTrainingProjects(selected, fields.value, 'submit')
  if (issues.length > 0) {
    const issue = issues[0]
    ElMessage.warning(issue.rowNo > 0 ? `第 ${issue.rowNo} 项：${issue.message}` : issue.message)
    return
  }
  try {
    await ElMessageBox.confirm(
      `本次提交 ${selected.length} 个培训项目，进入${submitTargetLabel.value}；其他草稿仍可编辑。确认提交？`,
      '提交职业培训项目审核',
      { confirmButtonText: '确认提交', cancelButtonText: '再检查一下', type: 'warning' },
    )
  } catch {
    return
  }

  submitting.value = true
  try {
    const result = await submitTrainingSheet(current.id, {
      version: current.version,
      projectIds: selected.map((project) => project.id!),
    }, newIdempotencyKey())
    selectedProjectIds.value = []
    applyDetail(result)
    ElMessage.success(`已提交${submitTargetLabel.value}`)
  } catch {
    // 请求错误由统一拦截器提示，保留当前页面便于重试。
  } finally {
    submitting.value = false
  }
}

const reviewing = ref(false)
const reviewDialogVisible = ref(false)
const reviewResult = ref<ReviewResult>('APPROVED')
const reviewComment = ref('')
const reviewReason = ref('')
const reviewProject = ref<TrainingProjectInput | null>(null)
const reviewApproveHint = computed(() => reviewProject.value?.currentReviewNode === 'UNIT_REVIEW'
  ? '院审通过后将转校级负责人审核。'
  : '校审通过后该项目为最终通过状态。')

function openReview(project: TrainingProjectInput, result: ReviewResult): void {
  if (project.canReview !== true || !project.id) return
  reviewProject.value = project
  reviewResult.value = result
  reviewComment.value = ''
  reviewReason.value = ''
  reviewDialogVisible.value = true
}

async function handleReview(): Promise<void> {
  const current = sheet.value
  const project = reviewProject.value
  if (!current || !project?.id || project.canReview !== true || reviewing.value) return
  if (reviewResult.value === 'REJECTED' && !reviewComment.value.trim()) {
    ElMessage.warning('驳回时必须填写审核意见')
    return
  }
  reviewing.value = true
  try {
    const result = await reviewTrainingSheet(current.id, {
      projectId: project.id,
      version: current.version,
      result: reviewResult.value,
      comment: reviewComment.value.trim() || undefined,
      reason: reviewReason.value.trim() || undefined,
    }, newIdempotencyKey())
    applyDetail(result)
    reviewDialogVisible.value = false
    ElMessage.success(reviewResult.value === 'APPROVED' ? '审核已通过' : '已驳回，等待修改后重新提交')
  } catch {
    // 请求错误由统一拦截器提示，保留审核意见便于重试。
  } finally {
    reviewing.value = false
  }
}

async function handleExport(): Promise<void> {
  const current = sheet.value
  if (!current || exporting.value) return
  exporting.value = true
  try {
    const blob = await exportTrainingSheet(current.id)
    const url = URL.createObjectURL(blob)
    const filename = `${current.planName.replace(/[\\/:*?"<>|]/g, '_')}-职业培训信息表.xlsx`
    triggerFileDownload(url, filename)
    window.setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch {
    // 请求错误由统一拦截器提示。
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div class="training-page" v-loading="loading">
    <el-result
      v-if="loadFailed"
      icon="error"
      title="培训采集数据加载失败"
      sub-title="请检查网络连接后重试"
    >
      <template #extra>
        <el-button type="primary" @click="loadRoute">重新加载</el-button>
      </template>
    </el-result>

    <template v-else-if="sheet">
      <el-button class="back-button" link type="primary" @click="backToList">← {{ backLabel }}</el-button>
      <div class="page-header detail-header">
        <div class="detail-heading">
          <div class="detail-title-line">
            <h1 class="page-title">{{ sheet.planName }}</h1>
            <StatusTag kind="TrainingSheetStatus" :value="sheet.status" :review-node="sheet.currentReviewNode" />
          </div>
          <p class="page-subtitle">
            {{ sheet.unitName }} · 共 {{ projects.length }} 个{{ previewApprovedOnly ? '审核通过' : '培训' }}项目 · 第 {{ sheet.revisionNo }} 轮提交 · 最近保存 {{ formatDateTime(sheet.updatedAt) }}
          </p>
        </div>
        <div class="page-actions">
          <el-button
            :loading="exporting"
            :disabled="approvedCount === 0"
            :title="approvedCount === 0 ? '暂无审核通过项目可导出' : `导出 ${approvedCount} 个审核通过项目`"
            @click="handleExport"
          >
            导出已通过项目（{{ approvedCount }}）
          </el-button>
          <template v-if="editable">
            <el-button :disabled="!dirty || submitting" :loading="saving" @click="persistProjects()">
              保存草稿
            </el-button>
            <el-button
              type="primary"
              :disabled="dirty || selectedProjectIds.length === 0 || saving"
              :loading="submitting"
              @click="handleSubmit"
            >
              提交所选项目（{{ selectedProjectIds.length }}）
            </el-button>
          </template>
        </div>
      </div>

      <el-alert
        v-if="previewApprovedOnly"
        type="info"
        :closable="false"
        title="当前为审核通过项目预览，仅显示已通过数据，可查看项目或导出 Excel。"
        class="page-alert"
      />
      <el-alert
        v-else-if="sheet.status === 'APPROVED'"
        type="success"
        :closable="false"
        title="采集表已审核通过，全部项目只读；可查看项目或导出 Excel。"
        class="page-alert"
      />
      <el-alert
        v-else-if="sheet.status === 'PENDING_REVIEW'"
        type="warning"
        :closable="false"
        :title="reviewable ? '待审项目只读，请在对应行审核；其他草稿仍可编辑。' : '待审项目只读，其他草稿仍可编辑；可在对应行查看当前节点。'"
        class="page-alert"
      />
      <el-alert
        v-else-if="sheet.status === 'REJECTED'"
        type="error"
        :closable="false"
        title="部分项目被驳回，请在对应行查看审核意见并修改重提；其他项目状态不受影响。"
        class="page-alert"
      />
      <el-alert
        v-else
        type="info"
        :closable="false"
        title="逐行填写项目并保存草稿，再勾选一行或多行提交；干事先院审，负责人直送校审，导出仅含已通过项目。"
        class="page-alert"
      />

      <el-card shadow="never" class="project-card">
        <div class="project-toolbar">
          <div>
            <h2>{{ previewApprovedOnly ? '审核通过项目' : '培训项目' }}</h2>
            <span v-if="dirty" class="unsaved-tip">有尚未保存的修改</span>
          </div>
          <el-button v-if="!readonly" type="primary" plain @click="openCreate">新增培训项目</el-button>
        </div>

        <ResponsiveRecordView v-if="projects.length > 0">
          <template #table>
            <el-table :data="projects" row-key="rowNo" @selection-change="handleSelectionChange">
              <el-table-column v-if="!previewApprovedOnly" type="selection" width="44" :selectable="isSubmittable" />
              <el-table-column prop="rowNo" label="序号" width="64" />
              <el-table-column label="项目名称" min-width="210">
                <template #default="{ row }">
                  <div>{{ projectName(row.data) }}</div>
                  <div v-if="row.status === 'REJECTED' && row.reviewComment" class="project-row-comment">
                    审核意见：{{ row.reviewComment }}
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="培训日期" min-width="205">
                <template #default="{ row }">{{ projectDates(row.data) }}</template>
              </el-table-column>
              <el-table-column label="总人数（人）" width="110" align="right">
                <template #default="{ row }">{{ projectPeople(row.data) }}</template>
              </el-table-column>
              <el-table-column label="到账经费（万元）" width="145" align="right">
                <template #default="{ row }">{{ projectFunding(row.data) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="150">
                <template #default="{ row }">
                  <StatusTag kind="TrainingSheetStatus" :value="row.status ?? 'DRAFT'" :review-node="row.currentReviewNode" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="248" fixed="right">
                <template #default="{ row, $index }">
                  <el-button v-if="row.canReview" link type="success" @click="openReview(row, 'APPROVED')">通过</el-button>
                  <el-button v-if="row.canReview" link type="danger" @click="openReview(row, 'REJECTED')">驳回</el-button>
                  <el-button link type="primary" @click="openProject($index)">
                    {{ row.canEdit === false ? '查看' : '编辑' }}
                  </el-button>
                  <el-button v-if="row.canEdit !== false" link type="danger" @click="removeProject($index)">移除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>
          <template #cards>
            <div class="project-list">
              <article v-for="(project, index) in projects" :key="project.id ?? project.rowNo" class="project-item">
                <div class="project-item__head">
                  <div>
                    <span class="project-item__number">项目 {{ project.rowNo }}</span>
                    <h3>{{ projectName(project.data) }}</h3>
                  </div>
                  <StatusTag kind="TrainingSheetStatus" :value="project.status ?? 'DRAFT'" :review-node="project.currentReviewNode" />
                </div>
                <div class="project-item__date">{{ projectDates(project.data) }}</div>
                <dl class="project-item__metrics">
                  <div>
                    <dt>总人数（人）</dt>
                    <dd>{{ projectPeople(project.data) }}</dd>
                  </div>
                  <div>
                    <dt>到账经费（万元）</dt>
                    <dd>{{ projectFunding(project.data) }}</dd>
                  </div>
                </dl>
                <div v-if="project.status === 'REJECTED' && project.reviewComment" class="project-review-comment">
                  审核意见：{{ project.reviewComment }}
                </div>
                <div class="project-item__actions">
                  <el-checkbox
                    v-if="isSubmittable(project)"
                    :model-value="selectedProjectIds.includes(project.id!)"
                    @change="toggleProjectSelection(project, $event)"
                  >
                    选择提交
                  </el-checkbox>
                  <el-button v-if="project.canReview" size="small" type="success" @click="openReview(project, 'APPROVED')">通过</el-button>
                  <el-button v-if="project.canReview" size="small" type="danger" plain @click="openReview(project, 'REJECTED')">驳回</el-button>
                  <el-button size="small" type="primary" plain @click="openProject(index)">
                    {{ project.canEdit === false ? '查看项目' : '编辑项目' }}
                  </el-button>
                  <el-button v-if="project.canEdit !== false" size="small" type="danger" plain @click="removeProject(index)">
                    移除
                  </el-button>
                </div>
              </article>
            </div>
          </template>
        </ResponsiveRecordView>
        <el-empty v-else :description="previewApprovedOnly ? '暂无审核通过项目' : '尚未填写培训项目'">
          <el-button v-if="!readonly" type="primary" @click="openCreate">新增第一个项目</el-button>
        </el-empty>
      </el-card>

      <el-card v-if="reviewHistory.length > 0" shadow="never" class="review-history">
        <h2>审核记录</h2>
        <el-timeline>
          <el-timeline-item
            v-for="review in reviewHistory"
            :key="review.id"
            :timestamp="formatDateTime(review.createdAt)"
            placement="top"
          >
            <strong>
              项目 {{ review.rowNo }} · 第 {{ review.revisionNo }} 轮 · {{ review.reviewNode === 'UNIT_REVIEW' ? '学院负责人审核' : '校级负责人审核' }} ·
              {{ review.result === 'APPROVED' ? '通过' : '驳回' }}
            </strong>
            <div class="review-meta">{{ review.reviewerName ?? '审核人' }}<span v-if="review.isProxy">（管理员代审）</span></div>
            <div v-if="review.comment" class="review-comment">审核意见：{{ review.comment }}</div>
            <div v-if="review.proxyReason" class="review-comment">代审原因：{{ review.proxyReason }}</div>
          </el-timeline-item>
        </el-timeline>
      </el-card>
    </template>

    <TrainingProjectEditor
      v-model="editorVisible"
      :fields="fields"
      :project="editorProject"
      :readonly="editorReadonly"
      :is-new="editorIsNew"
      @save="handleEditorSave"
    />

    <el-dialog
      v-model="reviewDialogVisible"
      :title="reviewResult === 'APPROVED' ? '确认审核通过' : '驳回培训项目'"
      width="min(520px, 92vw)"
    >
      <el-alert
        :type="reviewResult === 'APPROVED' ? 'warning' : 'error'"
        :closable="false"
        :title="`项目 ${reviewProject?.rowNo ?? ''}：${reviewResult === 'APPROVED' ? reviewApproveHint : '驳回后填报人可修改项目并重新提交，必须填写审核意见。'}`"
      />
      <el-form label-position="top" class="review-form" @submit.prevent>
        <el-form-item v-if="userStore.roles.includes('ADMIN')" label="代审原因">
          <el-input v-model="reviewReason" maxlength="500" placeholder="管理员代审时可填写原因" />
        </el-form-item>
        <el-form-item label="审核意见" :required="reviewResult === 'REJECTED'">
          <el-input
            v-model="reviewComment"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            :placeholder="reviewResult === 'REJECTED' ? '请说明需要修改的内容（必填）' : '可填写审核意见'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewDialogVisible = false">取消</el-button>
        <el-button :type="reviewResult === 'APPROVED' ? 'primary' : 'danger'" :loading="reviewing" @click="handleReview">
          {{ reviewResult === 'APPROVED' ? '确认通过' : '确认驳回' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.training-page {
  min-width: 0;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
}

.page-title {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 24px;
  line-height: 32px;
}

.page-subtitle {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 22px;
}

.project-item__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.back-button {
  margin-bottom: 12px;
  padding-left: 0;
}

.detail-heading {
  min-width: 0;
}

.detail-title-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.page-actions {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.page-actions :deep(.el-button) {
  margin-left: 0;
}

.page-alert {
  margin-bottom: 16px;
}

.project-card {
  min-width: 0;
}

.review-history {
  margin-top: 16px;
}

.review-history h2 {
  margin: 0 0 16px;
  font-size: 17px;
}

.review-meta,
.review-comment {
  margin-top: 6px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 22px;
  overflow-wrap: anywhere;
}

.review-form {
  margin-top: 16px;
}

.project-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.project-toolbar h2 {
  display: inline;
  margin: 0;
  font-size: 17px;
}

.unsaved-tip {
  margin-left: 12px;
  color: var(--el-color-warning);
  font-size: 12px;
}

.project-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.project-item {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.project-item__number {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.project-item h3 {
  margin: 4px 0 0;
  font-size: 16px;
  line-height: 24px;
  overflow-wrap: anywhere;
}

.project-item__date {
  margin-top: 12px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.project-item__metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 16px 0 0;
}

.project-item__metrics div {
  min-width: 0;
  padding: 10px 12px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.project-item__metrics dt {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.project-item__metrics dd {
  margin: 4px 0 0;
  color: var(--el-text-color-primary);
  font-size: 18px;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.project-item__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}

.project-row-comment,
.project-review-comment {
  margin-top: 6px;
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 20px;
  overflow-wrap: anywhere;
}

@media (max-width: 768px) {
  .page-header {
    flex-direction: column;
    gap: 14px;
  }

  .page-actions {
    width: 100%;
    justify-content: flex-start;
  }
}

@media (max-width: 480px) {
  .page-title {
    font-size: 20px;
    line-height: 28px;
  }

  .page-actions :deep(.el-button) {
    flex: 1 1 auto;
    min-height: 40px;
  }

  .project-toolbar {
    align-items: flex-start;
  }

  .project-toolbar :deep(.el-button) {
    padding-right: 10px;
    padding-left: 10px;
  }

  .unsaved-tip {
    display: block;
    margin: 4px 0 0;
  }

  .project-item {
    padding: 14px;
  }

  .project-item__actions :deep(.el-button) {
    min-height: 40px;
  }
}
</style>
