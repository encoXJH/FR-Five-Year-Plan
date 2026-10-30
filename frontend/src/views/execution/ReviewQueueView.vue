<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { errorMessage, executionApi } from '@/api'
import type { ID, Submission } from '@/types/api'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import PlanSelect from '@/components/PlanSelect.vue'
import StatusTag from '@/components/StatusTag.vue'
import VersionTag from '@/components/VersionTag.vue'
import ValueDisplay from '@/components/ValueDisplay.vue'
import RemoteState from '@/components/RemoteState.vue'
const router = useRouter()
const rows = ref<Submission[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const query = reactive({ page: 1, page_size: 20, keyword: '', plan_id: undefined as ID | undefined, target_year: undefined as number | undefined })
async function load() { loading.value = true; error.value = ''; try { const result = await executionApi.reviewQueue(query); rows.value = result.items; total.value = result.total } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
onMounted(load)
</script>
<template>
  <div class="page-container"><PageHeader title="校级人工审核" description="同屏核验统计口径、年度目标、学院填报、历史版本与佐证材料。系统不自动判断材料真实性或完成合规性。" />
    <div class="filter-bar"><el-input v-model="query.keyword" placeholder="学院／指标／任务代码" clearable style="width:250px" @keyup.enter="load"><template #prefix><el-icon><Search /></el-icon></template></el-input><div style="width:300px"><PlanSelect v-model="query.plan_id" placeholder="全部待审计划" /></div><el-select v-model="query.target_year" placeholder="填报年度" clearable style="width:130px"><el-option v-for="year in [2026,2027,2028,2029,2030]" :key="year" :value="year" :label="year" /></el-select><el-button type="primary" plain @click="load">查询</el-button></div>
    <RemoteState :loading="loading" :error="error" :empty="!rows.length" empty-text="暂无待审核填报" empty-description="学院提交或退回后重报会进入校级审核待办。" @retry="load"><el-table :data="rows"><el-table-column label="指标／任务" min-width="280"><template #default="{ row }"><button class="link-button" style="text-align:left;font-weight:550" @click="router.push(`/execution/reviews/${row.id}`)">{{ row.indicator_name }}</button><div class="mono subtle" style="margin-top:4px">{{ row.task_code }}</div></template></el-table-column><el-table-column prop="plan_name" label="计划" min-width="170" show-overflow-tooltip /><el-table-column prop="college_org_name" label="学院" width="150" /><el-table-column prop="target_year" label="年度" width="75" align="center" /><el-table-column label="填报版本" width="100"><template #default="{ row }"><VersionTag :version="row.version_no" :current="row.is_current" /></template></el-table-column><el-table-column label="学院填报值" width="135"><template #default="{ row }"><ValueDisplay :value="row.reported_value" :text="row.reported_text" :bool-value="row.reported_bool" :unit="row.task?.unit_name || row.task?.unit_code" /></template></el-table-column><el-table-column label="材料" width="80" align="center"><template #default="{ row }">{{ row.materials?.length ?? '—' }} 项</template></el-table-column><el-table-column label="提交时间" width="165"><template #default="{ row }">{{ formatDateTime(row.submitted_at) }}</template></el-table-column><el-table-column label="状态" width="95"><template #default="{ row }"><StatusTag :status="row.submission_status" /></template></el-table-column><el-table-column label="操作" width="95" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="router.push(`/execution/reviews/${row.id}`)">进入审核</el-button></template></el-table-column></el-table><div class="table-footer"><el-pagination v-model:current-page="query.page" v-model:page-size="query.page_size" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div></RemoteState>
  </div>
</template>
