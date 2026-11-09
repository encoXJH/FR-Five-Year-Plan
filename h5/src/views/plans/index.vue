<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import ReasonDialog from '@/components/ReasonDialog.vue'
import StatusTag from '@/components/StatusTag.vue'
import PageState from '@/components/PageState.vue'
import { archivePlan, createPlan, deletePlan, getPlan, listPlans, savePlanAsTemplate } from '@/api/plan'
import { listTemplates } from '@/api/template'
import { getTodos, listNotifications, markNotificationRead } from '@/api/todo'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import { enumLabel } from '@/utils/enumLabels'
import { formatDateTime, formatPercent, formatRelativeTime, EMPTY_DISPLAY } from '@/utils/format'
import { resolveNotificationLink, resolveTodoLink, todoGroupLabel } from '@/utils/todo'
import { clearCurrentPlan, getCurrentPlan, setCurrentPlan } from '@/utils/currentPlan'
import type { CreatePlanRequest, PlanCard, PlanTemplate } from '@/types/plan'
import type { PlanSource, PlanStatus } from '@/types/enums'
import type { TodoItem } from '@/types/todo'
import type { NotificationItem } from '@/types/notification'

const router = useRouter()
const userStore = useUserStore()
const appStore = useAppStore()

const loading = ref(false)
const plans = ref<PlanCard[]>([])
const statusFilter = ref<PlanStatus | ''>('')
const keyword = ref('')
const currentPlanId = ref(getCurrentPlan()?.id ?? '')
const canManage = computed(() => userStore.can('plan:manage'))

const visiblePlans = computed(() =>
  plans.value.filter((plan) => {
    if (statusFilter.value && plan.status !== statusFilter.value) return false
    if (keyword.value && !plan.name.includes(keyword.value.trim())) return false
    return true
  }),
)

async function loadPlans(silent = false): Promise<void> {
  if (!silent) loading.value = true
  try {
    const loaded = await listPlans()
    plans.value = loaded
    const cached = getCurrentPlan()
    if (cached && !loaded.some((plan) => plan.id === cached.id)) {
      clearCurrentPlan()
      currentPlanId.value = ''
    }
  } finally {
    if (!silent) loading.value = false
  }
}

function enterPlan(plan: PlanCard): void {
  setCurrentPlan(plan)
  currentPlanId.value = plan.id
  appStore.setLastPlan(plan.id)
  router.push(`/plans/${plan.id}`)
}

function setAsCurrent(plan: PlanCard): void {
  setCurrentPlan(plan)
  currentPlanId.value = plan.id
  appStore.setLastPlan(plan.id)
  showToast(`已将「${plan.name}」设为当前项目`)
}

function ringStyle(rate: number | null): Record<string, string> {
  const percent = rate === null ? 0 : Math.max(0, Math.min(100, rate * 100))
  return {
    background: `conic-gradient(#2B5BC0 ${percent}%, #e9eef7 0)`,
  }
}

const actionVisible = ref(false)
const actionTarget = ref<PlanCard | null>(null)
const actionItems = computed<{ name: string; key: string; color?: string }[]>(() => {
  const plan = actionTarget.value
  if (!plan) return []
  const items: { name: string; key: string; color?: string }[] = [
    { name: '进入计划', key: 'enter' },
    { name: plan.id === currentPlanId.value ? '当前项目（已设）' : '设为当前项目', key: 'current' },
  ]
  if (canManage.value) {
    items.push({ name: '复制计划', key: 'copy' })
    items.push({ name: '另存为模板', key: 'template' })
    if (plan.status !== 'ARCHIVED') items.push({ name: '归档计划', key: 'archive', color: '#E45B5B' })
    if (plan.status === 'DRAFT' || plan.status === 'ARCHIVED') {
      items.push({ name: '删除计划', key: 'delete', color: '#E45B5B' })
    }
  }
  return items
})

function openActions(plan: PlanCard): void {
  actionTarget.value = plan
  actionVisible.value = true
}

async function handleAction(action: { key: string }): Promise<void> {
  const plan = actionTarget.value
  actionVisible.value = false
  if (!plan) return
  switch (action.key) {
    case 'enter':
      enterPlan(plan)
      break
    case 'current':
      setAsCurrent(plan)
      break
    case 'copy':
      await copyPlan(plan)
      break
    case 'template':
      openSaveTemplate(plan)
      break
    case 'archive':
      archiveTarget.value = plan
      archiveVisible.value = true
      break
    case 'delete':
      deleteTarget.value = plan
      deleteVisible.value = true
      break
  }
}

