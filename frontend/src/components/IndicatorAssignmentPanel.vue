<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Delete, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorMessage, executionApi, optionsApi } from '@/api'
import type { AnnualTarget, CollegeTask, ConstructionPlan, ID, Indicator, Numeric, Organization, UserAccount, ValueKind } from '@/types/api'
import { isBlank } from '@/utils/format'
import StatusTag from '@/components/StatusTag.vue'
import TargetValueEditor from '@/components/TargetValueEditor.vue'
import AnnualTargetGrid from '@/components/AnnualTargetGrid.vue'

interface DraftTask extends Omit<Partial<CollegeTask>, 'milestones'> { key: string; milestones: AnnualTarget[] }
const props = defineProps<{ indicator: Indicator; plan: ConstructionPlan; canAssign: boolean }>()
const emit = defineEmits<{ assigned: [count: number] }>()
const router = useRouter()
const existing = ref<CollegeTask[]>([])
const colleges = ref<Organization[]>([])
const owners = ref<UserAccount[]>([])
const drafts = ref<DraftTask[]>([])
const selectedCollege = ref<ID>('')
const loading = ref(true)
const saving = ref(false)
const nonAdditive = ref(false)
const nonAdditiveReason = ref('')
const serverPreview = ref<Record<string, unknown>>()
const assignDialog = ref(false)
const years = computed(() => Array.from({ length: props.plan.end_year - props.plan.start_year + 1 }, (_, index) => props.plan.start_year + index))
const valueKind = computed<ValueKind>(() => props.indicator.value_kind || (props.indicator.calc_method === 'BOOLEAN' ? 'BOOLEAN' : 'DECIMAL'))
// 复合单位既可能按数值量化，也可能保留文本；按指标已量化形态选择编辑器，避免把数值目标当成文本。
const editorKind = computed<ValueKind>(() => {
  const indicator = props.indicator
  const hasNumeric = numberOrNull(indicator.professional_five_year_target_value) !== null
    || (indicator.annual_targets || []).some((target) => numberOrNull(target.professional_target_value) !== null)
  return hasNumeric ? 'DECIMAL' : valueKind.value
})
const numericKind = computed(() => ['DECIMAL', 'PERCENT', 'PERCENT_YEAR'].includes(editorKind.value))
const additive = computed(() => !nonAdditive.value && ['CUMULATIVE', 'ANNUAL_QUOTA', 'FINAL'].includes(props.indicator.calc_method || ''))
const disabledReason = computed(() => {
  if (props.indicator.calc_method === 'EXTERNAL') return '外部数据型由校级负责人录入认定，无需下发学院任务'
  if (!['PUBLISHED', 'IN_PROGRESS'].includes(props.plan.plan_status)) return '计划发布后才能选择接收学院并下发任务'
  if (props.indicator.indicator_status !== 'PUBLISHED') return '请先保存并发布指标，再选择接收学院和负责人'
  if (!props.canAssign) return '仅校级负责人可在计划未关闭时下发学院任务'
  return ''
})
function numeric(value: Numeric | null | undefined) { return isBlank(value) ? 0 : Number(value) }
function numberOrNull(value: Numeric | null | undefined) { return isBlank(value) ? null : Number(value) }
function formatTarget(value: number | null) { return value === null ? '未提供' : String(value) }
const allocatedFiveYear = computed(() => drafts.value.reduce((sum, task) => sum + numeric(task.five_year_target_value), 0))
const fiveYearTarget = computed(() => numberOrNull(props.indicator.professional_five_year_target_value))
const difference = computed(() => (fiveYearTarget.value === null ? null : fiveYearTarget.value - allocatedFiveYear.value))
const previewErrors = computed(() => Array.isArray(serverPreview.value?.errors) ? serverPreview.value.errors as string[] : [])
function annualTotal(year: number) { return drafts.value.reduce((sum, task) => sum + numeric(task.milestones.find((item) => item.target_year === year)?.current_target_value), 0) }
function annualTargetRecord(year: number) { return props.indicator.annual_targets?.find((item) => item.target_year === year) }
function indicatorAnnual(year: number) { return numberOrNull(annualTargetRecord(year)?.professional_target_value) }
function annualTargetLabel(year: number) {
  const target = annualTargetRecord(year)
  if (!target) return '未提供'
  if (target.professional_target_bool !== null && target.professional_target_bool !== undefined) return target.professional_target_bool ? '是' : '否'
  if (target.professional_target_text) return target.professional_target_text
  return formatTarget(numberOrNull(target.professional_target_value))
}
function annualDifference(year: number) { const target = indicatorAnnual(year); return target === null ? null : target - annualTotal(year) }
function annualTotalLabel(year: number) { return numericKind.value ? String(annualTotal(year)) : '—' }
const fiveYearDisplay = computed(() => {
  const indicator = props.indicator
  if (indicator.professional_five_year_target_bool !== null && indicator.professional_five_year_target_bool !== undefined) return indicator.professional_five_year_target_bool ? '是' : '否'
  if (indicator.professional_five_year_target_text) return indicator.professional_five_year_target_text
  return formatTarget(numberOrNull(indicator.professional_five_year_target_value))
})
function taskFiveYearLabel(task: DraftTask) {
  if (task.five_year_target_bool !== null && task.five_year_target_bool !== undefined) return task.five_year_target_bool ? '是' : '否'
  if (task.five_year_target_text) return task.five_year_target_text
  return formatTarget(numberOrNull(task.five_year_target_value))
}
function averageTask(task: DraftTask) {
  const target = numberOrNull(task.five_year_target_value)
  if (target === null) { ElMessage.warning('请先填写该学院的五年任务目标'); return }
  const count = task.milestones.length
  if (!count) return
  const precision = Math.min(Math.max(props.indicator.decimal_precision ?? 2, 0), 8)
  const factor = 10 ** precision
  // 向上取整，保证各年度目标之和不少于学院五年任务目标。
  const average = Math.ceil((target * factor) / count - 1e-9) / factor
  task.milestones = task.milestones.map((item) => ({ ...item, current_target_value: average, current_target_text: null, current_target_bool: null }))
  ElMessage.success(`已按 ${count} 个年度平均分配，每年 ${average}${props.indicator.unit_raw || ''}`)
}
function availableOwners(task: DraftTask) { return owners.value.filter((item) => item.primary_org_id === task.college_org_id) }
async function load() {
  loading.value = true
  try {
    const [tasks, orgs, users] = await Promise.all([
      executionApi.tasks({ indicator_id: props.indicator.id, page_size: 200 }),
      optionsApi.organizations({ org_type: 'COLLEGE', status: 'ENABLED', page_size: 200 }),
      optionsApi.users({ role: 'COLLEGE_OWNER', status: 'ENABLED', page_size: 200 }),
    ])
    const indicatorTasks = tasks.items.filter((item) => String(item.indicator_id) === String(props.indicator.id))
    existing.value = indicatorTasks.filter((item) => !['REVOKED', 'DRAFT'].includes(item.task_status))
    drafts.value = indicatorTasks.filter((item) => item.task_status === 'DRAFT').map((task) => ({ ...task, key: `draft-${task.id}`, milestones: years.value.map((target_year) => task.milestones?.find((item) => item.target_year === target_year) || { target_year, current_target_value: null, current_target_text: null, current_target_bool: null, target_version_no: 1 }) }))
    colleges.value = orgs.items
    owners.value = users.items
  } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { loading.value = false }
}
function invalidatePreview() { serverPreview.value = undefined; assignDialog.value = false }
function addCollege() {
  if (!selectedCollege.value || disabledReason.value) return
  const org = colleges.value.find((item) => item.id === selectedCollege.value)
  drafts.value.push({ key: `${Date.now()}-${selectedCollege.value}`, indicator_id: props.indicator.id, college_org_id: selectedCollege.value, college_org_name: org?.org_name, owner_user_id: '', execution_scope: '', five_year_target_value: null, five_year_target_text: null, five_year_target_bool: null, milestones: years.value.map((target_year) => ({ target_year, current_target_value: null, current_target_text: null, current_target_bool: null, target_version_no: 1 })) })
  selectedCollege.value = ''
}
function taskPayload() {
  return {
    tasks: drafts.value.map((task) => ({
      college_org_id: task.college_org_id,
      owner_user_id: task.owner_user_id,
      execution_scope: task.execution_scope || undefined,
      five_year_target_value: task.five_year_target_value,
      five_year_target_bool: task.five_year_target_bool,
      five_year_target_text: task.five_year_target_text,
      annual_targets: task.milestones.map((item) => ({ target_year: item.target_year, target_value: item.current_target_value, target_bool: item.current_target_bool, target_text: item.current_target_text, allocation_raw: item.professional_allocation_year_raw })),
    })),
    non_additive_reason: nonAdditive.value ? nonAdditiveReason.value : undefined,
    expected_version: props.indicator.lock_version,
  }
}
function validateDraft() {
  if (!drafts.value.length) { ElMessage.warning('请先添加至少一个接收学院'); return false }
  if (drafts.value.some((task) => !task.owner_user_id)) { ElMessage.warning('请为每个接收学院选择学院负责人'); return false }
  const sameCollege = drafts.value.filter((task) => task.college_org_id && drafts.value.filter((other) => other.college_org_id === task.college_org_id).length > 1)
  if (sameCollege.some((task) => !task.execution_scope?.trim())) { ElMessage.warning('同一学院多项任务必须填写不同执行范围'); return false }
  if (nonAdditive.value && !nonAdditiveReason.value.trim()) { ElMessage.warning('非加总分配必须填写原因'); return false }
  if (additive.value && difference.value !== null && Math.abs(difference.value) > 0.000001) { ElMessage.warning('五年目标存在未解释差异，请调整目标或标记非加总分配'); return false }
  if (additive.value && years.value.some((year) => { const value = annualDifference(year); return value !== null && Math.abs(value) > 0.000001 })) { ElMessage.warning('至少一个已提供目标值的年度存在未解释分配差异，请查看年度差异表'); return false }
  return true
}
async function preview() {
  if (saving.value || disabledReason.value || !validateDraft()) return
  saving.value = true
  try {
    serverPreview.value = await executionApi.previewDecomposition(props.indicator.id, taskPayload())
    assignDialog.value = true
  } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { saving.value = false }
}
async function assign() {
  if (saving.value || !serverPreview.value || previewErrors.value.length) return
  saving.value = true
  try {
    const result = await executionApi.assignTasks(props.indicator.id, taskPayload())
    const count = result.items.length
    assignDialog.value = false
    ElMessage.success(`已下发至 ${count} 个学院，任务待负责人接收`)
    emit('assigned', count)
    await load()
  } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { saving.value = false }
}
watch([drafts, nonAdditive, nonAdditiveReason], invalidatePreview, { deep: true })
onMounted(load)
</script>

