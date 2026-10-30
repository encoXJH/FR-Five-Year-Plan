<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Plus, Search, Upload } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { errorMessage, executionApi, planningApi } from '@/api'
import type { CalcMethod, ConstructionPlan, DictionaryItem, Direction, ID, Indicator, LeaderDashboard } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { formatBusinessValue, formatNumber } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import PlanSelect from '@/components/PlanSelect.vue'
import StatusTag from '@/components/StatusTag.vue'

interface StatusOption { label: string; value: string }
const statusOptions: StatusOption[] = [
  { label: '待确认', value: 'NEED_CONFIRM' },
  { label: '已确认', value: 'CONFIRMED' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '草稿', value: 'DRAFT' },
]

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const planId = ref(String(route.query.plan_id || ''))
const plan = ref<ConstructionPlan>()
const dashboard = ref<LeaderDashboard>()
const directions = ref<Direction[]>([])
const indicators = ref<Indicator[]>([])
const types = ref<DictionaryItem[]>([])
const units = ref<DictionaryItem[]>([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const statusFilter = ref('')
const selectedDirectionId = ref<ID>()
const expandedIds = ref<Set<string>>(new Set())
const directionDialog = ref(false)
const indicatorDialog = ref(false)
const saving = ref(false)
const directionFormRef = ref<FormInstance>()
const indicatorFormRef = ref<FormInstance>()
const editingDirectionId = ref<ID>()
const isSchoolOwner = computed(() => auth.roles.includes('SCHOOL_OWNER'))
const canEdit = computed(() => isSchoolOwner.value && Boolean(plan.value) && plan.value?.plan_status !== 'CLOSED')
const hasProgressData = computed(() => Boolean(dashboard.value))
const years = computed(() => plan.value ? Array.from({ length: plan.value.end_year - plan.value.start_year + 1 }, (_, index) => plan.value!.start_year + index) : [])
const selectedDirection = computed(() => directions.value.find((item) => String(item.id) === String(selectedDirectionId.value)))
const directionCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const item of indicators.value) counts.set(String(item.direction_id), (counts.get(String(item.direction_id)) || 0) + 1)
  return counts
})
const progressMap = computed(() => new Map((dashboard.value?.indicators || []).map((item) => [String(item.id), item])))
const filteredIndicators = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return indicators.value.filter((item) => {
    if (selectedDirectionId.value && String(item.direction_id) !== String(selectedDirectionId.value)) return false
    if (statusFilter.value === 'PUBLISHED' && item.indicator_status !== 'PUBLISHED') return false
    if (statusFilter.value === 'DRAFT' && item.indicator_status !== 'DRAFT') return false
    if (['NEED_CONFIRM', 'CONFIRMED'].includes(statusFilter.value) && item.parse_status !== statusFilter.value) return false
    if (text && !`${item.indicator_name}${item.indicator_code}`.toLowerCase().includes(text)) return false
    return true
  })
})
const allExpanded = computed(() => filteredIndicators.value.length > 0 && filteredIndicators.value.every((item) => expandedIds.value.has(String(item.id))))

const directionForm = reactive({ plan_id: '' as ID, direction_code: '', direction_name: '', source_seq: undefined as number | undefined, description: '', sort_no: 1, status: 'ENABLED', lock_version: undefined as number | undefined })
const indicatorForm = reactive({ plan_id: '' as ID, direction_id: '' as ID, indicator_code: '', indicator_name: '', indicator_desc: '', indicator_type_code: '', unit_code: '', unit_raw: '', target_direction: 'GE' as 'GE' | 'LE' | 'EQ', calc_method: undefined as CalcMethod | undefined, statistical_rule: '', parse_status: 'CONFIRMED', indicator_status: 'DRAFT' })
const rules: FormRules = { direction_name: [{ required: true, message: '请输入方向名称', trigger: 'blur' }], direction_code: [{ required: true, message: '请输入方向代码', trigger: 'blur' }] }
const indicatorRules: FormRules = { direction_id: [{ required: true, message: '请选择所属方向', trigger: 'change' }], indicator_code: [{ required: true, message: '请输入指标代码', trigger: 'blur' }], indicator_name: [{ required: true, message: '请输入指标名称', trigger: 'blur' }], indicator_desc: [{ required: true, message: '请输入完整指标描述', trigger: 'blur' }], indicator_type_code: [{ required: true, message: '请选择指标类型', trigger: 'change' }], unit_code: [{ required: true, message: '请选择指标单位', trigger: 'change' }] }