const copying = ref(false)
async function copyPlan(plan: PlanCard): Promise<void> {
  try {
    await showConfirmDialog({
      title: '复制计划',
      message: `将复制「${plan.name}」的指标体系结构，生成一份草稿计划（不含执行数据）。确认复制？`,
      confirmButtonText: '确认复制',
    })
  } catch {
    return
  }
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
    showToast('已复制为新计划')
    setCurrentPlan(created)
    currentPlanId.value = created.id
    await loadPlans()
    router.push(`/plans/${created.id}`)
  } catch {
    // 拦截器已提示
  } finally {
    copying.value = false
  }
}

const templateVisible = ref(false)
const templateName = ref('')
const templateTarget = ref<PlanCard | null>(null)
const savingTemplate = ref(false)

function openSaveTemplate(plan: PlanCard): void {
  templateTarget.value = plan
  templateName.value = `${plan.name} 模板`
  templateVisible.value = true
}

async function submitTemplate(): Promise<void> {
  if (!templateTarget.value) return
  if (!templateName.value.trim()) {
    showToast('请填写模板名称')
    return
  }
  savingTemplate.value = true
  try {
    await savePlanAsTemplate(templateTarget.value.id, { name: templateName.value.trim() })
    showToast('已另存为模板')
    templateVisible.value = false
  } catch {
    // 拦截器已提示
  } finally {
    savingTemplate.value = false
  }
}

const archiveVisible = ref(false)
const archiveTarget = ref<PlanCard | null>(null)
const archiving = ref(false)

async function submitArchive(reason: string): Promise<void> {
  if (!archiveTarget.value) return
  archiving.value = true
  try {
    const detail = await getPlan(archiveTarget.value.id)
    await archivePlan(archiveTarget.value.id, detail.version, reason)
    showToast(`「${archiveTarget.value.name}」已归档`)
    archiveVisible.value = false
    await loadPlans()
  } catch {
    // 拦截器已提示
  } finally {
    archiving.value = false
  }
}

const deleteVisible = ref(false)
const deleteTarget = ref<PlanCard | null>(null)
const deleting = ref(false)

async function submitDelete(reason: string): Promise<void> {
  if (!deleteTarget.value) return
  const target = deleteTarget.value
  deleting.value = true
  try {
    const detail = await getPlan(target.id)
    await deletePlan(target.id, detail.version, reason)
    showToast(`「${target.name}」已删除`)
    deleteVisible.value = false
    if (currentPlanId.value === target.id) {
      clearCurrentPlan()
      currentPlanId.value = ''
    }
    await loadPlans()
  } catch {
    // 拦截器已提示
  } finally {
    deleting.value = false
  }
}

// ---------- 新建计划 ----------
const createVisible = ref(false)
const createStep = ref(0)
const createLoading = ref(false)
const templates = ref<PlanTemplate[]>([])
const templatePickerVisible = ref(false)
const sourcePlanPickerVisible = ref(false)

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

