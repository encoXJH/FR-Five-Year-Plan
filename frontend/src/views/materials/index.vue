<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { FolderOpened } from '@element-plus/icons-vue'
import { bindMaterials, listMaterialIndicators, listMaterials } from '@/api/material'
import type { MaterialIndicatorOption, MaterialItem } from '@/api/material'
import { listOrgUnits } from '@/api/orgUnit'
import type { OrgUnit } from '@/api/orgUnit'
import { downloadFile } from '@/api/file'
import MaterialUploader from '@/views/submission/components/MaterialUploader.vue'
import FilePreviewDrawer from '@/components/FilePreviewDrawer.vue'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'
import { FILE_CATEGORY_COLORS, formatFileSize } from '@/utils/file'

const CATEGORY_LABELS: Record<string, string> = {
  DOCUMENT: '文档',
  PDF: 'PDF',
  IMAGE: '图片',
  VIDEO: '视频',
  ARCHIVE: '压缩包',
}

const userStore = useUserStore()
const loading = ref(false)
const binding = ref(false)
const indicators = ref<MaterialIndicatorOption[]>([])
const units = ref<OrgUnit[]>([])
const rows = ref<MaterialItem[]>([])
const total = ref(0)
const filters = reactive({ indicatorId: '', unitId: '', category: '', keyword: '' })
const page = ref(1)
const pageSize = ref(10)

const indicatorGroups = computed(() => {
  const groups = new Map<string, MaterialIndicatorOption[]>()
  for (const item of indicators.value) {
    const key = item.directionName?.trim() || '未归入建设方向'
    const list = groups.get(key)
    if (list) list.push(item)
    else groups.set(key, [item])
  }
  return [...groups.entries()].map(([name, items]) => ({ name, items }))
})
const selectedIndicatorLabel = computed(() => {
  const item = indicators.value.find((entry) => entry.id === filters.indicatorId)
  return item ? `${item.code} ${item.name}` : ''
})

const uploadVisible = ref(false)
const uploadIndicatorId = ref('')
const uploadFileIds = ref<string[]>([])
const uploadKey = ref(0)

const previewVisible = ref(false)
const previewFile = ref<MaterialItem | null>(null)