<template>
  <div class="assignment-panel" v-loading="loading">
    <el-alert v-if="disabledReason" :title="disabledReason" type="info" :closable="false" show-icon />
    <template v-else>
      <div class="assignment-guide"><strong>接收流程</strong><span>选择接收学院</span><span>指定学院负责人</span><span>分配任务目标</span><span>预览并确认下发</span></div>
      <section v-if="existing.length" class="assignment-section">
        <div class="section-heading"><h3 class="section-title">已下发任务</h3><span class="form-tip">接收人和任务状态清晰可见；已接收任务不可在此覆盖</span></div>
        <el-table :data="existing" size="small"><el-table-column prop="task_code" label="任务代码" width="145" /><el-table-column prop="college_org_name" label="接收学院" min-width="150" /><el-table-column prop="owner_user_name" label="学院负责人" width="120" /><el-table-column prop="execution_scope" label="执行范围" min-width="140" /><el-table-column label="状态" width="105"><template #default="{ row }"><StatusTag :status="row.task_status" /></template></el-table-column><el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="primary" @click="router.push(`/execution/tasks/${row.id}`)">查看</el-button></template></el-table-column></el-table>
      </section>
      <section class="assignment-section">
        <div class="section-heading"><h3 class="section-title">新增接收学院</h3><span class="form-tip">正式下发前不会生成学院任务</span></div>
        <div class="college-picker"><el-select v-model="selectedCollege" placeholder="第一步：选择接收学院" filterable style="width:280px"><el-option v-for="org in colleges" :key="org.id" :value="org.id" :label="org.org_name" /></el-select><el-button type="primary" plain :disabled="!selectedCollege" @click="addCollege"><el-icon><Plus /></el-icon>添加接收学院</el-button></div>
        <div class="non-additive"><el-checkbox v-model="nonAdditive">非加总分配／共同承担</el-checkbox><el-input v-if="nonAdditive" v-model="nonAdditiveReason" placeholder="必填：说明牵头关系或不可加总原因" style="flex:1" /><span v-else class="form-tip">累计型、年度定额型默认校验加总</span></div>
        <el-collapse v-if="drafts.length" accordion><el-collapse-item v-for="(task, index) in drafts" :key="task.key" :name="task.key"><template #title><div class="task-title"><strong>{{ task.college_org_name }}</strong><span class="subtle small">{{ task.owner_user_id ? '已选负责人' : '待选负责人' }}</span><span class="subtle small">五年目标 {{ taskFiveYearLabel(task) }}</span></div></template><div class="task-edit"><div class="task-edit-top"><el-form label-position="top"><el-form-item label="学院负责人（任务接收人）" required><el-select v-model="task.owner_user_id" filterable placeholder="第二步：选择该学院负责人" style="width:100%"><el-option v-for="user in availableOwners(task)" :key="user.id" :value="user.id" :label="`${user.user_name}（${user.primary_org_name || user.account_name}）`" /></el-select><div v-if="!availableOwners(task).length" class="warning-text">该学院暂无启用的学院负责人，请先在用户管理中配置</div></el-form-item><el-form-item label="执行范围"><el-input v-model="task.execution_scope" placeholder="同学院多项任务时必填" /></el-form-item></el-form><div><div class="target-label-row"><label class="target-label">学院五年任务目标</label><el-button v-if="numericKind" link type="primary" size="small" @click="averageTask(task)">平均分配</el-button></div><TargetValueEditor v-model:value="task.five_year_target_value" v-model:text-value="task.five_year_target_text" v-model:bool-value="task.five_year_target_bool" :value-kind="editorKind" :unit="indicator.unit_raw" /></div><el-button link type="danger" @click="drafts.splice(index, 1)"><el-icon><Delete /></el-icon>移除</el-button></div><AnnualTargetGrid v-model="task.milestones" :start-year="plan.start_year" :end-year="plan.end_year" :value-kind="editorKind" :unit="indicator.unit_raw" field-set="task" /></div></el-collapse-item></el-collapse><el-empty v-else description="尚未选择接收学院；请先添加学院，再指定负责人和任务目标" :image-size="65" />
      </section>
      <section v-if="drafts.length" class="assignment-section"><div class="section-heading"><h3 class="section-title">目标差异</h3><el-button type="primary" :loading="saving" @click="preview">预览差异并下发</el-button></div><div class="diff-grid"><div class="diff-card"><span>指标五年目标</span><strong>{{ fiveYearDisplay }}</strong></div><div class="diff-card"><span>本次已分配</span><strong>{{ numericKind ? allocatedFiveYear : '—' }}</strong></div><div class="diff-card"><span>未分配／差异</span><strong :class="numericKind && difference !== null && Math.abs(difference) > 0.000001 ? 'danger-text' : 'success-text'">{{ numericKind && difference !== null ? difference : '—' }}</strong></div><div class="diff-card"><span>接收学院</span><strong>{{ drafts.length }}</strong></div></div><el-table :data="years.map(year => ({ year, targetLabel: annualTargetLabel(year), allocated: annualTotalLabel(year), difference: annualDifference(year) }))" size="small" style="margin-top:12px"><el-table-column prop="year" label="年度" width="90" /><el-table-column prop="targetLabel" label="指标年度目标" /><el-table-column prop="allocated" label="学院目标合计" /><el-table-column label="差异"><template #default="{ row }"><span :class="row.difference !== null && Math.abs(row.difference) > 0.000001 ? 'danger-text' : 'success-text'">{{ row.difference === null ? '—' : row.difference }}</span></template></el-table-column></el-table><div class="form-tip" style="margin-top:8px">源表未提供的年度按“未提供”展示，不按 0 参与加总校验；已提供目标值的年度和五年目标仍会校验差异。</div></section>
    </template>
    <el-dialog v-model="assignDialog" title="确认接收学院与任务目标" width="650px"><el-alert title="确认下发后将生成学院待接收任务和负责人待办，不会自动视为已接收。" type="warning" :closable="false" style="margin-bottom:15px" /><div v-if="previewErrors.length" class="preview-errors"><div v-for="item in previewErrors" :key="item">· {{ item }}</div></div><div class="diff-grid"><div class="diff-card"><span>五年目标</span><strong>{{ fiveYearDisplay }}</strong></div><div class="diff-card"><span>已分配</span><strong>{{ numericKind ? allocatedFiveYear : '—' }}</strong></div><div class="diff-card"><span>未分配</span><strong>{{ numericKind && difference !== null ? difference : '—' }}</strong></div><div class="diff-card"><span>接收学院</span><strong>{{ drafts.length }}</strong></div></div><div class="receiver-summary"><div v-for="task in drafts" :key="task.key">{{ task.college_org_name }} · {{ owners.find(user => user.id === task.owner_user_id)?.user_name || '未选负责人' }}</div></div><template #footer><el-button @click="assignDialog = false">返回修改</el-button><el-button type="primary" :loading="saving" :disabled="previewErrors.length > 0" @click="assign">确认下发</el-button></template></el-dialog>
  </div>
