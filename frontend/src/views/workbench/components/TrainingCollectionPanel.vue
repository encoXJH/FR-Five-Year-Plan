<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import StatusTag from '@/components/StatusTag.vue'
import { listTrainingSheets } from '@/api/training'
import { useUserStore } from '@/stores/user'
import { useAutoRefresh } from '@/utils/autoRefresh'
import { formatDateTime } from '@/utils/format'
import type { TrainingSheetSummary } from '@/types/training'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const loadFailed = ref(false)
const sheets = ref<TrainingSheetSummary[]>([])

async function load(silent = false): Promise<void> {
  if (!userStore.can('training:fill')) return
  if (!silent) loading.value = true
  loadFailed.value = false
  try {
    sheets.value = await listTrainingSheets()
  } catch {
    if (!silent) loadFailed.value = true
  } finally {
    if (!silent) loading.value = false
  }
}

function actionLabel(sheet: TrainingSheetSummary): string {
  if (sheet.canReview) return '去审核'
  if (sheet.canEdit) return sheet.status === 'REJECTED' ? '修改重提' : '填写采集表'
  return '查看采集表'
}

function openSheet(sheet: TrainingSheetSummary): void {
  void router.push({ path: `/training/${sheet.id}`, query: { from: 'training' } })
}

onMounted(() => void load())
useAutoRefresh(() => load(true))
</script>

<template>
  <el-card v-if="userStore.can('training:fill')" shadow="never" class="training-collection" v-loading="loading">
    <div class="training-collection__header">
      <div>
        <h2>职业培训采集</h2>
        <p>进入独立采集页逐行填写，可选择一行或多行提交；各行独立显示审核状态，导出仅含已通过项目。</p>
      </div>
      <el-button v-if="loadFailed" link type="primary" @click="load()">重新加载</el-button>
    </div>
    <el-alert v-if="loadFailed" type="error" :closable="false" title="培训采集加载失败，请稍后重试" />
    <div v-else-if="sheets.length > 0" class="training-collection__grid">
      <article v-for="sheet in sheets" :key="sheet.id" class="training-sheet">
        <div class="training-sheet__head">
          <h3>{{ sheet.planName }}</h3>
          <StatusTag kind="TrainingSheetStatus" :value="sheet.status" :review-node="sheet.currentReviewNode" />
        </div>
        <div class="training-sheet__meta">
          <span>{{ sheet.unitName }} · {{ sheet.projectCount }} 个项目</span>
          <span v-if="sheet.submittedByName">最近提交：{{ sheet.submittedByName }}</span>
          <span>更新于 {{ formatDateTime(sheet.updatedAt) }}</span>
        </div>
        <div class="training-sheet__actions">
          <el-button :type="sheet.canEdit || sheet.canReview ? 'primary' : 'default'" @click="openSheet(sheet)">
            {{ actionLabel(sheet) }}
          </el-button>
        </div>
      </article>
    </div>
    <el-empty v-else-if="!loading" description="暂无职业培训采集计划" :image-size="64" />
  </el-card>
</template>

<style scoped>
.training-collection {
  margin-top: 18px;
}

.training-collection__header,
.training-sheet__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.training-collection__header {
  margin-bottom: 16px;
}

.training-collection__header h2 {
  margin: 0;
  font-size: 17px;
}

.training-collection__header p {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 22px;
}

.training-collection__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 320px), 1fr));
  gap: 14px;
}

.training-sheet {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.training-sheet h3 {
  margin: 0;
  font-size: 16px;
  line-height: 26px;
  overflow-wrap: anywhere;
}

.training-sheet__meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 14px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.training-sheet__actions {
  margin-top: 16px;
  text-align: right;
}

@media (max-width: 480px) {
  .training-sheet__head {
    flex-direction: column;
  }

  .training-sheet__actions :deep(.el-button) {
    width: 100%;
    min-height: 42px;
  }
}
</style>