async function loadList(): Promise<void> {
  loading.value = true
  try {
    const result = await listMaterials({
      indicatorId: filters.indicatorId || undefined,
      unitId: filters.unitId || undefined,
      category: filters.category || undefined,
      keyword: filters.keyword.trim() || undefined,
      page: page.value,
      pageSize: pageSize.value,
    })
    rows.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void loadList()
}

function resetFilters(): void {
  filters.indicatorId = ''
  filters.unitId = ''
  filters.category = ''
  filters.keyword = ''
  search()
}

function openUpload(): void {
  uploadIndicatorId.value = ''
  uploadFileIds.value = []
  uploadKey.value += 1
  uploadVisible.value = true
}

async function confirmBind(): Promise<void> {
  if (!uploadIndicatorId.value) {
    ElMessage.warning('请选择要关联的关键产出指标')
    return
  }
  if (uploadFileIds.value.length === 0) {
    ElMessage.warning('请等待材料上传完成后再确认')
    return
  }
  binding.value = true
  try {
    await bindMaterials(uploadIndicatorId.value, uploadFileIds.value)
    ElMessage.success(`已上传并关联 ${uploadFileIds.value.length} 份材料`)
    uploadVisible.value = false
    await loadList()
  } finally {
    binding.value = false
  }
}

function openPreview(row: MaterialItem): void {
  previewFile.value = row
  previewVisible.value = true
}

async function download(row: MaterialItem): Promise<void> {
  await downloadFile(row.id, row.fileName, row.size)
}

onMounted(async () => {
  const requests: Promise<unknown>[] = [listMaterialIndicators().then((data) => { indicators.value = data })]
  if (!userStore.isUnitOwnerOnly) {
    requests.push(listOrgUnits().then((data) => { units.value = data }))
  }
  await Promise.all([...requests, loadList()])
})
</script>

<template>
  <div class="materials-page">
    <div class="page-header">
      <div>
        <h1 class="page-title"><el-icon><FolderOpened /></el-icon> 材料库</h1>
        <p class="page-subtitle">跨学院材料统一管理，按关键产出指标精准检索并在线预览</p>
      </div>
    </div>

    <div class="app-action-row upload-actions">
      <el-button v-permission="'material:upload'" type="primary" @click="openUpload">上传材料</el-button>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="关键产出指标">
          <span class="indicator-select-wrap" :title="selectedIndicatorLabel">
            <el-select
              v-model="filters.indicatorId"
              clearable
              filterable
              popper-class="material-indicator-popper"
              placeholder="全部指标（可按编码或名称搜索）"
              style="width: 100%"
              @change="search"
            >
              <el-option-group v-for="group in indicatorGroups" :key="group.name" :label="group.name">
                <el-option v-for="item in group.items" :key="item.id" :label="`${item.code} ${item.name}`" :value="item.id">
                  <span class="opt-code">{{ item.code }}</span><span class="opt-name">{{ item.name }}</span>
                </el-option>
              </el-option-group>
            </el-select>
          </span>
        </el-form-item>
        <el-form-item v-if="!userStore.isUnitOwnerOnly" label="所属学院">
          <el-select v-model="filters.unitId" clearable filterable placeholder="全部学院" style="width: 190px" @change="search">
            <el-option v-for="item in units" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="材料类型">
          <el-select v-model="filters.category" clearable placeholder="全部类型" style="width: 130px" @change="search">
            <el-option v-for="(label, value) in CATEGORY_LABELS" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="filters.keyword" clearable placeholder="文件名 / 指标编码或名称" style="width: 220px" @keyup.enter="search" @clear="search" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="list-card">
      <el-table v-loading="loading" :data="rows" border size="small">
        <el-table-column label="材料名称" min-width="210">
          <template #default="{ row }">
            <div class="file-name-cell">
              <span class="file-dot" :style="{ backgroundColor: FILE_CATEGORY_COLORS[row.category as keyof typeof FILE_CATEGORY_COLORS] }" />
              <span :title="row.fileName">{{ row.fileName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="关联关键产出指标" min-width="230">
          <template #default="{ row }">
            <span class="indicator-code">{{ row.indicator.code }}</span> {{ row.indicator.name }}
          </template>
        </el-table-column>
        <el-table-column label="所属学院" min-width="140">
          <template #default="{ row }">{{ row.unit?.name ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ CATEGORY_LABELS[row.category] ?? row.category }}</template>
        </el-table-column>
        <el-table-column label="大小" width="90" align="right">
          <template #default="{ row }">{{ formatFileSize(row.size) }}</template>
        </el-table-column>
        <el-table-column label="上传人" width="100">
          <template #default="{ row }">{{ row.uploadedBy?.name ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.uploadedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openPreview(row)">预览</el-button>
            <el-button link type="primary" @click="download(row)">下载</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty :image-size="80" description="暂无符合条件的材料" /></template>
      </el-table>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        class="list-pagination"
        background
        layout="total, sizes, prev, pager, next"
        :total="total"
        :page-sizes="[10, 20, 50]"
        @current-change="loadList"
        @size-change="search"
      />
    </el-card>

    <el-dialog v-model="uploadVisible" title="上传并关联材料" width="min(680px, calc(100vw - 32px))" align-center :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="关联关键产出指标" required>
          <el-select v-model="uploadIndicatorId" filterable popper-class="material-indicator-popper" placeholder="请选择指标（可按编码或名称搜索）" style="width: 100%">
            <el-option-group v-for="group in indicatorGroups" :key="group.name" :label="group.name">
              <el-option v-for="item in group.items" :key="item.id" :label="`${item.code} ${item.name}`" :value="item.id">
                <span class="opt-code">{{ item.code }}</span><span class="opt-name">{{ item.name }}</span>
              </el-option>
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="工作材料" required>
          <MaterialUploader :key="uploadKey" v-model="uploadFileIds" biz-type="INDICATOR_ATTACHMENT" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="binding" @click="confirmBind">完成上传并关联</el-button>
      </template>
    </el-dialog>

    <FilePreviewDrawer v-model="previewVisible" :file="previewFile" />
  </div>
</template>

<style scoped>
.page-header { margin-bottom: 16px; }
.page-title { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 20px; font-weight: 600; }
.page-title .el-icon { color: var(--el-color-primary); }
.page-subtitle { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.filter-card { margin-bottom: 16px; }
.filter-card :deep(.el-card__body) { padding-bottom: 0; }
.indicator-select-wrap { display: inline-block; width: 320px; max-width: 100%; vertical-align: middle; }
:global(.material-indicator-popper) { max-width: 560px; }
:global(.material-indicator-popper .el-select-dropdown__item) { display: flex; align-items: center; gap: 6px; }
:global(.material-indicator-popper .opt-code) { flex: none; color: var(--el-color-primary); font-weight: 600; font-variant-numeric: tabular-nums; }
:global(.material-indicator-popper .opt-name) { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-name-cell { display: flex; align-items: center; gap: 8px; min-width: 0; }
.file-name-cell > span:last-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-dot { flex-shrink: 0; width: 8px; height: 8px; border-radius: 50%; }
.indicator-code { color: var(--el-color-primary); font-weight: 600; font-variant-numeric: tabular-nums; }
.list-pagination { justify-content: flex-end; margin-top: 16px; }
.materials-page :deep(.material-uploader) { width: 100%; }

@media (max-width: 768px) {
  .upload-actions .el-button { width: 100%; min-height: 44px; margin: 0; }
  .indicator-select-wrap { width: 100%; }
  .filter-card :deep(.el-form-item) { display: flex; margin-right: 0; }
  .filter-card :deep(.el-form-item__content) { flex: 1; }
  .filter-card :deep(.el-select), .filter-card :deep(.el-input) { width: 100% !important; }
  .list-pagination { justify-content: center; }
}
</style>
