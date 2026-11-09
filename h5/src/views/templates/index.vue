<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { showConfirmDialog, showToast } from 'vant'
import StatusTag from '@/components/StatusTag.vue'
import PageState from '@/components/PageState.vue'
import {
  copyTemplate,
  disableTemplate,
  enableTemplate,
  getTemplate,
  listTemplates,
  updateTemplate,
} from '@/api/template'
import { listPlans, savePlanAsTemplate } from '@/api/plan'
import { formatDateTime } from '@/utils/format'
import type { PlanCard, PlanTemplate, PlanTemplateDetail } from '@/types/plan'

const loading = ref(false)
const templates = ref<PlanTemplate[]>([])
const filters = reactive({ status: '' as '' | 'ENABLED' | 'DISABLED', keyword: '' })

const visibleTemplates = computed(() =>
  templates.value.filter((item) => {
    if (filters.status === 'ENABLED' && !item.isEnabled) return false
    if (filters.status === 'DISABLED' && item.isEnabled) return false
    if (filters.keyword && !item.name.includes(filters.keyword.trim())) return false
    return true
  }),
)

async function loadTemplates(): Promise<void> {
  loading.value = true
  try {
    templates.value = await listTemplates()
  } finally {
    loading.value = false
  }
}

// 结构
const structureVisible = ref(false)
const structureLoading = ref(false)
const currentTemplate = ref<PlanTemplateDetail | null>(null)
const templateIndicators = computed(() =>
  (currentTemplate.value?.content?.indicators ?? []).filter((node) => node.nodeType === 'A'),
)
async function openStructure(row: PlanTemplate): Promise<void> {
  currentTemplate.value = null
  structureVisible.value = true
  structureLoading.value = true
  try {
    currentTemplate.value = await getTemplate(row.id)
  } finally {
    structureLoading.value = false
  }
}

// 操作
const actionVisible = ref(false)
const actionTarget = ref<PlanTemplate | null>(null)
const actionItems = computed(() => {
  const row = actionTarget.value
  if (!row) return []
  return [
    { name: '查看结构', key: 'structure' },
    { name: '重命名', key: 'rename' },
    { name: '复制模板', key: 'copy' },
    { name: row.isEnabled ? '停用模板' : '启用模板', key: 'toggle', color: row.isEnabled ? '#E45B5B' : undefined },
  ]
})
function openActions(row: PlanTemplate): void {
  actionTarget.value = row
  actionVisible.value = true
}
async function handleAction(action: { key: string }): Promise<void> {
  const row = actionTarget.value
  actionVisible.value = false
  if (!row) return
  if (action.key === 'structure') await openStructure(row)
  else if (action.key === 'rename') openRename(row)
  else if (action.key === 'copy') await handleCopy(row)
  else if (action.key === 'toggle') await handleToggle(row)
}

// 重命名
const renameVisible = ref(false)
const renameValue = ref('')
const renameTarget = ref<PlanTemplate | null>(null)
const renameSaving = ref(false)
function openRename(row: PlanTemplate): void {
  renameTarget.value = row
  renameValue.value = row.name
  renameVisible.value = true
}
async function submitRename(): Promise<void> {
  if (!renameTarget.value) return
  if (!renameValue.value.trim()) {
    showToast('请填写模板名称')
    return
  }
  renameSaving.value = true
  try {
    await updateTemplate(renameTarget.value.id, {
      name: renameValue.value.trim(),
      version: renameTarget.value.version,
    })
    showToast('模板已更新')
    renameVisible.value = false
    await loadTemplates()
  } finally {
    renameSaving.value = false
  }
}

async function handleCopy(row: PlanTemplate): Promise<void> {
  try {
    await showConfirmDialog({
      title: '复制模板',
      message: `确认复制模板「${row.name}」？`,
      confirmButtonText: '确认复制',
    })
  } catch {
    return
  }
  await copyTemplate(row.id)
  showToast('模板已复制')
  await loadTemplates()
}

