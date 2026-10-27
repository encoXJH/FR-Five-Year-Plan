<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
import {
  createMilestone,
  deleteMilestone,
  listMilestones,
  updateMilestone,
} from '@/api/milestone'
import { listIndicators } from '@/api/indicator'
import { useUserStore } from '@/stores/user'
import { enumOptions } from '@/utils/enumLabels'
import { formatDate } from '@/utils/format'
import type { CreateMilestoneRequest, Milestone } from '@/types/milestone'
import type { MilestoneStatus } from '@/types/enums'

const props = defineProps<{
  planId: string
  /** 计划年度（关联年度目标下拉） */
  years: number[]
  /** 计划已归档：只读 */
  readonly?: boolean
}>()

const userStore = useUserStore()

/** 增删改权限（API 15.7：indicator:edit / submission:submit） */
const canManage = computed(
  () => !props.readonly && userStore.can('indicator:edit', 'submission:submit'),
)

// ---------- 指标选择 ----------
const indicatorOptions = ref<{ id: string; label: string }[]>([])
const selectedIndicatorId = ref('')

async function loadIndicatorOptions(): Promise<void> {
  try {
    const tree = await listIndicators(props.planId)
    const options: { id: string; label: string }[] = []
    for (const node of tree) {
      options.push({ id: node.id, label: `${node.code} ${node.name}（A 类）` })
      for (const child of node.children ?? []) {
        options.push({ id: child.id, label: `${child.code} ${child.name}` })
      }
    }
    indicatorOptions.value = options
    if (!selectedIndicatorId.value && options.length > 0) {
      selectedIndicatorId.value = options[0].id
      loadMilestones()
    }
  } catch {
    // 拦截器已提示
  }
}

// ---------- 里程碑列表 ----------
const loading = ref(false)
const milestones = ref<Milestone[]>([])

/** 按计划完成时间升序展示（时间轴） */
const sortedMilestones = computed(() =>
  [...milestones.value].sort((a, b) => (a.dueDate ?? '').localeCompare(b.dueDate ?? '')),
)

async function loadMilestones(): Promise<void> {
  if (!selectedIndicatorId.value) {
    milestones.value = []
    return
  }
  loading.value = true
  try {
    milestones.value = await listMilestones({ indicatorId: selectedIndicatorId.value })
  } catch {
    milestones.value = []
  } finally {
    loading.value = false
  }
}

function timelineType(status: MilestoneStatus): 'primary' | 'success' | 'danger' | 'info' {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'OVERDUE':
      return 'danger'
    case 'NOT_STARTED':
      return 'info'
    default:
      return 'primary'
  }
}

// ---------- 新增 / 编辑 ----------
const dialogVisible = ref(false)
const dialogSaving = ref(false)
const editingId = ref<string | null>(null)

const milestoneForm = reactive({
  name: '',
  year: null as number | null,
  dueDate: '',
  deliverable: '',
  status: 'NOT_STARTED' as MilestoneStatus,
  version: 0,
})

const statusOptions = computed(() => enumOptions('MilestoneStatus', userStore.enums))

function openCreate(): void {
  editingId.value = null
  milestoneForm.name = ''
  milestoneForm.year = null
  milestoneForm.dueDate = ''
  milestoneForm.deliverable = ''
  milestoneForm.status = 'NOT_STARTED'
  milestoneForm.version = 0
  dialogVisible.value = true
}

function openEdit(item: Milestone): void {
  editingId.value = item.id
  milestoneForm.name = item.name
  milestoneForm.year = item.year ?? null
  milestoneForm.dueDate = item.dueDate ? item.dueDate.slice(0, 10) : ''
  milestoneForm.deliverable = item.deliverable ?? ''
  milestoneForm.status = item.status
  milestoneForm.version = item.version
  dialogVisible.value = true
}