async function load() {
  if (!planId.value) return
  loading.value = true; error.value = ''
  try {
    const [p, d, i, board] = await Promise.all([
      planningApi.plan(planId.value),
      planningApi.directions({ plan_id: planId.value, page_size: 200 }),
      planningApi.indicators({ plan_id: planId.value, page_size: 1000 }),
      executionApi.leaderDashboard(planId.value).catch(() => undefined),
    ])
    plan.value = p; directions.value = d.items; indicators.value = i.items; dashboard.value = board
    if (selectedDirectionId.value && !d.items.some((item) => String(item.id) === String(selectedDirectionId.value))) selectedDirectionId.value = undefined
    expandedIds.value = i.items.length ? new Set([String(i.items[0].id)]) : new Set()
  } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
function changePlan() {
  selectedDirectionId.value = undefined
  keyword.value = ''
  statusFilter.value = ''
  expandedIds.value = new Set()
  router.replace({ query: { plan_id: planId.value } })
  load()
}
function isExpanded(id: ID) { return expandedIds.value.has(String(id)) }
function toggleExpanded(id: ID) {
  const next = new Set(expandedIds.value)
  const key = String(id)
  if (next.has(key)) next.delete(key); else next.add(key)
  expandedIds.value = next
}
function toggleAll() {
  expandedIds.value = allExpanded.value ? new Set() : new Set(filteredIndicators.value.map((item) => String(item.id)))
}
function progressFor(indicator: Indicator) { return progressMap.value.get(String(indicator.id)) }
function taskCount(indicator: Indicator) { return progressFor(indicator)?.task_count || 0 }
function decomposeLabel(indicator: Indicator) { return taskCount(indicator) ? '分工管理' : '分解任务' }
function canDecompose(indicator: Indicator) { return isSchoolOwner.value && indicator.indicator_status === 'PUBLISHED' }
function fiveYearText(indicator: Indicator) {
  return formatBusinessValue(indicator.professional_five_year_target_value, { text: indicator.professional_five_year_target_text, boolValue: indicator.professional_five_year_target_bool, valueKind: indicator.value_kind, unit: indicator.unit_raw, emptyText: '未提供' })
}
function annualText(indicator: Indicator, year: number) {
  const target = indicator.annual_targets?.find((item) => item.target_year === year)
  if (!target) return '未提供'
  return formatBusinessValue(target.professional_target_value, { text: target.professional_target_text, boolValue: target.professional_target_bool, valueKind: indicator.value_kind, unit: indicator.unit_raw, emptyText: '未提供' })
}
function currentText(indicator: Indicator) {
  const progress = progressFor(indicator)
  if (!progress) return '—'
  if (progress.progress_basis === 'VALUE') return `${formatNumber(progress.recognized_value ?? 0)} ${indicator.unit_raw || ''}`.trim()
  if (progress.milestone_count) return `${progress.completed} / ${progress.milestone_count} 个年度`
  return '—'
}
function openDirection(row?: Direction) {
  editingDirectionId.value = row?.id
  Object.assign(directionForm, row ? { ...row } : { plan_id: planId.value, direction_code: '', direction_name: '', source_seq: undefined, description: '', sort_no: directions.value.length + 1, status: 'ENABLED', lock_version: undefined })
  directionDialog.value = true
}
async function saveDirection() {
  if (!await directionFormRef.value?.validate().catch(() => false)) return
  saving.value = true
  try { if (editingDirectionId.value) await planningApi.updateDirection(editingDirectionId.value, directionForm); else await planningApi.createDirection(directionForm); ElMessage.success('建设方向已保存'); directionDialog.value = false; await load() } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { saving.value = false }
}
async function openIndicator() {
  if (!types.value.length) { const [t, u] = await Promise.all([planningApi.indicatorTypes(), planningApi.indicatorUnits()]); types.value = t.items; units.value = u.items }
  Object.assign(indicatorForm, { plan_id: planId.value, direction_id: selectedDirectionId.value || directions.value[0]?.id || '', indicator_code: '', indicator_name: '', indicator_desc: '', indicator_type_code: '', unit_code: '', unit_raw: '', target_direction: 'GE' as 'GE' | 'LE' | 'EQ', calc_method: undefined as CalcMethod | undefined, statistical_rule: '', parse_status: 'CONFIRMED', indicator_status: 'DRAFT' }); indicatorDialog.value = true
}
function changeUnit(code: string) { indicatorForm.unit_raw = units.value.find((item) => item.unit_code === code)?.unit_name || '' }
async function saveIndicator() {
  if (!await indicatorFormRef.value?.validate().catch(() => false)) return
  saving.value = true
  try { const result = await planningApi.createIndicator(indicatorForm); ElMessage.success('指标草稿已创建，请继续量化'); indicatorDialog.value = false; router.push(`/planning/indicators/${result.id}`) } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { saving.value = false }
}
onMounted(load)
</script>
<template>
  <div class="page-container indicator-ledger">
    <PageHeader title="指标总表" :description="plan ? `${plan.plan_name}（${plan.start_year}—${plan.end_year}）· ${directions.length} 个方向 · ${indicators.length} 项指标` : '选择建设计划后查看方向与关键产出指标'">
      <template #actions>
        <el-button v-if="canEdit" type="primary" :disabled="!directions.length" @click="openIndicator"><el-icon><Plus /></el-icon>新增指标</el-button>
        <el-button v-if="canEdit" @click="openDirection()"><el-icon><Plus /></el-icon>新增方向</el-button>
        <el-button v-if="isSchoolOwner" :disabled="!planId" @click="router.push('/planning/imports')"><el-icon><Upload /></el-icon>导入 Excel</el-button>
      </template>
    </PageHeader>
    <el-alert v-if="error" :title="error" type="error" show-icon style="margin-bottom:14px"><el-button link @click="load">重试</el-button></el-alert>
    <el-alert v-if="plan && ['PUBLISHED', 'IN_PROGRESS'].includes(plan.plan_status)" title="计划已发布，仍可继续新增方向和指标；新增指标以草稿状态创建，完成量化后需单独点击“发布指标”，再下发给学院。" type="info" show-icon :closable="false" style="margin-bottom:14px" />
    <div v-if="planId" class="ledger-shell" v-loading="loading">
      <aside class="direction-rail">
        <div class="rail-head">
          <h3>主要建设方向</h3>
          <div class="rail-actions">
            <el-button v-if="canEdit" link type="primary" @click="openDirection()">添加</el-button>
            <el-button v-if="canEdit && selectedDirection" link @click="openDirection(selectedDirection)">编辑</el-button>
          </div>
        </div>
        <div class="direction-list">
          <button class="direction-item" :class="{ active: !selectedDirectionId }" @click="selectedDirectionId = undefined">
            <span>全部方向</span><em>{{ indicators.length }}</em>
          </button>
          <button v-for="direction in directions" :key="direction.id" class="direction-item" :class="{ active: String(selectedDirectionId) === String(direction.id) }" @click="selectedDirectionId = direction.id">
            <span :title="direction.direction_name">{{ direction.direction_name }}</span><em>{{ directionCounts.get(String(direction.id)) || 0 }}</em>
          </button>
        </div>
        <el-empty v-if="!directions.length" description="暂无建设方向" :image-size="46" />
      </aside>
      <section class="ledger-main">
        <div class="ledger-filter">
          <div class="ledger-plan"><PlanSelect v-model="planId" @change="changePlan" /></div>
          <el-input v-model="keyword" placeholder="指标编码或名称" clearable style="width:220px" @keyup.enter="load"><template #prefix><el-icon><Search /></el-icon></template></el-input>
          <el-select v-model="statusFilter" placeholder="量化状态" clearable style="width:130px"><el-option label="全部状态" value="" /><el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select>
          <el-button type="primary" plain @click="load">查询</el-button>
          <div class="ledger-tools"><el-button link @click="toggleAll">{{ allExpanded ? '收起全部' : '全部展开' }}</el-button></div>
        </div>
        <div class="ledger-table-head"><span>编码</span><span>关键产出指标</span><span>建设方向</span><span class="head-actions">操作</span></div>
        <div v-if="filteredIndicators.length" class="indicator-list">
          <div v-for="row in filteredIndicators" :key="row.id" class="indicator-entry" :class="{ expanded: isExpanded(row.id) }">
            <div class="indicator-summary" role="button" tabindex="0" @click="toggleExpanded(row.id)" @keydown.enter.prevent="toggleExpanded(row.id)" @keydown.space.prevent="toggleExpanded(row.id)">
              <span class="entry-code"><el-icon class="entry-chevron" :class="{ open: isExpanded(row.id) }"><ArrowRight /></el-icon><span class="mono">{{ row.indicator_code }}</span></span>
              <span class="entry-name-wrap"><span class="entry-name" :title="row.indicator_name">{{ row.indicator_name }}</span><StatusTag :status="row.indicator_status" /></span>
              <span class="entry-direction" :title="row.direction_name">{{ row.direction_name }}</span>
              <span class="entry-actions" @click.stop>
                <el-button link type="primary" @click="router.push(`/planning/indicators/${row.id}`)">详情</el-button>
                <el-button v-if="isSchoolOwner" link type="primary" :disabled="row.indicator_status !== 'PUBLISHED'" @click="router.push(`/planning/indicators/${row.id}/decompose`)">{{ decomposeLabel(row) }}</el-button>
                <span v-else class="entry-readonly">只读</span>
              </span>
            </div>
            <div v-if="isExpanded(row.id)" class="indicator-detail">
              <div class="detail-meta">
                <div class="meta-field meta-policy"><span>统计口径</span><div class="meta-tags"><StatusTag :status="row.indicator_type_code" /><StatusTag :status="row.calc_method" /><span class="unit-text">{{ row.unit_raw }}</span></div></div>
                <div class="meta-field"><span>五年目标</span><strong>{{ fiveYearText(row) }}</strong></div>
                <div class="meta-field"><span>当前完成</span><strong>{{ currentText(row) }}</strong></div>
                <div class="meta-field"><span>当年完成量</span><strong class="subtle">—</strong></div>
                <div class="meta-field"><span>五年累计</span><strong>{{ currentText(row) }}</strong></div>
                <div class="meta-field"><span>负责人</span><strong>{{ plan?.school_owner_name || '—' }}</strong></div>
              </div>
              <div class="year-grid-head"><span>年度目标</span><span>空值＝未提供 · 0＝数值零 · —＝不适用</span></div>
              <div class="detail-year-grid">
                <div v-for="year in years" :key="year" class="year-cell"><span>{{ year }}</span><strong>{{ annualText(row, year) }}</strong></div>
              </div>
              <template v-if="hasProgressData">
                <div v-if="!taskCount(row)" class="task-placeholder">暂无学院任务，可通过「{{ decomposeLabel(row) }}」创建学院分工</div>
                <div v-else class="task-summary"><span>已下发 <strong>{{ taskCount(row) }}</strong> 个学院任务</span><el-button v-if="canDecompose(row)" link type="primary" @click="router.push(`/planning/indicators/${row.id}/decompose`)">查看／继续分工</el-button></div>
              </template>
              <div v-else class="task-placeholder">学院任务信息暂不可用，请前往任务详情查看</div>
            </div>
          </div>
        </div>
        <el-empty v-else description="当前筛选下没有指标" :image-size="70" />
      </section>
    </div>
    <div v-else class="state-block"><h3>请选择建设计划</h3><p>选择计划后可查看其真实方向指标总表和量化状态。</p></div>
    <el-dialog v-model="directionDialog" :title="editingDirectionId ? '编辑建设方向' : '新建建设方向'" width="560px"><el-form ref="directionFormRef" :model="directionForm" :rules="rules" label-width="95px"><el-form-item label="方向代码" prop="direction_code"><el-input v-model="directionForm.direction_code" :disabled="Boolean(editingDirectionId)" placeholder="计划内唯一" /></el-form-item><el-form-item label="方向名称" prop="direction_name"><el-input v-model="directionForm.direction_name" type="textarea" :rows="2" maxlength="500" /></el-form-item><el-form-item label="来源序号"><el-input-number v-model="directionForm.source_seq" :min="1" placeholder="仅用于溯源" /></el-form-item><el-form-item label="排序号"><el-input-number v-model="directionForm.sort_no" :min="1" /></el-form-item><el-form-item label="方向说明"><el-input v-model="directionForm.description" type="textarea" :rows="3" /></el-form-item></el-form><template #footer><el-button @click="directionDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveDirection">保存</el-button></template></el-dialog>
    <el-dialog v-model="indicatorDialog" title="新建关键产出指标" width="700px"><el-form ref="indicatorFormRef" :model="indicatorForm" :rules="indicatorRules" label-width="105px"><el-form-item label="所属方向" prop="direction_id"><el-select v-model="indicatorForm.direction_id" style="width:100%"><el-option v-for="item in directions" :key="item.id" :value="item.id" :label="item.direction_name" /></el-select></el-form-item><el-form-item label="指标代码" prop="indicator_code"><el-input v-model="indicatorForm.indicator_code" placeholder="计划内唯一，不使用来源序号代替" /></el-form-item><el-form-item label="指标名称" prop="indicator_name"><el-input v-model="indicatorForm.indicator_name" type="textarea" :rows="2" maxlength="1000" /></el-form-item><el-form-item label="完整描述" prop="indicator_desc"><el-input v-model="indicatorForm.indicator_desc" type="textarea" :rows="3" placeholder="完整保留统计范围和括号说明" /></el-form-item><div style="display:grid;grid-template-columns:1fr 1fr;gap:15px"><el-form-item label="指标类型" prop="indicator_type_code"><el-select v-model="indicatorForm.indicator_type_code" style="width:100%"><el-option v-for="item in types" :key="item.type_code" :value="item.type_code" :label="item.type_name" /></el-select></el-form-item><el-form-item label="指标单位" prop="unit_code"><el-select v-model="indicatorForm.unit_code" style="width:100%" @change="changeUnit"><el-option v-for="item in units" :key="item.unit_code" :value="item.unit_code" :label="item.unit_name" /></el-select></el-form-item></div></el-form><template #footer><el-button @click="indicatorDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveIndicator">创建并量化</el-button></template></el-dialog>
  </div>
</template>
<style scoped>
.indicator-ledger .ledger-shell{display:grid;grid-template-columns:220px minmax(0,1fr);overflow:hidden;background:#fff;border:1px solid var(--line);border-radius:8px}
.direction-rail{min-width:0;background:#fafbfd;border-right:1px solid var(--line)}
.rail-head{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:14px 14px 10px}
.rail-head h3{margin:0;font-size:13px;font-weight:650;color:#2a3750}
.rail-actions{display:flex;align-items:center;gap:2px}
.direction-list{padding:0 8px 12px}
.direction-item{display:flex;align-items:flex-start;justify-content:space-between;gap:8px;width:100%;padding:9px 10px;border:0;border-radius:5px;background:transparent;color:#5d6b81;font-size:12px;line-height:1.5;text-align:left;cursor:pointer}
.direction-item:hover{background:#f1f5fc;color:#2c4f91}
.direction-item.active{background:#e9f0ff;color:#2458d3;font-weight:600}
.direction-item span{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.direction-item em{flex:0 0 auto;margin-top:1px;color:#9aa5b8;font-size:11px;font-style:normal}
.ledger-main{min-width:0;background:#fff}
.ledger-filter{display:flex;align-items:center;flex-wrap:wrap;gap:10px;padding:12px 14px;border-bottom:1px solid var(--line)}
.ledger-plan{width:280px}
.ledger-tools{margin-left:auto;display:flex;align-items:center;gap:8px}
.ledger-table-head{display:grid;grid-template-columns:110px minmax(0,1fr) 170px 200px;gap:12px;padding:10px 16px;background:#f7f9fc;border-bottom:1px solid var(--line);color:#7e8a9d;font-size:11px}
.head-actions{text-align:right}
.indicator-entry{border-bottom:1px solid #edf0f5}
.indicator-entry:last-child{border-bottom:0}
.indicator-summary{display:grid;grid-template-columns:110px minmax(0,1fr) 170px 200px;gap:12px;align-items:center;padding:12px 16px;cursor:pointer;outline:none}
.indicator-summary:hover{background:#f8faff}
.indicator-summary:focus-visible{background:#eef5ff}
.entry-code{display:flex;align-items:center;gap:6px;min-width:0;color:#576880;font-size:11px}
.entry-code .mono{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.entry-chevron{flex:0 0 auto;color:#9aa8bc;transition:transform .15s}
.entry-chevron.open{transform:rotate(90deg);color:#2458d3}
.entry-name-wrap{display:flex;align-items:center;gap:8px;min-width:0}
.entry-name{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#22314d;font-size:12px;font-weight:550}
.entry-direction{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#77859a;font-size:11px}
.entry-actions{display:flex;align-items:center;justify-content:flex-end;gap:10px}
.entry-readonly{color:#a3adbd;font-size:11px}
.indicator-detail{padding:0 16px 16px 138px}
.detail-meta{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;padding:12px;background:#f4f8ff;border:1px solid #dee9fa;border-radius:5px}
.meta-field{min-width:0}
.meta-field span{display:block;color:#8190a6;font-size:10px}
.meta-field strong{display:block;margin-top:4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#25344f;font-size:12px;font-weight:600}
.meta-policy{grid-column:span 1}
.meta-tags{display:flex;align-items:center;gap:5px;flex-wrap:wrap;margin-top:5px}
.unit-text{color:#66758d;font-size:11px}
.year-grid-head{display:flex;align-items:center;justify-content:space-between;gap:10px;margin:12px 0 7px;color:#66758d;font-size:11px}
.year-grid-head span:last-child{color:#9aa5b8;font-size:10px}
.detail-year-grid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:8px}
.year-cell{padding:8px 10px;background:#fafbfd;border:1px solid #edf0f6;border-radius:4px}
.year-cell span{display:block;color:#8b97a9;font-size:10px}
.year-cell strong{display:block;margin-top:4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#25344f;font-size:12px;font-weight:600}
.task-placeholder{margin-top:10px;padding:14px;border:1px dashed #d7e0ee;border-radius:4px;color:#8b97a9;font-size:11px;text-align:center}
.task-summary{display:flex;align-items:center;gap:12px;margin-top:10px;padding:10px 12px;background:#f7faff;border:1px solid #e2eaf7;border-radius:4px;color:#65738b;font-size:11px}
@media(max-width:1300px){
  .indicator-ledger .ledger-shell{grid-template-columns:190px minmax(0,1fr)}
  .ledger-table-head,.indicator-summary{grid-template-columns:100px minmax(0,1fr) 140px 180px}
  .indicator-detail{padding-left:16px}
}
@media(max-width:1000px){
  .indicator-ledger .ledger-shell{grid-template-columns:1fr}
  .direction-rail{border-right:0;border-bottom:1px solid var(--line)}
  .direction-list{display:flex;gap:6px;overflow-x:auto;padding:0 12px 12px}
  .direction-item{width:auto;white-space:nowrap}
  .direction-item em{margin-left:6px}
  .ledger-table-head,.indicator-summary{grid-template-columns:90px minmax(0,1fr) 160px}
  .ledger-table-head span:nth-child(3),.entry-direction{display:none}
  .head-actions{text-align:right}
  .ledger-plan{width:100%}
}
</style>