const sourceOptions: { value: PlanSource; title: string; desc: string; icon: string }[] = [
  { value: 'BLANK', title: '空白新建', desc: '从零开始搭建指标体系', icon: 'add-o' },
  { value: 'TEMPLATE', title: '从模板新建', desc: '复用模板中的方向与指标结构', icon: 'description' },
  { value: 'COPY', title: '复制已有计划', desc: '复制某个计划的完整结构', icon: 'copy' },
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

async function pickSource(source: PlanSource): Promise<void> {
  form.source = source
  if (source === 'TEMPLATE' && templates.value.length === 0) {
    try {
      templates.value = (await listTemplates()).filter((item) => item.isEnabled)
    } catch {
      // 拦截器已提示
    }
  }
}

const templateNameOf = computed(
  () => templates.value.find((item) => item.id === form.templateId)?.name ?? '',
)
const sourcePlanNameOf = computed(
  () => plans.value.find((item) => item.id === form.sourcePlanId)?.name ?? '',
)

async function submitCreate(): Promise<void> {
  if (!form.name.trim()) {
    createStep.value = 1
    showToast('请填写计划名称')
    return
  }
  if (form.endYear < form.startYear) {
    showToast('截止年份不能早于起始年份')
    return
  }
  if (form.source === 'TEMPLATE' && !form.templateId) {
    showToast('请选择来源模板')
    return
  }
  if (form.source === 'COPY' && !form.sourcePlanId) {
    showToast('请选择来源计划')
    return
  }
  createLoading.value = true
  try {
    const payload: CreatePlanRequest = {
      name: form.name.trim(),
      startYear: Number(form.startYear),
      endYear: Number(form.endYear),
      baselineYear: Number(form.baselineYear),
      settleDeadline: form.settleDeadline || null,
      source: form.source,
    }
    if (form.source === 'TEMPLATE') payload.templateId = form.templateId
    if (form.source === 'COPY') {
      payload.sourcePlanId = form.sourcePlanId
      payload.copyAllocations = form.copyAllocations
    }
    const plan = await createPlan(payload)
    showToast('计划创建成功')
    createVisible.value = false
    setCurrentPlan(plan)
    currentPlanId.value = plan.id
    await loadPlans()
    router.push(form.source === 'TEMPLATE' ? `/plans/${plan.id}/indicators` : `/plans/${plan.id}`)
  } catch {
    // 拦截器已提示
  } finally {
    createLoading.value = false
  }
}

// ---------- 待办 / 动态 ----------
const topTodos = ref<TodoItem[]>([])
const topNotifications = ref<NotificationItem[]>([])

async function loadTodos(): Promise<void> {
  try {
    topTodos.value = (await getTodos()).items.slice(0, 4)
  } catch {
    // 保留上次数据
  }
}

async function loadNotifications(): Promise<void> {
  try {
    topNotifications.value = (await listNotifications({ page: 1, pageSize: 4 })).items
  } catch {
    // 保留上次数据
  }
}

function goTodo(todo: TodoItem): void {
  const link = resolveTodoLink(todo)
  if (link) router.push(link)
  else showToast('该待办暂无可跳转页面')
}

async function openNotification(item: NotificationItem): Promise<void> {
  const link = resolveNotificationLink(item)
  if (!link) {
    showToast('该通知暂无可跳转页面')
    return
  }
  if (!item.readAt) {
    try {
      const updated = await markNotificationRead(item.id)
      item.readAt = updated.readAt
      appStore.refreshUnread()
    } catch {
      // 拦截器已提示
    }
  }
  router.push(link)
}

onMounted(() => {
  loadPlans()
  loadTodos()
  loadNotifications()
})
</script>

<template>
  <div class="page page--tabbar">
    <div class="home-hero">
      <div>
        <p class="home-hello">你好，{{ userStore.userInfo?.name ?? '' }}</p>
        <h1 class="home-title">我的计划</h1>
        <p class="home-sub">共 {{ plans.length }} 个计划 · 当前项目 {{ currentPlanId ? '已选' : '未选' }}</p>
      </div>
      <button v-if="canManage" type="button" class="create-btn" @click="openCreate">
        <van-icon name="plus" size="18" />
        新建
      </button>
    </div>

    <div class="quick-row">
      <button type="button" class="quick-card" @click="router.push('/todos')">
        <van-icon name="records-o" size="20" />
        <span>待办</span>
        <strong>{{ topTodos.length }}</strong>
      </button>
      <button type="button" class="quick-card" @click="router.push('/workbench')">
        <van-icon name="todo-list-o" size="20" />
        <span>工作台</span>
      </button>
      <button
        v-if="userStore.can('stats:view')"
        type="button"
        class="quick-card"
        @click="router.push('/stats')"
      >
        <van-icon name="chart-trending-o" size="20" />
        <span>统计</span>
      </button>
      <button
        v-if="userStore.can('screen:view')"
        type="button"
        class="quick-card"
        @click="router.push({ name: 'Screen', params: currentPlanId ? { planId: currentPlanId } : {} })"
      >
        <van-icon name="desktop-o" size="20" />
        <span>大屏</span>
      </button>
    </div>

    <div class="filter-block">
      <van-search v-model="keyword" placeholder="搜索计划名称" shape="round" />
      <div class="filter-scroll">
        <button
          v-for="option in [
            { value: '', label: '全部' },
            { value: 'DRAFT', label: '草稿' },
            { value: 'ACTIVE', label: '执行中' },
            { value: 'ARCHIVED', label: '已归档' },
          ]"
          :key="option.value"
          type="button"
          class="filter-chip"
          :class="{ 'is-active': statusFilter === option.value }"
          @click="statusFilter = option.value as PlanStatus | ''"
        >{{ option.label }}</button>
      </div>
    </div>

    <PageState :loading="loading" :empty="visiblePlans.length === 0" empty-text="没有符合条件的计划">
      <div class="plan-list">
        <article v-for="plan in visiblePlans" :key="plan.id" class="plan-card" @click="enterPlan(plan)">
          <div class="plan-head">
            <div class="plan-name-wrap">
              <span v-if="plan.id === currentPlanId" class="chip">当前项目</span>
              <h2 class="plan-name">{{ plan.name }}</h2>
              <p class="plan-period">{{ plan.startYear }}–{{ plan.endYear }} · 基线 {{ plan.baselineYear }}</p>
            </div>
            <div class="plan-head-right">
              <StatusTag kind="PlanStatus" :value="plan.status" />
              <button type="button" class="more-btn" aria-label="更多操作" @click.stop="openActions(plan)">
                <van-icon name="ellipsis" size="18" />
              </button>
            </div>
          </div>

          <div class="plan-body">
            <div class="ring" :style="ringStyle(plan.overallCompletionRate)">
              <div class="ring__inner">
                <template v-if="plan.overallCompletionRate === 0">
                  <span class="ring__empty">暂无<br />完成数据</span>
                </template>
                <template v-else>
                  {{ plan.overallCompletionRate === null ? EMPTY_DISPLAY : formatPercent(plan.overallCompletionRate) }}
                </template>
              </div>
            </div>
            <div class="plan-stats">
              <div class="plan-stat-label">整体完成率</div>
              <div class="plan-stat-row">
                <span>指标 <strong>{{ plan.indicatorCountA }}</strong></span>
                <span>学院任务 <strong>{{ plan.taskCountB }}</strong></span>
              </div>
              <div class="plan-updated">更新于 {{ formatDateTime(plan.updatedAt) }}</div>
            </div>
          </div>

          <div class="plan-footer">
            <span class="text-brand">进入计划</span>
            <van-icon name="arrow" size="14" color="#2B5BC0" />
          </div>
        </article>
      </div>
    </PageState>

    <div v-if="topTodos.length > 0" class="card">
      <div class="card-title">
        <span>我的待办</span>
        <button type="button" class="card-title__extra" @click="router.push('/todos')">全部</button>
      </div>
      <button
        v-for="todo in topTodos"
        :key="`${todo.type}-${todo.bizId}`"
        type="button"
        class="mini-item"
        @click="goTodo(todo)"
      >
        <span class="chip chip--gray">{{ todoGroupLabel(todo.type) }}</span>
        <span class="mini-title ellipsis">{{ todo.detail ?? todo.bizCode ?? '—' }}</span>
        <span class="mini-time">{{ formatRelativeTime(todo.todoSince) }}</span>
      </button>
    </div>

    <div v-if="topNotifications.length > 0" class="card">
      <div class="card-title">
        <span>最近动态</span>
        <button type="button" class="card-title__extra" @click="router.push({ path: '/todos', query: { tab: 'notifications' } })">全部</button>
      </div>
      <button
        v-for="item in topNotifications"
        :key="item.id"
        type="button"
        class="mini-item"
        @click="openNotification(item)"
      >
        <span class="unread-dot" :class="{ 'is-unread': !item.readAt }" />
        <span class="chip chip--gray">{{ enumLabel('NotificationType', item.type, userStore.enums) }}</span>
        <span class="mini-title ellipsis">{{ item.title }}</span>
        <span class="mini-time">{{ formatRelativeTime(item.createdAt) }}</span>
      </button>
    </div>

    <van-action-sheet
      v-model:show="actionVisible"
      :actions="actionItems"
      cancel-text="取消"
      close-on-click-action
      @select="handleAction"
    />

    <van-popup v-model:show="createVisible" position="bottom" round :style="{ maxHeight: '88vh' }">
      <div class="create-sheet">
        <div class="sheet-head">
          <strong>新建计划</strong>
          <button type="button" class="close-btn" @click="createVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <van-steps :active="createStep" active-color="#2B5BC0" class="create-steps">
          <van-step>选择方式</van-step>
          <van-step>填写属性</van-step>
        </van-steps>

        <div v-if="createStep === 0" class="source-list">
          <button
            v-for="option in sourceOptions"
            :key="option.value"
            type="button"
            class="source-card"
            :class="{ 'is-active': form.source === option.value }"
            @click="pickSource(option.value)"
          >
            <van-icon :name="option.icon" size="22" />
            <div class="source-main">
              <strong>{{ option.title }}</strong>
              <span>{{ option.desc }}</span>
            </div>
            <van-icon v-if="form.source === option.value" name="success" color="#2B5BC0" />
          </button>
        </div>

        <div v-else class="create-form">
          <div class="field">
            <label>计划名称<span class="required">*</span></label>
            <van-field v-model="form.name" maxlength="100" show-word-limit placeholder="如：2026–2030 五年发展规划" />
          </div>
          <div class="year-row">
            <div class="field">
              <label>起始年</label>
              <van-field v-model.number="form.startYear" type="number" inputmode="numeric" />
            </div>
            <div class="field">
              <label>截止年</label>
              <van-field v-model.number="form.endYear" type="number" inputmode="numeric" />
            </div>
            <div class="field">
              <label>基线年</label>
              <van-field v-model.number="form.baselineYear" type="number" inputmode="numeric" />
            </div>
          </div>
          <div class="field">
            <label>年度核算截止日</label>
            <van-field
              :model-value="form.settleDeadline ?? ''"
              placeholder="MM-DD，如 01-15（次年）"
              @update:model-value="(value: string) => (form.settleDeadline = value)"
            />
          </div>
          <div v-if="form.source === 'TEMPLATE'" class="field">
            <label>来源模板<span class="required">*</span></label>
            <van-field
              readonly
              is-link
              :model-value="templateNameOf"
              placeholder="请选择模板"
              @click="templatePickerVisible = true"
            />
          </div>
          <template v-if="form.source === 'COPY'">
            <div class="field">
              <label>来源计划<span class="required">*</span></label>
              <van-field
                readonly
                is-link
                :model-value="sourcePlanNameOf"
                placeholder="请选择计划"
                @click="sourcePlanPickerVisible = true"
              />
            </div>
            <van-checkbox v-model="form.copyAllocations" shape="square">
              同时复制分解数据（学院任务）
            </van-checkbox>
          </template>
        </div>

        <div class="sheet-actions">
          <van-button v-if="createStep === 1" block @click="createStep = 0">上一步</van-button>
          <van-button v-if="createStep === 0" block type="primary" @click="createStep = 1">下一步</van-button>
          <van-button v-else block type="primary" :loading="createLoading" @click="submitCreate">
            创建计划
          </van-button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="templatePickerVisible" position="bottom" round>
      <van-picker
        :columns="templates.map((item) => ({ text: item.name, value: item.id }))"
        @confirm="(value: { selectedOptions: Array<{ text: string; value: string }> }) => {
          form.templateId = value.selectedOptions[0]?.value
          templatePickerVisible = false
        }"
        @cancel="templatePickerVisible = false"
      />
    </van-popup>

    <van-popup v-model:show="sourcePlanPickerVisible" position="bottom" round>
      <van-picker
        :columns="plans.map((item) => ({ text: item.name, value: item.id }))"
        @confirm="(value: { selectedOptions: Array<{ text: string; value: string }> }) => {
          form.sourcePlanId = value.selectedOptions[0]?.value
          sourcePlanPickerVisible = false
        }"
        @cancel="sourcePlanPickerVisible = false"
      />
    </van-popup>

    <van-popup v-model:show="templateVisible" position="bottom" round safe-area-inset-bottom>
      <div class="simple-sheet">
        <h3>另存为模板</h3>
        <van-field v-model="templateName" maxlength="100" show-word-limit placeholder="模板名称" />
        <div class="sheet-actions">
          <van-button block @click="templateVisible = false">取消</van-button>
          <van-button block type="primary" :loading="savingTemplate" @click="submitTemplate">保存模板</van-button>
        </div>
      </div>
    </van-popup>

    <ReasonDialog
      v-model="archiveVisible"
      :title="`归档计划${archiveTarget ? '：' + archiveTarget.name : ''}`"
      label="归档原因"
      confirm-text="确认归档"
      danger
      :loading="archiving"
      @confirm="submitArchive"
    />
    <ReasonDialog
      v-model="deleteVisible"
      :title="`删除计划${deleteTarget ? '：' + deleteTarget.name : ''}`"
      label="删除原因"
      placeholder="删除后不再显示，审计历史保留"
      confirm-text="确认删除"
      danger
      :loading="deleting"
      @confirm="submitDelete"
    />
  </div>
