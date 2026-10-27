<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
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
import type { PlanCard, PlanTemplate, PlanTemplateDetail, TemplateStructureNode } from '@/types/plan'

const loading = ref(false)
const templates = ref<PlanTemplate[]>([])

const filters = reactive({
  status: '' as '' | 'ENABLED' | 'DISABLED',
  keyword: '',
})

/** 后端列表无查询参数，状态（isEnabled）与关键字本地过滤 */
const visibleTemplates = computed<PlanTemplate[]>(() =>
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

function resetFilters(): void {
  filters.status = ''
  filters.keyword = ''
}

function structureSummary(row: PlanTemplate): string {
  const parts: string[] = []
  if (row.indicatorCountA !== null) parts.push(`A ${row.indicatorCountA}`)
  if (row.indicatorCountAPlus !== null) parts.push(`A+ ${row.indicatorCountAPlus}`)
  return parts.length > 0 ? parts.join(' · ') : '—'
}

// ---------- 结构查看 ----------
const structureVisible = ref(false)
const structureLoading = ref(false)
const currentTemplate = ref<PlanTemplateDetail | null>(null)

const templateIndicators = computed(() => currentTemplate.value?.content?.indicators ?? [])
const structureJson = computed(() =>
  currentTemplate.value?.content ? JSON.stringify(currentTemplate.value.content, null, 2) : '',
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

function nodeIconType(node: TemplateStructureNode): string {
  if (node.nodeType === 'A') return 'A'
  if (node.nodeType === 'A_PLUS') return 'A+'
  return node.nodeType
}

// ---------- 重命名 ----------
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
    ElMessage.warning('请填写模板名称')
    return
  }
  renameSaving.value = true
  try {
    await updateTemplate(renameTarget.value.id, {
      name: renameValue.value.trim(),
      version: renameTarget.value.version,
    })
    ElMessage.success('模板已更新')
    renameVisible.value = false
    await loadTemplates()
  } catch {
    // 拦截器已提示
  } finally {
    renameSaving.value = false
  }
}

// ---------- 复制 / 停用 / 启用 ----------
async function handleCopy(row: PlanTemplate): Promise<void> {
  await ElMessageBox.confirm(`确认复制模板「${row.name}」？`, '复制模板', {
    confirmButtonText: '确认复制',
    cancelButtonText: '取消',
  })
  try {
    await copyTemplate(row.id)
    ElMessage.success('模板已复制')
    await loadTemplates()
  } catch {
    // 拦截器已提示
  }
}

async function handleToggle(row: PlanTemplate): Promise<void> {
  const isEnable = !row.isEnabled
  try {
    if (isEnable) {
      await enableTemplate(row.id)
      ElMessage.success('模板已启用')
    } else {
      await ElMessageBox.confirm(
        `停用后新建计划时将不能选择「${row.name}」。确认停用？`,
        '停用模板',
        { confirmButtonText: '确认停用', cancelButtonText: '取消', type: 'warning' },
      )
      await disableTemplate(row.id)
      ElMessage.success('模板已停用')
    }
    await loadTemplates()
  } catch (error) {
    if (error !== 'cancel') {
      // 拦截器已提示
    }
  }
}

// ---------- 从计划另存为模板 ----------
const saveAsVisible = ref(false)
const saveAsSaving = ref(false)
const planOptions = ref<PlanCard[]>([])
const saveAsForm = reactive({ planId: '', name: '' })

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
    ElMessage.warning('请选择来源计划')
    return
  }
  if (!saveAsForm.name.trim()) {
    ElMessage.warning('请填写模板名称')
    return
  }
  saveAsSaving.value = true
  try {
    await savePlanAsTemplate(saveAsForm.planId, { name: saveAsForm.name.trim() })
    ElMessage.success('已另存为模板')
    saveAsVisible.value = false
    await loadTemplates()
  } catch {
    // 拦截器已提示
  } finally {
    saveAsSaving.value = false
  }
}

onMounted(loadTemplates)
</script>