async function handleToggle(row: PlanTemplate): Promise<void> {
  if (!row.isEnabled) {
    await enableTemplate(row.id)
    showToast('模板已启用')
    await loadTemplates()
    return
  }
  try {
    await showConfirmDialog({
      title: '停用模板',
      message: `停用后新建计划时将不能选择「${row.name}」。确认停用？`,
      confirmButtonText: '确认停用',
    })
  } catch {
    return
  }
  await disableTemplate(row.id)
  showToast('模板已停用')
  await loadTemplates()
}

// 另存为
const saveAsVisible = ref(false)
const saveAsSaving = ref(false)
const planOptions = ref<PlanCard[]>([])
const saveAsForm = reactive({ planId: '', name: '' })
const planPickerVisible = ref(false)
const planLabel = computed(
  () => planOptions.value.find((item) => item.id === saveAsForm.planId)?.name ?? '',
)

async function openSaveAs(): Promise<void> {
  saveAsForm.planId = ''
  saveAsForm.name = ''
  saveAsVisible.value = true
  try {
    planOptions.value = await listPlans()
  } catch {
    // 拦截器已提示
  }
}
async function submitSaveAs(): Promise<void> {
  if (!saveAsForm.planId) {
    showToast('请选择来源计划')
    return
  }
  if (!saveAsForm.name.trim()) {
    showToast('请填写模板名称')
    return
  }
  saveAsSaving.value = true
  try {
    await savePlanAsTemplate(saveAsForm.planId, { name: saveAsForm.name.trim() })
    showToast('已另存为模板')
    saveAsVisible.value = false
    await loadTemplates()
  } finally {
    saveAsSaving.value = false
  }
}

