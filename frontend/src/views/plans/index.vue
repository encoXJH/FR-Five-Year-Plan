<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { MoreFilled, Plus } from '@element-plus/icons-vue'
import ReasonDialog from '@/components/ReasonDialog.vue'
import StatusTag from '@/components/StatusTag.vue'
import { archivePlan, createPlan, getPlan, listPlans, savePlanAsTemplate } from '@/api/plan'
import { listTemplates } from '@/api/template'
import { getTodos, listNotifications, markNotificationRead } from '@/api/todo'
import { useUserStore } from '@/stores/user'
import { enumLabel } from '@/utils/enumLabels'
import {
  formatDate,
  formatDateTime,
  formatPercent,
  formatRelativeTime,
  timeProgressOfYear,
  EMPTY_DISPLAY,
} from '@/utils/format'
import { resolveTodoLink, todoGroupLabel } from '@/utils/todo'
import type { CreatePlanRequest, PlanCard, PlanTemplate } from '@/types/plan'
import type { PlanSource, PlanStatus, Role } from '@/types/enums'
import type { TodoItem } from '@/types/todo'
import type { NotificationItem } from '@/types/notification'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const plans = ref<PlanCard[]>([])
const statusFilter = ref<PlanStatus | ''>('')
const keyword = ref('')

const canManage = computed(() => userStore.can('plan:manage'))

// ---------- 欢迎条（UI 规范 P02） ----------
const ROLE_PRIORITY: Role[] = ['ADMIN', 'LEADER', 'SCHOOL_OWNER', 'UNIT_OWNER']