<template>
  <div class="page-header">
    <div>
      <h1 class="page-title">模板管理</h1>
      <p class="page-subtitle">维护计划模板，用于快速新建计划</p>
    </div>
    <el-button type="primary" @click="openSaveAs">从计划另存为模板</el-button>
  </div>

  <el-card shadow="never" class="filter-card">
    <el-form inline @submit.prevent>
      <el-form-item label="状态">
        <el-select v-model="filters.status" clearable placeholder="全部" style="width: 120px">
          <el-option label="启用" value="ENABLED" />
          <el-option label="停用" value="DISABLED" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键字">
        <el-input v-model="filters.keyword" clearable placeholder="模板名称" style="width: 200px" />
      </el-form-item>
      <el-form-item>
        <el-button @click="loadTemplates">刷新</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>
  </el-card>

  <el-card shadow="never">
    <el-table v-loading="loading" :data="visibleTemplates" border>
      <el-table-column prop="name" label="模板名称" min-width="200" show-overflow-tooltip />
      <el-table-column label="来源计划" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.sourcePlanName ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="结构" min-width="180">
        <template #default="{ row }">{{ structureSummary(row) }}</template>
      </el-table-column>
      <el-table-column label="周期" width="80" align="center">
        <template #default="{ row }">
          {{ row.durationYears === null ? '—' : `${row.durationYears} 年` }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <StatusTag kind="TemplateStatus" :value="row.isEnabled ? 'ENABLED' : 'DISABLED'" />
        </template>
      </el-table-column>
      <el-table-column prop="referenceCount" label="引用次数" width="90" align="right" />
      <el-table-column label="更新时间" width="150">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" @click="openStructure(row)">查看结构</el-button>
          <el-button link type="primary" @click="openRename(row)">编辑</el-button>
          <el-button link type="primary" @click="handleCopy(row)">复制</el-button>
          <el-button
            link
            :type="row.isEnabled ? 'danger' : 'success'"
            @click="handleToggle(row)"
          >
            {{ row.isEnabled ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :image-size="80" description="暂无模板，可从计划另存为模板" />
      </template>
    </el-table>
  </el-card>

  <el-drawer v-model="structureVisible" title="模板结构" size="560px">
    <div v-loading="structureLoading">
      <template v-if="currentTemplate">
        <el-descriptions :column="2" border class="structure-meta">
          <el-descriptions-item label="模板名称">{{ currentTemplate.name }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusTag kind="TemplateStatus" :value="currentTemplate.isEnabled ? 'ENABLED' : 'DISABLED'" />
          </el-descriptions-item>
        </el-descriptions>
        <el-tree
          v-if="templateIndicators.length > 0"
          :data="templateIndicators"
          :props="{ label: 'name', children: 'children' }"
          default-expand-all
          class="structure-tree"
        >
          <template #default="{ data }">
            <span class="tree-node">
              <el-tag size="small" effect="plain" round>{{ nodeIconType(data) }}</el-tag>
              <span>{{ data.code ? `${data.code} ` : '' }}{{ data.name }}</span>
              <span v-if="data.statCaliber" class="node-extra">
                {{ data.statCaliber }}<template v-if="data.unit"> · {{ data.unit }}</template>
              </span>
            </span>
          </template>
        </el-tree>
        <template v-else>
          <el-empty :image-size="80" description="模板未包含结构数据" />
        </template>
        <el-collapse v-if="structureJson" class="structure-json">
          <el-collapse-item title="原始 JSON" name="json">
            <pre class="json-view">{{ structureJson }}</pre>
          </el-collapse-item>
        </el-collapse>
      </template>
    </div>
  </el-drawer>

  <el-dialog v-model="renameVisible" title="编辑模板" width="480px">
    <el-form label-width="90px">
      <el-form-item label="模板名称" required>
        <el-input v-model="renameValue" maxlength="100" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="renameVisible = false">取消</el-button>
      <el-button type="primary" :loading="renameSaving" @click="submitRename">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="saveAsVisible" title="从计划另存为模板" width="480px">
    <el-form label-width="90px">
      <el-form-item label="来源计划" required>
        <el-select v-model="saveAsForm.planId" placeholder="请选择计划" style="width: 100%">
          <el-option v-for="plan in planOptions" :key="plan.id" :label="plan.name" :value="plan.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="模板名称" required>
        <el-input v-model="saveAsForm.name" maxlength="100" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="saveAsVisible = false">取消</el-button>
      <el-button type="primary" :loading="saveAsSaving" @click="submitSaveAs">保存模板</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
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

.filter-card {
  margin-bottom: var(--space-4);
  border-radius: var(--radius-card);
}

.structure-meta {
  margin-bottom: var(--space-4);
}

.tree-node {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.node-extra {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.structure-json {
  margin-top: var(--space-4);
}

.json-view {
  margin: 0;
  padding: var(--space-3);
  background: var(--el-fill-color-light);
  border-radius: var(--el-border-radius-base);
  font-family: var(--app-font-mono);
  font-size: 12px;
  line-height: 18px;
  overflow: auto;
  max-height: 320px;
}
</style>