</template>

<style scoped>
.assignment-guide{display:flex;align-items:center;gap:18px;flex-wrap:wrap;padding:12px 14px;margin-bottom:18px;background:#f2f7ff;border:1px solid #d9e8fb;border-radius:6px;color:#52647f;font-size:12px}.assignment-guide strong{color:#2468b4}.assignment-guide span:not(:last-child)::after{content:'→';margin-left:18px;color:#91a4bf}.assignment-section{padding:16px 0;border-top:1px solid var(--line)}.assignment-section:first-of-type{border-top:0}.section-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:13px}.section-heading .section-title{margin:0}.college-picker{display:flex;gap:10px;margin-bottom:14px}.non-additive{display:flex;align-items:center;gap:13px;margin-bottom:14px}.task-title{width:100%;display:flex;align-items:center;gap:14px}.task-title strong{flex:1}.task-edit{padding:4px 10px 12px}.task-edit-top{display:grid;grid-template-columns:minmax(0,1.4fr) 230px 60px;align-items:end;gap:18px}.task-edit-top .el-form{display:grid;grid-template-columns:1fr 1fr;gap:12px}.target-label{display:block;color:#606a7c;font-size:12px}.target-label-row{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:8px}.diff-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}.diff-card{padding:12px;background:#f7f9fc;border-radius:6px}.diff-card span{display:block;color:#758297;font-size:12px}.diff-card strong{display:block;margin-top:5px;font-size:20px}.preview-errors{padding:10px 14px;margin-bottom:12px;color:#b42318;background:#fef3f2;border-radius:5px}.receiver-summary{padding:12px 4px;line-height:1.9;color:#52647f}@media(max-width:1100px){.diff-grid{grid-template-columns:1fr 1fr}.task-edit-top{grid-template-columns:1fr}.task-edit-top .el-form{grid-template-columns:1fr}}
</style>