async function saveMilestone(): Promise<void> {
  if (!milestoneForm.name.trim()) {
    ElMessage.warning('请填写里程碑名称')
    return
  }
  if (!milestoneForm.dueDate) {
    ElMessage.warning('请选择计划完成时间')
    return
  }
  dialogSaving.value = true
  try {
    if (editingId.value) {
      await updateMilestone(editingId.value, {
        name: milestoneForm.name.trim(),
        year: milestoneForm.year,
        dueDate: milestoneForm.dueDate,
        deliverable: milestoneForm.deliverable.trim() || null,
        status: milestoneForm.status,
        version: milestoneForm.version,
      })
      ElMessage.success('里程碑已更新')
    } else {
      const body: CreateMilestoneRequest = {
        indicatorId: selectedIndicatorId.value,
        name: milestoneForm.name.trim(),
        year: milestoneForm.year,
        dueDate: milestoneForm.dueDate,
        deliverable: milestoneForm.deliverable.trim() || null,
        status: milestoneForm.status,
      }
      await createMilestone(body)
      ElMessage.success('里程碑已创建')
    }
    dialogVisible.value = false
    await loadMilestones()
  } catch {
    // 拦截器已提示
  } finally {
    dialogSaving.value = false
  }
}

async function removeMilestone(item: Milestone): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除里程碑「${item.name}」？`, '删除里程碑', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await deleteMilestone(item.id, item.version)
  ElMessage.success('已删除')
  await loadMilestones()
}

onMounted(loadIndicatorOptions)
</script>

<template>
  <div>
    <div class="panel-toolbar">
      <el-select
        v-model="selectedIndicatorId"
        filterable
        placeholder="选择指标（A / A+）查看里程碑"
        style="width: 320px"
        @change="loadMilestones"
      >
        <el-option
          v-for="option in indicatorOptions"
          :key="option.id"
          :label="option.label"
          :value="option.id"
        />
      </el-select>
      <el-button
        v-if="canManage"
        type="primary"
        :disabled="!selectedIndicatorId"
        @click="openCreate"
      >
        新增里程碑
      </el-button>
    </div>

    <div v-loading="loading">
      <el-timeline v-if="sortedMilestones.length > 0" class="milestone-timeline">
        <el-timeline-item
          v-for="item in sortedMilestones"
          :key="item.id"
          :type="timelineType(item.status)"
          :timestamp="formatDate(item.dueDate)"
          placement="top"
        >
          <div class="milestone-head">
            <span class="milestone-name">{{ item.name }}</span>
            <StatusTag kind="MilestoneStatus" :value="item.status" />
            <span v-if="item.year" class="milestone-year">{{ item.year }} 年目标</span>
          </div>
          <div v-if="item.deliverable" class="milestone-deliverable">
            交付物：{{ item.deliverable }}
          </div>
          <div v-if="canManage" class="milestone-actions">
            <el-button link type="primary" size="small" @click="openEdit(item)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeMilestone(item)">删除</el-button>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty
        v-else-if="selectedIndicatorId && !loading"
        :image-size="80"
        description="该指标暂无里程碑"
      />
      <el-empty v-else-if="!loading" :image-size="80" description="请先选择指标" />
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑里程碑' : '新增里程碑'"
      width="480px"
    >
      <el-form label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="milestoneForm.name" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="计划完成" required>
          <el-date-picker
            v-model="milestoneForm.dueDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="关联年度">
          <el-select v-model="milestoneForm.year" clearable placeholder="关联年度目标（选填）" style="width: 100%">
            <el-option v-for="year in years" :key="year" :label="`${year} 年`" :value="year" />
          </el-select>
        </el-form-item>
        <el-form-item label="交付物要求">
          <el-input
            v-model="milestoneForm.deliverable"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="milestoneForm.status" style="width: 100%">
            <el-option
              v-for="option in statusOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dialogSaving" @click="saveMilestone">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
  flex-wrap: wrap;
}

.milestone-timeline {
  padding-left: var(--space-1);
}

.milestone-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.milestone-name {
  font-weight: 600;
  font-size: 13px;
}

.milestone-year {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.milestone-deliverable {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.milestone-actions {
  margin-top: var(--space-1);
}
</style>