onMounted(loadTemplates)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1>模板管理</h1>
        <p class="muted small">维护计划模板，用于快速新建计划</p>
      </div>
      <van-button size="small" type="primary" @click="openSaveAs">从计划另存</van-button>
    </div>

    <van-search v-model="filters.keyword" placeholder="搜索模板名称" shape="round" />
    <div class="filter-scroll">
      <button type="button" class="filter-chip" :class="{ 'is-active': filters.status === '' }" @click="filters.status = ''">全部</button>
      <button type="button" class="filter-chip" :class="{ 'is-active': filters.status === 'ENABLED' }" @click="filters.status = 'ENABLED'">启用</button>
      <button type="button" class="filter-chip" :class="{ 'is-active': filters.status === 'DISABLED' }" @click="filters.status = 'DISABLED'">停用</button>
    </div>

    <PageState :loading="loading" :empty="visibleTemplates.length === 0" empty-text="暂无模板，可从计划另存为模板">
      <div class="template-list">
        <article v-for="row in visibleTemplates" :key="row.id" class="template-card">
          <div class="row--between">
            <h2>{{ row.name }}</h2>
            <StatusTag kind="TemplateStatus" :value="row.isEnabled ? 'ENABLED' : 'DISABLED'" />
          </div>
          <div class="template-meta">
            <span>来源：{{ row.sourcePlanName ?? '—' }}</span>
            <span>A 指标 {{ row.indicatorCountA ?? '—' }}</span>
            <span>周期 {{ row.durationYears === null ? '—' : `${row.durationYears} 年` }}</span>
            <span>引用 {{ row.referenceCount }} 次</span>
          </div>
          <div class="small muted">更新于 {{ formatDateTime(row.updatedAt) }}</div>
          <div class="template-actions">
            <button type="button" class="text-btn" @click="openStructure(row)">查看结构</button>
            <button type="button" class="text-btn" @click="openRename(row)">重命名</button>
            <button type="button" class="text-btn" @click="handleCopy(row)">复制</button>
            <button type="button" class="text-btn" @click="openActions(row)">更多</button>
          </div>
        </article>
      </div>
    </PageState>

    <van-action-sheet
      v-model:show="actionVisible"
      :actions="actionItems"
      cancel-text="取消"
      close-on-click-action
      @select="handleAction"
    />

    <van-popup v-model:show="structureVisible" position="bottom" round :style="{ height: '82vh' }">
      <div class="sheet">
        <div class="sheet-head">
          <strong>模板结构</strong>
          <button type="button" class="close-btn" @click="structureVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="sheet-body">
          <div v-if="structureLoading" class="skeleton-block">加载中…</div>
          <template v-else-if="currentTemplate">
            <div class="kv-list">
              <div class="kv"><span class="kv__label">模板名称</span><span class="kv__value">{{ currentTemplate.name }}</span></div>
              <div class="kv"><span class="kv__label">状态</span><span class="kv__value"><StatusTag kind="TemplateStatus" :value="currentTemplate.isEnabled ? 'ENABLED' : 'DISABLED'" /></span></div>
            </div>
            <div v-if="templateIndicators.length === 0" class="empty-state">模板未包含结构数据</div>
            <div v-else class="structure-list">
              <div v-for="(node, index) in templateIndicators" :key="index" class="structure-item">
                <div class="row">
                  <span class="chip">A</span>
                  <strong>{{ node.code ? `${node.code} ` : '' }}{{ node.name }}</strong>
                </div>
                <div v-if="node.statCaliber || node.unit" class="small muted">
                  {{ node.statCaliber ?? '' }}<template v-if="node.unit"> · {{ node.unit }}</template>
                </div>
              </div>
            </div>
          </template>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="renameVisible" position="bottom" round safe-area-inset-bottom>
      <div class="sheet simple">
        <h3>编辑模板</h3>
        <van-field v-model="renameValue" maxlength="100" show-word-limit placeholder="模板名称" />
        <div class="sheet-actions">
          <van-button block @click="renameVisible = false">取消</van-button>
          <van-button block type="primary" :loading="renameSaving" @click="submitRename">保存</van-button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="saveAsVisible" position="bottom" round safe-area-inset-bottom>
      <div class="sheet simple">
        <h3>从计划另存为模板</h3>
        <van-field readonly is-link :model-value="planLabel" placeholder="请选择来源计划" @click="planPickerVisible = true" />
        <van-field v-model="saveAsForm.name" maxlength="100" show-word-limit placeholder="模板名称" />
        <div class="sheet-actions">
          <van-button block @click="saveAsVisible = false">取消</van-button>
          <van-button block type="primary" :loading="saveAsSaving" @click="submitSaveAs">保存模板</van-button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="planPickerVisible" position="bottom" round>
      <van-picker
        :columns="planOptions.map((item) => ({ text: item.name, value: item.id }))"
        @confirm="(value: { selectedOptions: Array<{ text: string; value: string }> }) => {
          saveAsForm.planId = value.selectedOptions[0]?.value ?? ''
          saveAsForm.name = value.selectedOptions[0]?.text ? `${value.selectedOptions[0].text} 模板` : ''
          planPickerVisible = false
        }"
        @cancel="planPickerVisible = false"
      />
    </van-popup>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin: 10px 0 12px;
}
.page-head h1 {
  margin: 0;
  font-size: 22px;
}
.page-head p {
  margin: 2px 0 0;
}
.template-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 6px;
}
.template-card {
  padding: 12px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.template-card h2 {
  margin: 0;
  font-size: 15px;
  line-height: 21px;
}
.template-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: 8px 0 4px;
  color: var(--text-secondary);
  font-size: 12px;
}
.template-actions {
  display: flex;
  gap: 16px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--line);
}
.text-btn {
  min-height: 32px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--brand);
  font-size: 13px;
}
.sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 14px 16px calc(14px + var(--safe-bottom));
}
.sheet.simple {
  height: auto;
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
.sheet-body {
  flex: 1;
  overflow-y: auto;
}
.sheet h3 {
  margin: 0 0 12px;
  font-size: 16px;
}
.sheet :deep(.van-field) {
  margin-bottom: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  padding: 10px 12px;
}
.sheet-actions {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}
.structure-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}
.structure-item {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fbfcfe;
}
</style>