const now = new Date()
const greeting = computed(() => {
  const hour = now.getHours()
  if (hour < 12) return '上午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})
const primaryRoleLabel = computed(() => {
  const primary = ROLE_PRIORITY.find((role) => userStore.roles.includes(role))
  return primary ? enumLabel('Role', primary, userStore.enums) : ''
})
const currentYear = now.getFullYear()
const yearTimeProgress = computed(() => formatPercent(timeProgressOfYear(currentYear, now)))
const todayText = computed(() => formatDate(now))

const visiblePlans = computed(() =>
  plans.value.filter((plan) => {
    if (statusFilter.value && plan.status !== statusFilter.value) return false
    if (keyword.value && !plan.name.includes(keyword.value.trim())) return false
    return true
  }),
)

async function loadPlans(): Promise<void> {
  loading.value = true
  try {
    plans.value = await listPlans()
  } finally {
    loading.value = false
  }
}

function goPlan(plan: PlanCard): void {
  router.push(`/plans/${plan.id}`)
}

function percentValue(rate: number | null): number {
  return rate === null ? 0 : Math.min(rate * 100, 100)
}

// ---------- 卡片 ⋯ 菜单：进入 / 复制 / 另存为模板 / 归档 ----------
const copying = ref(false)

async function handleCopy(plan: PlanCard): Promise<void> {
  await ElMessageBox.confirm(
    `将复制「${plan.name}」的指标体系结构，生成一份草稿计划（不含执行数据）。确认复制？`,
    '复制计划',
    { confirmButtonText: '确认复制', cancelButtonText: '取消' },
  )
  copying.value = true
  try {
    const created = await createPlan({
      name: `${plan.name}（副本）`,
      startYear: plan.startYear,
      endYear: plan.endYear,
      baselineYear: plan.baselineYear,
      source: 'COPY',
      sourcePlanId: plan.id,
      copyAllocations: false,
    })
    ElMessage.success('已复制为新计划')
    router.push(`/plans/${created.id}`)
  } catch {
    // 拦截器已提示
  } finally {
    copying.value = false
  }
}

const templateDialogVisible = ref(false)
const templateName = ref('')
const templateTargetId = ref<string | null>(null)
const savingTemplate = ref(false)

function openSaveAsTemplate(plan: PlanCard): void {
  templateTargetId.value = plan.id
  templateName.value = `${plan.name} 模板`
  templateDialogVisible.value = true
}

async function submitSaveAsTemplate(): Promise<void> {
  if (!templateTargetId.value) return
  if (!templateName.value.trim()) {
    ElMessage.warning('请填写模板名称')
    return
  }
  savingTemplate.value = true
  try {
    await savePlanAsTemplate(templateTargetId.value, { name: templateName.value.trim() })
    ElMessage.success('已另存为模板')
    templateDialogVisible.value = false
  } catch {
    // 拦截器已提示
  } finally {
    savingTemplate.value = false
  }
}

const archiveDialogVisible = ref(false)
const archiveTarget = ref<PlanCard | null>(null)
const archiving = ref(false)

function openArchiveDialog(plan: PlanCard): void {
  archiveTarget.value = plan
  archiveDialogVisible.value = true
}

async function submitArchive(reason: string): Promise<void> {
  if (!archiveTarget.value) return
  archiving.value = true
  try {
    const detail = await getPlan(archiveTarget.value.id)
    await archivePlan(archiveTarget.value.id, detail.version, reason)
    ElMessage.success(`「${archiveTarget.value.name}」已归档`)
    archiveDialogVisible.value = false
    await loadPlans()
  } catch {
    // 拦截器已提示
  } finally {
    archiving.value = false
  }
}

function handleCardCommand(plan: PlanCard, command: string): void {
  if (command === 'enter') goPlan(plan)
  else if (command === 'copy') void handleCopy(plan)
  else if (command === 'template') openSaveAsTemplate(plan)
  else if (command === 'archive') openArchiveDialog(plan)
}

// ---------- 新建计划 ----------
const createVisible = ref(false)
const createStep = ref(0)
const createLoading = ref(false)
const formRef = ref<FormInstance>()
const templates = ref<PlanTemplate[]>([])

const form = reactive<CreatePlanRequest>({
  name: '',
  startYear: 2026,
  endYear: 2030,
  baselineYear: 2025,
  settleDeadline: '01-15',
  source: 'BLANK',
  templateId: undefined,
  sourcePlanId: undefined,
  copyAllocations: false,
})

const rules: FormRules = {
  name: [{ required: true, message: '请填写计划名称', trigger: 'blur' }],
  templateId: [{ required: true, message: '请选择来源模板', trigger: 'change' }],
  sourcePlanId: [{ required: true, message: '请选择来源计划', trigger: 'change' }],
}

const sourceOptions: { value: PlanSource; title: string; desc: string }[] = [
  { value: 'BLANK', title: '空白新建', desc: '从零开始搭建指标体系' },
  { value: 'TEMPLATE', title: '从模板新建', desc: '复用模板中的方向与指标结构' },
  { value: 'COPY', title: '复制已有计划', desc: '复制某个计划的完整结构' },
]

function openCreate(): void {
  createStep.value = 0
  form.name = ''
  form.startYear = 2026
  form.endYear = 2030
  form.baselineYear = 2025
  form.settleDeadline = '01-15'
  form.source = 'BLANK'
  form.templateId = undefined
  form.sourcePlanId = undefined
  form.copyAllocations = false
  createVisible.value = true
}

function pickSource(source: PlanSource): void {
  form.source = source
  if (source === 'TEMPLATE' && templates.value.length === 0) {
    listTemplates()
      .then((list) => {
        templates.value = list.filter((item) => item.isEnabled)
      })
      .catch(() => undefined)
  }
}

function nextStep(): void {
  createStep.value = 1
}

async function submitCreate(): Promise<void> {
  if (!formRef.value) return
  await formRef.value.validate()
  if (form.endYear < form.startYear) {
    ElMessage.error('截止年份不能早于起始年份')
    return
  }
  createLoading.value = true
  try {
    const payload: CreatePlanRequest = {
      name: form.name,
      startYear: form.startYear,
      endYear: form.endYear,
      baselineYear: form.baselineYear,
      settleDeadline: form.settleDeadline || null,
      source: form.source,
    }
    if (form.source === 'TEMPLATE') payload.templateId = form.templateId
    if (form.source === 'COPY') {
      payload.sourcePlanId = form.sourcePlanId
      payload.copyAllocations = form.copyAllocations
    }
    const plan = await createPlan(payload)
    ElMessage.success('计划创建成功')
    createVisible.value = false
    router.push(`/plans/${plan.id}`)
  } catch {
    // 拦截器已提示
  } finally {
    createLoading.value = false
  }
}

onMounted(() => {
  loadPlans()
  loadTodos()
  loadNotifications()
})

// ---------- 我的待办 / 最近动态（各前 5 条） ----------
const topTodos = ref<TodoItem[]>([])

async function loadTodos(): Promise<void> {
  try {
    const result = await getTodos()
    topTodos.value = result.items.slice(0, 5)
  } catch {
    topTodos.value = []
  }
}

function goTodo(todo: TodoItem): void {
  const link = resolveTodoLink(todo)
  if (link) router.push(link)
  else ElMessage.info('该待办暂无可跳转页面')
}

const topNotifications = ref<NotificationItem[]>([])

async function loadNotifications(): Promise<void> {
  try {
    const result = await listNotifications({ page: 1, pageSize: 5 })
    topNotifications.value = result.items
  } catch {
    topNotifications.value = []
  }
}

async function openNotification(item: NotificationItem): Promise<void> {
  if (!item.readAt) {
    try {
      const updated = await markNotificationRead(item.id)
      item.readAt = updated.readAt
    } catch {
      // 拦截器已提示
    }
  }
  if (item.link) router.push(item.link)
}
</script>

<template>
  <div class="welcome-bar">
    {{ greeting }}，{{ userStore.userInfo?.name ?? ''
    }}<template v-if="primaryRoleLabel">（{{ primaryRoleLabel }}）</template>
    · 今天是 {{ todayText }} · {{ currentYear }} 年度时间进度 {{ yearTimeProgress }}
  </div>

  <div class="page-header">
    <div>
      <h1 class="page-title">我的计划</h1>
      <p class="page-subtitle">共 {{ plans.length }} 个计划</p>
    </div>
    <div class="header-filters">
      <el-select v-model="statusFilter" placeholder="状态：全部" clearable style="width: 140px">
        <el-option label="草稿" value="DRAFT" />
        <el-option label="执行中" value="ACTIVE" />
        <el-option label="已归档" value="ARCHIVED" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="搜索计划名称"
        clearable
        style="width: 220px"
      />
    </div>
  </div>

  <div v-loading="loading" class="plan-grid">
    <button v-if="canManage" type="button" class="plan-card plan-card--new" @click="openCreate">
      <el-icon :size="32" class="new-icon"><Plus /></el-icon>
      <span class="new-text">新建计划</span>
      <span class="new-desc">空白 / 模板 / 复制</span>
    </button>

    <el-card
      v-for="plan in visiblePlans"
      :key="plan.id"
      shadow="hover"
      class="plan-card"
      @click="goPlan(plan)"
    >
      <div class="card-top">
        <span class="plan-name" :title="plan.name">{{ plan.name }}</span>
        <div class="card-top-right">
          <StatusTag kind="PlanStatus" :value="plan.status" />
          <el-dropdown
            trigger="click"
            @command="(command: string) => handleCardCommand(plan, command)"
          >
            <el-button text class="card-more" @click.stop>
              <el-icon><MoreFilled /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="enter">进入</el-dropdown-item>
                <el-dropdown-item v-if="canManage" command="copy">复制</el-dropdown-item>
                <el-dropdown-item v-if="canManage" command="template">另存为模板</el-dropdown-item>
                <el-dropdown-item v-if="canManage" command="archive" divided>
                  <span class="text-danger">归档</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
      <div class="plan-period">
        周期 {{ plan.startYear }}–{{ plan.endYear }} · 基线 {{ plan.baselineYear }}
      </div>
      <div class="card-body">
        <el-progress
          type="circle"
          :width="72"
          :stroke-width="8"
          :percentage="percentValue(plan.overallCompletionRate)"
          color="#2B5BC0"
        >
          <span class="rate-text">
            {{ plan.overallCompletionRate === null ? EMPTY_DISPLAY : formatPercent(plan.overallCompletionRate) }}
          </span>
        </el-progress>
        <div class="plan-stats">
          <div>整体完成率</div>
          <div>A 类 {{ plan.indicatorCountA }} · A+ {{ plan.indicatorCountAPlus }} · B {{ plan.taskCountB }}</div>
          <div>更新于 {{ formatDateTime(plan.updatedAt) }}</div>
        </div>
      </div>
    </el-card>

    <el-empty
      v-if="!loading && visiblePlans.length === 0"
      class="grid-empty"
      description="没有符合条件的计划，试试调整筛选条件"
    />
  </div>

  <div class="bottom-row">
    <el-card shadow="never" class="bottom-card">
      <template #header><span class="card-title">我的待办</span></template>
      <el-empty v-if="topTodos.length === 0" description="暂无待办" :image-size="60" />
      <div
        v-for="todo in topTodos"
        :key="`${todo.type}-${todo.bizId}`"
        class="mini-item"
        @click="goTodo(todo)"
      >
        <el-tag size="small" effect="light" class="mini-tag">{{ todoGroupLabel(todo.type) }}</el-tag>
        <span class="mini-title" :title="todo.detail ?? todo.bizCode ?? ''">
          {{ todo.detail ?? todo.bizCode ?? '—' }}
        </span>
        <span class="mini-time">{{ formatRelativeTime(todo.todoSince) }}</span>
      </div>
    </el-card>
    <el-card shadow="never" class="bottom-card">
      <template #header><span class="card-title">最近动态</span></template>
      <el-empty v-if="topNotifications.length === 0" description="暂无消息" :image-size="60" />
      <div
        v-for="item in topNotifications"
        :key="item.id"
        class="mini-item"
        @click="openNotification(item)"
      >
        <span class="unread-dot" :class="{ 'is-unread': !item.readAt }" />
        <el-tag size="small" effect="light" class="mini-tag">
          {{ enumLabel('NotificationType', item.type, userStore.enums) }}
        </el-tag>
        <span class="mini-title" :title="item.title">{{ item.title }}</span>
        <span class="mini-time">{{ formatRelativeTime(item.createdAt) }}</span>
      </div>
    </el-card>
  </div>

  <el-dialog v-model="createVisible" title="新建计划" width="560px" :close-on-click-modal="false">
    <el-steps :active="createStep" align-center class="create-steps">
      <el-step title="选择方式" />
      <el-step title="填写属性" />
    </el-steps>

    <div v-show="createStep === 0" class="source-options">
      <div
        v-for="option in sourceOptions"
        :key="option.value"
        class="source-card"
        :class="{ 'source-card--active': form.source === option.value }"
        @click="pickSource(option.value)"
      >
        <div class="source-title">{{ option.title }}</div>
        <div class="source-desc">{{ option.desc }}</div>
      </div>
    </div>

    <el-form
      v-show="createStep === 1"
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="120px"
    >
      <el-form-item label="计划名称" prop="name">
        <el-input v-model="form.name" maxlength="100" show-word-limit placeholder="如：2026–2030 五年发展规划" />
      </el-form-item>
      <el-form-item label="起止年份" required>
        <el-input-number v-model="form.startYear" :min="2000" :max="2100" :controls="false" />
        <span class="year-sep">至</span>
        <el-input-number v-model="form.endYear" :min="2000" :max="2100" :controls="false" />
      </el-form-item>
      <el-form-item label="基线年">
        <el-input-number v-model="form.baselineYear" :min="2000" :max="2100" :controls="false" />
      </el-form-item>
      <el-form-item label="核算截止日">
        <el-input v-model="form.settleDeadline" placeholder="MM-DD，如 01-15（次年）" style="width: 200px" />
      </el-form-item>
      <el-form-item v-if="form.source === 'TEMPLATE'" label="来源模板" prop="templateId">
        <el-select v-model="form.templateId" placeholder="请选择模板" style="width: 100%">
          <el-option v-for="tpl in templates" :key="tpl.id" :label="tpl.name" :value="tpl.id" />
        </el-select>
      </el-form-item>
      <template v-if="form.source === 'COPY'">
        <el-form-item label="来源计划" prop="sourcePlanId">
          <el-select v-model="form.sourcePlanId" placeholder="请选择计划" style="width: 100%">
            <el-option v-for="plan in plans" :key="plan.id" :label="plan.name" :value="plan.id" />
          </el-select>
        </el-form-item>
        <el-form-item label=" ">
          <el-checkbox v-model="form.copyAllocations">同时复制分解数据（B 任务）</el-checkbox>
        </el-form-item>
      </template>
    </el-form>

    <template #footer>
      <el-button v-if="createStep === 1" @click="createStep = 0">上一步</el-button>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button v-if="createStep === 0" type="primary" @click="nextStep">下一步</el-button>
      <el-button v-else type="primary" :loading="createLoading" @click="submitCreate">
        创建计划
      </el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="templateDialogVisible" title="另存为模板" width="480px">
    <el-form label-width="90px">
      <el-form-item label="模板名称" required>
        <el-input v-model="templateName" maxlength="100" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="templateDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="savingTemplate" @click="submitSaveAsTemplate">
        保存模板
      </el-button>
    </template>
  </el-dialog>

  <ReasonDialog
    v-model="archiveDialogVisible"
    title="归档计划"
    :target="archiveTarget ? `${archiveTarget.name}（${archiveTarget.startYear}–${archiveTarget.endYear}）` : ''"
    confirm-text="确认归档"
    :loading="archiving"
    @confirm="submitArchive"
  />
</template>

<style scoped>
.welcome-bar {
  margin-bottom: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background: var(--el-bg-color);
  border-radius: var(--radius-card);
  font-size: 14px;
  color: var(--el-text-color-regular);
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
}

.page-subtitle {
  margin: var(--space-1) 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.header-filters {
  display: flex;
  gap: var(--space-2);
}

.plan-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: var(--space-4);
  min-height: 240px;
}

.plan-card {
  height: 220px;
  border-radius: var(--radius-dialog);
  cursor: pointer;
}

.plan-card--new {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  background: var(--el-bg-color);
  border: 1px dashed var(--el-border-color);
  color: var(--el-color-primary);
  font-size: 14px;
}

.plan-card--new:hover {
  border-color: var(--el-color-primary);
}

.new-text {
  font-weight: 600;
}

.new-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.card-top-right {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex-shrink: 0;
}

.card-more {
  padding: 4px;
  color: var(--el-text-color-secondary);
}

.text-danger {
  color: var(--el-color-danger);
}

.plan-name {
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.plan-period {
  margin-top: var(--space-1);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.card-body {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  margin-top: var(--space-4);
}

.rate-text {
  font-size: 14px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.plan-stats {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.grid-empty {
  grid-column: 1 / -1;
}

.create-steps {
  margin-bottom: var(--space-5);
}

.source-options {
  display: flex;
  gap: var(--space-3);
}

.source-card {
  flex: 1;
  padding: var(--space-4);
  border: 1px solid var(--el-border-color);
  border-radius: var(--radius-card);
  cursor: pointer;
}

.source-card--active {
  border-color: var(--el-color-primary);
  background-color: var(--el-color-primary-light-9);
}

.source-title {
  font-weight: 600;
  margin-bottom: var(--space-1);
}

.source-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.year-sep {
  margin: 0 var(--space-2);
  color: var(--el-text-color-secondary);
}

.bottom-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-4);
  margin-top: var(--space-4);
}

.bottom-card {
  border-radius: var(--radius-card);
}

.card-title {
  font-size: 16px;
  font-weight: 600;
}

.mini-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 8px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}

.mini-item:last-child {
  border-bottom: none;
}

.mini-item:hover {
  background-color: var(--el-fill-color-light);
}

.mini-tag {
  flex-shrink: 0;
}

.mini-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mini-time {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.unread-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: transparent;
  flex-shrink: 0;
}

.unread-dot.is-unread {
  background-color: var(--el-color-danger);
}

@media (max-width: 768px) {
  .bottom-row {
    grid-template-columns: 1fr;
  }
}
</style>