</template>

<style scoped>
.home-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin: -12px -12px 12px;
  padding: 18px 16px 22px;
  background: linear-gradient(135deg, #2b5bc0, #1f4699);
  color: #fff;
  border-radius: 0 0 18px 18px;
}
.home-hello {
  margin: 0;
  font-size: 12px;
  opacity: 0.8;
}
.home-title {
  margin: 4px 0 2px;
  font-size: 24px;
  line-height: 30px;
}
.home-sub {
  margin: 0;
  font-size: 12px;
  opacity: 0.8;
}
.create-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 36px;
  padding: 0 14px;
  border: 1px solid rgba(255, 255, 255, 0.4);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}
.quick-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin-bottom: 12px;
}
.quick-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-height: 62px;
  padding: 8px 4px;
  border: none;
  border-radius: 12px;
  background: #fff;
  color: var(--text-secondary);
  font-size: 11px;
  box-shadow: var(--shadow);
}
.quick-card strong {
  color: var(--brand);
  font-size: 13px;
}
.filter-block {
  margin-bottom: 10px;
}
.plan-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.plan-card {
  padding: 14px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.plan-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.plan-name-wrap {
  flex: 1;
  min-width: 0;
}
.plan-name {
  margin: 6px 0 4px;
  font-size: 16px;
  line-height: 22px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.plan-period {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
}
.plan-head-right {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.more-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  background: #f5f7fb;
  color: var(--text-secondary);
}
.plan-body {
  display: flex;
  align-items: center;
  gap: 16px;
  margin: 14px 0 10px;
}
.ring {
  position: relative;
  width: 76px;
  height: 76px;
  border-radius: 50%;
  flex-shrink: 0;
}
.ring__inner {
  position: absolute;
  inset: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff;
  color: var(--brand);
  font-size: 14px;
  font-weight: 700;
  text-align: center;
  line-height: 16px;
}
.ring__empty {
  color: var(--text-placeholder);
  font-size: 10px;
  font-weight: 500;
}
.plan-stats {
  flex: 1;
  min-width: 0;
}
.plan-stat-label {
  color: var(--text-secondary);
  font-size: 12px;
}
.plan-stat-row {
  display: flex;
  gap: 14px;
  margin: 6px 0 4px;
  font-size: 13px;
}
.plan-stat-row strong {
  color: var(--brand);
}
.plan-updated {
  color: var(--text-placeholder);
  font-size: 11px;
}
.plan-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
  font-size: 13px;
}
.mini-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 44px;
  padding: 6px 0;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  text-align: left;
}
.mini-item:last-child {
  border-bottom: none;
}
.mini-title {
  flex: 1;
  min-width: 0;
  color: var(--text);
  font-size: 13px;
}
.mini-time {
  flex-shrink: 0;
  color: var(--text-placeholder);
  font-size: 11px;
}
.unread-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: transparent;
  flex-shrink: 0;
}
.unread-dot.is-unread {
  background: var(--danger);
}
.create-sheet {
  padding: 16px 16px calc(16px + var(--safe-bottom));
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
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
.create-steps {
  margin-bottom: 12px;
  padding: 8px 0;
  background: transparent;
}
.source-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.source-card {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 66px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fbfcfe;
  color: var(--text);
  text-align: left;
}
.source-card.is-active {
  border-color: var(--brand);
  background: #f5f8ff;
}
.source-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}
.source-main strong {
  font-size: 14px;
}
.source-main span {
  color: var(--text-secondary);
  font-size: 12px;
}
.create-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.field label {
  display: block;
  margin-bottom: 4px;
  color: var(--text-secondary);
  font-size: 13px;
}
.required {
  color: var(--danger);
}
.field :deep(.van-field) {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  padding: 10px 12px;
}
.year-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.sheet-actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}
.simple-sheet {
  padding: 16px 16px calc(16px + var(--safe-bottom));
}
.simple-sheet h3 {
  margin: 0 0 12px;
  font-size: 16px;
}
.simple-sheet :deep(.van-field) {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  padding: 10px 12px;
}
</style>
