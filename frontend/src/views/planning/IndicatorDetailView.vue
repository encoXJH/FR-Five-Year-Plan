<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Check, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage, executionApi, optionsApi, planningApi } from '@/api'
import type { AnnualTarget, ConstructionPlan, DictionaryItem, Indicator, Organization, ValueKind } from '@/types/api'
import { calcMethodOptions } from '@/utils/dictionaries'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import TargetValueEditor from '@/components/TargetValueEditor.vue'
import AnnualTargetGrid from '@/components/AnnualTargetGrid.vue'
import IndicatorAssignmentPanel from '@/components/IndicatorAssignmentPanel.vue'

const route = useRoute()
const auth = useAuthStore()
const id = String(route.params.id)
const indicator = ref<Indicator>()
const plan = ref<ConstructionPlan>()
const form = ref<Partial<Indicator>>({})
const annualTargets = ref<AnnualTarget[]>([])
const types = ref<DictionaryItem[]>([])
const units = ref<DictionaryItem[]>([])
const schoolDepartments = ref<Organization[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const validation = ref<{ valid: boolean; errors: string[]; warnings: string[] }>()
const activeTab = ref('definition')
const rawSections = ref<string[]>([])
const responsibilitySections = ref<string[]>([])
const externalDialog = ref(false)
const externalSaving = ref(false)
const externalForm = ref({ target_year: undefined as number | undefined, recognized_value: undefined as number | undefined, recognized_text: '', recognized_bool: undefined as number | undefined, recognition_source: '', recognition_date: '', comment: '' })
const canEdit = computed(() => (auth.roles.includes('SCHOOL_OWNER') || auth.roles.includes('ADMIN')) && plan.value?.plan_status !== 'CLOSED')
const coreFrozen = computed(() => !canEdit.value || indicator.value?.indicator_status === 'PUBLISHED')
const currentUnit = computed(() => units.value.find((item) => item.unit_code === form.value.unit_code))
const valueKind = computed<ValueKind>(() => form.value.value_kind || currentUnit.value?.value_kind || (form.value.calc_method === 'BOOLEAN' ? 'BOOLEAN' : 'DECIMAL'))
const years = computed(() => Array.from({ length: plan.value ? plan.value.end_year - plan.value.start_year + 1 : 0 }, (_, index) => (plan.value?.start_year || 0) + index))
const fiveYearTarget = computed(() => {
  const value = form.value.professional_five_year_target_value
  if (value === null || value === undefined || value === '') return undefined
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : undefined
})
const canAverage = computed(() => canEdit.value && ['DECIMAL', 'PERCENT', 'PERCENT_YEAR'].includes(valueKind.value) && fiveYearTarget.value !== undefined)
function averageAllocate() {
  const target = fiveYearTarget.value
  if (target === undefined) { ElMessage.warning('请先填写专业群五年目标'); return }
  if (!years.value.length) return
  const precision = Math.min(Math.max(form.value.decimal_precision ?? 2, 0), 8)
  const factor = 10 ** precision
  // 向上取整，保证各年度目标之和不少于五年目标。
  const average = Math.ceil((target * factor) / years.value.length - 1e-9) / factor
  annualTargets.value = annualTargets.value.map((item) => ({ ...item, professional_target_value: average, professional_target_raw: String(average), confirm_status: 'PENDING' }))
  const unit = form.value.unit_raw && form.value.unit_raw !== '—' ? form.value.unit_raw : ''
  ElMessage.success(`已按 ${years.value.length} 个年度平均分配，每年 ${average}${unit}，年度合计 ${Number((average * years.value.length).toFixed(precision))}${unit}`)
}
async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await planningApi.indicator(id)
    indicator.value = data
    const [p, t, u] = await Promise.all([planningApi.plan(data.plan_id), planningApi.indicatorTypes(), planningApi.indicatorUnits()])
    plan.value = p; types.value = t.items; units.value = u.items
    form.value = JSON.parse(JSON.stringify(data)) as Indicator
    annualTargets.value = years.value.map((target_year) => data.annual_targets?.find((item) => item.target_year === target_year) || { target_year, indicator_id: data.id, target_version_no: 1, confirm_status: 'PENDING' })
    if (!form.value.departments) form.value.departments = []
  } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
async function loadDepartments() { if (schoolDepartments.value.length) return; try { schoolDepartments.value = (await optionsApi.organizations({ org_type: 'SCHOOL_DEPT', status: 'ENABLED' })).items } catch { schoolDepartments.value = [] } }
function changeUnit(code: string) { form.value.unit_raw = units.value.find((item) => item.unit_code === code)?.unit_name || ''; form.value.value_kind = units.value.find((item) => item.unit_code === code)?.value_kind }
function addDepartment() { if (!form.value.departments) form.value.departments = []; form.value.departments.push({ org_id: '', relation_type: 'LEAD', responsibility_desc: '' }); loadDepartments() }
function focusValidation(messages: string[]) {
  activeTab.value = messages.some((item) => /年度|五年目标|学校分工|年目标/.test(item)) ? 'targets' : 'definition'
}
async function save(showMessage = true) {
  if (!form.value.indicator_name || !form.value.indicator_desc) { activeTab.value = 'definition'; ElMessage.warning('指标名称与完整描述不能为空'); return }
  saving.value = true; validation.value = undefined
  try {
    const saved = await planningApi.updateIndicator(id, { ...form.value, annual_targets: undefined, lock_version: indicator.value?.lock_version })
    const withTargets = await planningApi.saveAnnualTargets(id, annualTargets.value, saved.lock_version)
    indicator.value = withTargets; form.value = { ...withTargets, annual_targets: undefined }
    annualTargets.value = years.value.map((target_year) => withTargets.annual_targets?.find((item) => item.target_year === target_year) || { target_year, indicator_id: withTargets.id, target_version_no: 1, confirm_status: 'PENDING' })
    if (showMessage) ElMessage.success('量化草稿及年度目标已保存')
    return withTargets
  } catch (cause) { ElMessage.error(errorMessage(cause)); return undefined } finally { saving.value = false }
}
async function confirmAnnualTargets(showMessage = true) {
  annualTargets.value = annualTargets.value.map((item) => ({ ...item, confirm_status: 'CONFIRMED' }))
  const saved = await save(false)
  if (!saved) return undefined
  try {
    const result = await planningApi.validateIndicator(id)
    validation.value = result
    if (!result.valid) {
      focusValidation(result.errors)
      ElMessage.warning('年度目标已保存，请继续处理页面列出的量化问题')
      return undefined
    }
    const confirmed = await planningApi.updateIndicator(id, { parse_status: 'CONFIRMED', lock_version: saved.lock_version })
    indicator.value = confirmed
    form.value = { ...form.value, parse_status: confirmed.parse_status }
    if (showMessage) ElMessage.success('量化内容已保存并确认；未提供和不适用均按原义保留')
    return confirmed
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
    return undefined
  }
}
async function validate() {
  try {
    validation.value = await planningApi.validateIndicator(id)
    if (!validation.value.valid) { focusValidation(validation.value.errors); ElMessage.warning('仍有必填量化或核算口径待完善'); return }
    const confirmed = await planningApi.updateIndicator(id, { parse_status: 'CONFIRMED', lock_version: indicator.value?.lock_version })
    indicator.value = confirmed
    form.value = { ...form.value, parse_status: confirmed.parse_status }
    ElMessage.success('发布校验通过，量化内容已显式确认')
  } catch (cause) { ElMessage.error(errorMessage(cause)) }
}
async function publish() {
  try {
    await ElMessageBox.confirm(`将保存并确认${years.value.join('、')}年度目标（包括“未提供”和“不适用”），然后发布该指标。确认继续？`, '确认年度目标并发布', { type: 'warning', confirmButtonText: '确认并发布' })
    const confirmed = await confirmAnnualTargets(false)
    if (!confirmed) return
    await planningApi.publishIndicator(id, confirmed.lock_version)
    ElMessage.success('指标已发布，请选择接收学院和学院负责人')
    await load()
    activeTab.value = 'assignment'
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    ElMessage.error(errorMessage(cause))
  }
}
function openExternalRecognition() {
  externalForm.value = { target_year: years.value[0], recognized_value: undefined, recognized_text: '', recognized_bool: undefined, recognition_source: form.value.external_data_source || '', recognition_date: '', comment: '' }
  externalDialog.value = true
}
async function saveExternalRecognition() {
  const data = externalForm.value
  if (!data.target_year || !data.recognition_source.trim()) { ElMessage.warning('认定年度和外部来源必填'); return }
  externalSaving.value = true
  try {
    await executionApi.externalIndicatorRecognition(id, { target_year: data.target_year, recognized_value: data.recognized_value, recognized_bool: data.recognized_bool, recognized_text: data.recognized_text || undefined, external_source: data.recognition_source, recognition_note: `${data.recognition_date ? `认定日期：${data.recognition_date}；` : ''}${data.comment}` })
    ElMessage.success('外部认定已保存并触发核算；未生成学院填报')
    externalDialog.value = false
    await load()
  } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { externalSaving.value = false }
}
onMounted(load)
</script>
<template>
  <div class="page-container" v-loading="loading">
    <PageHeader :title="indicator?.indicator_name || '指标量化详情'" :description="indicator ? `${indicator.indicator_code} · ${indicator.direction_name || '所属方向'} · 目标版本 V${indicator.target_version_no}` : '读取指标量化信息'"><template #actions><el-button v-if="canEdit" :loading="saving" @click="save()">保存草稿</el-button><el-button v-if="canEdit" @click="validate">发布校验</el-button><el-button v-if="canEdit && ['PUBLISHED', 'IN_PROGRESS'].includes(plan?.plan_status || '') && indicator?.indicator_status !== 'PUBLISHED'" type="primary" @click="publish"><el-icon><Check /></el-icon>发布指标</el-button><el-button v-if="canEdit && indicator?.indicator_status === 'PUBLISHED' && indicator.calc_method === 'EXTERNAL'" type="primary" @click="openExternalRecognition">录入外部认定</el-button><el-button v-if="auth.roles.includes('SCHOOL_OWNER') && indicator?.indicator_status === 'PUBLISHED' && indicator.calc_method !== 'EXTERNAL'" type="primary" @click="activeTab = 'assignment'">选择接收学院并下发</el-button></template></PageHeader>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"><el-button link @click="load">重新加载</el-button></el-alert>
    <template v-if="indicator && plan">
      <div class="inline-meta" style="margin-bottom:14px"><StatusTag :status="indicator.indicator_status" /><StatusTag :status="indicator.parse_status" /><span>计划：{{ plan.plan_name }}</span><span>周期：{{ plan.start_year }}—{{ plan.end_year }}</span><span v-if="coreFrozen" class="warning-text">核心口径已冻结；未来年度修改仍须后端检查关联业务</span></div>
      <el-alert v-if="validation" :type="validation.valid ? 'success' : 'warning'" :title="validation.valid ? '发布校验通过' : '发布校验未通过'" :closable="false" style="margin-bottom:14px"><div v-for="item in validation.errors" :key="item">· {{ item }}</div><div v-for="item in validation.warnings" :key="item" class="warning-text">· {{ item }}</div></el-alert>
      <section class="panel quant-panel">
        <el-tabs v-model="activeTab" class="quant-tabs">
          <el-tab-pane label="01 指标定义" name="definition">
            <div class="quant-tab-body">
              <div class="section-heading"><h3 class="section-title">指标定义与核算口径</h3><span class="form-tip">指标类型是管理属性，不决定核算公式</span></div>
              <el-form :model="form" label-width="115px">
                <el-form-item label="关键产出指标" required><el-input v-model="form.indicator_name" type="textarea" :rows="2" :disabled="!canEdit" maxlength="1000" /></el-form-item>
                <el-form-item label="完整统计描述" required><el-input v-model="form.indicator_desc" type="textarea" :rows="3" :disabled="!canEdit" /></el-form-item>
                <div class="form-grid"><el-form-item label="指标类型" required><el-select v-model="form.indicator_type_code" :disabled="coreFrozen" style="width:100%"><el-option v-for="item in types" :key="item.type_code" :value="item.type_code" :label="item.type_name" /></el-select></el-form-item><el-form-item label="源指标单位" required><el-select v-model="form.unit_code" :disabled="coreFrozen" style="width:100%" @change="changeUnit"><el-option v-for="item in units" :key="item.unit_code" :value="item.unit_code" :label="`${item.unit_name}（${item.value_kind}）`" /></el-select></el-form-item><el-form-item label="标准单位"><el-select v-model="form.standard_unit_code" :disabled="coreFrozen" clearable placeholder="复合单位须人工确认" style="width:100%"><el-option v-for="item in units" :key="item.unit_code" :value="item.unit_code" :label="item.unit_name" /></el-select></el-form-item></div>
                <div class="form-grid"><el-form-item label="核算方式" required><el-select v-model="form.calc_method" :disabled="coreFrozen" placeholder="七类核算方式" style="width:100%"><el-option v-for="item in calcMethodOptions" :key="item.value" :value="item.value" :label="item.label"><span>{{ item.label }}</span><span class="option-note">{{ item.hint }}</span></el-option></el-select><div v-if="form.calc_method" class="form-tip">{{ calcMethodOptions.find(item => item.value === form.calc_method)?.hint }}</div></el-form-item><el-form-item label="目标方向"><el-select v-model="form.target_direction" :disabled="coreFrozen || valueKind === 'NONE'" style="width:100%"><el-option label="不少于／不低于（≥）" value="GE" /><el-option label="不超过／不高于（≤）" value="LE" /><el-option label="精确等于（=）" value="EQ" /></el-select></el-form-item><el-form-item label="显示小数位"><el-input-number v-model="form.decimal_precision" :disabled="!canEdit" :min="0" :max="4" /></el-form-item></div>
                <el-form-item label="统计口径" required><el-input v-model="form.statistical_rule" type="textarea" :rows="3" :disabled="coreFrozen" placeholder="明确统计范围、时点、增量／存量、是否允许补位等；待补口径不能显示为 0" /></el-form-item>
                <template v-if="['RATIO', 'GROWTH'].includes(form.calc_method || '')"><div class="required-tip">比率／增长率指标必须补充基期与分子分母口径；分母或基期为 0 时显示“无法自动核算”。</div><div class="form-grid two"><el-form-item label="分子来源口径"><el-input v-model="form.numerator_rule" type="textarea" :rows="2" :disabled="coreFrozen" /></el-form-item><el-form-item label="分母来源口径"><el-input v-model="form.denominator_rule" type="textarea" :rows="2" :disabled="coreFrozen" /></el-form-item></div></template>
                <div v-if="form.calc_method === 'GROWTH'" class="form-grid two"><el-form-item label="周期基期绝对值" required><el-input-number v-model="form.baseline_value" :disabled="coreFrozen" :controls="false" style="width:100%" /></el-form-item><el-form-item label="周期基期年度" required><el-select v-model="form.baseline_year" :disabled="coreFrozen" style="width:100%"><el-option v-for="year in years" :key="year" :value="year" :label="year" /></el-select></el-form-item></div>
                <el-form-item v-if="form.calc_method === 'EXTERNAL'" label="外部数据来源" required><el-input v-model="form.external_data_source" :disabled="coreFrozen" placeholder="如省厅统计系统；外部数据型不生成学院填报" /></el-form-item>
              </el-form>
            </div>
          </el-tab-pane>
          <el-tab-pane label="02 目标量化" name="targets">
            <div class="quant-tab-body">
              <el-alert v-if="plan.plan_status === 'DRAFT'" title="当前只需保存并确认目标，无需发布单个指标。全部指标完成后，请返回建设计划点击“发布计划”。" type="info" :closable="false" show-icon style="margin-bottom:18px" />
              <div class="section-heading"><h3 class="section-title">五年总目标</h3><span class="form-tip">空值＝未提供 · 0＝数值零 · —＝不适用</span></div>
              <div class="five-year-grid single"><div><label>专业群五年目标</label><TargetValueEditor v-model:value="form.professional_five_year_target_value" v-model:raw-value="form.professional_five_year_target_raw" v-model:text-value="form.professional_five_year_target_text" v-model:bool-value="form.professional_five_year_target_bool" :value-kind="valueKind" :unit="form.unit_raw" :disabled="coreFrozen" /></div></div>
              <el-collapse v-model="rawSections" class="source-collapse">
                <el-collapse-item title="查看源表原文（专业群 / 学校）" name="source">
                  <div class="five-year-grid source-grid"><div><label>专业群五年目标原文</label><el-input v-model="form.professional_five_year_target_raw" type="textarea" :rows="3" disabled /></div><div><label>学校五年目标原文</label><el-input v-model="form.school_five_year_target_raw" type="textarea" :rows="3" disabled /></div></div>
                </el-collapse-item>
              </el-collapse>
              <div class="section-heading annual-heading"><h3 class="section-title">{{ plan.start_year }}—{{ plan.end_year }} 年度目标</h3><div class="annual-tools"><span class="form-tip">每年选择“填写值 / 未提供 / 不适用”即可，空白不会转为 0</span><el-button v-if="canEdit" size="small" :disabled="!canAverage" @click="averageAllocate">平均分配</el-button></div></div>
              <AnnualTargetGrid v-model="annualTargets" :start-year="plan.start_year" :end-year="plan.end_year" :value-kind="valueKind" :unit="form.unit_raw" :disabled="!canEdit" show-confirm />
              <div v-if="canEdit" class="annual-confirm-action"><span>无需逐年勾选，一次确认量化内容和全部年度。</span><el-button type="primary" :loading="saving" @click="confirmAnnualTargets()">保存并确认量化</el-button></div>
            </div>
          </el-tab-pane>
          <el-tab-pane label="03 接收与下发" name="assignment" lazy>
            <div class="quant-tab-body">
              <IndicatorAssignmentPanel :indicator="indicator" :plan="plan" :can-assign="canEdit" @assigned="load" />
              <el-collapse v-model="responsibilitySections" class="responsibility-collapse">
                <el-collapse-item title="校级责任（非任务接收人）" name="school">
                  <div class="section-heading"><h3 class="section-title">校级牵头／协同单位</h3><el-button v-if="canEdit" link type="primary" @click="addDepartment"><el-icon><Plus /></el-icon>添加单位</el-button></div>
                  <el-alert title="教务处、双高办等仅作为指标牵头／协同单位，不是学院任务接收人，不会自动生成学院任务。" type="info" :closable="false" style="margin-bottom:12px" />
                  <div v-for="(department, index) in form.departments" :key="index" class="department-row"><el-select v-model="department.org_id" filterable placeholder="选择校级单位" :disabled="!canEdit" @focus="loadDepartments"><el-option v-for="org in schoolDepartments" :key="org.id" :value="org.id" :label="org.org_name" /></el-select><el-select v-model="department.relation_type" :disabled="!canEdit" style="width:115px"><el-option label="牵头" value="LEAD" /><el-option label="协同" value="COLLABORATOR" /></el-select><el-input v-model="department.responsibility_desc" :disabled="!canEdit" placeholder="责任说明／源文摘要" /><el-button v-if="canEdit" link type="danger" @click="form.departments?.splice(index, 1)">移除</el-button></div>
                  <el-empty v-if="!form.departments?.length" description="未配置校级牵头／协同单位" :image-size="55" />
                </el-collapse-item>
              </el-collapse>
            </div>
          </el-tab-pane>
        </el-tabs>
      </section>
    </template>
    <el-dialog v-model="externalDialog" title="录入外部数据认定" width="570px">
      <el-alert title="外部数据型由本项目校级负责人录入认定结果和来源，不生成学院填报；零分母或零基期仍不得自动核算。" type="warning" :closable="false" style="margin-bottom:15px" />
      <el-form label-width="110px"><el-form-item label="认定年度" required><el-select v-model="externalForm.target_year" style="width:100%"><el-option v-for="year in years" :key="year" :value="year" :label="year" /></el-select></el-form-item><el-form-item v-if="valueKind === 'BOOLEAN'" label="外部认定值"><el-radio-group v-model="externalForm.recognized_bool"><el-radio :value="1">是</el-radio><el-radio :value="0">否</el-radio></el-radio-group></el-form-item><el-form-item v-else-if="valueKind === 'RATIO' || valueKind === 'COMPOUND_DECIMAL'" label="外部认定文本"><el-input v-model="externalForm.recognized_text" /></el-form-item><el-form-item v-else label="外部认定值"><el-input-number v-model="externalForm.recognized_value" :controls="false" style="width:100%" /></el-form-item><el-form-item label="认定来源" required><el-input v-model="externalForm.recognition_source" /></el-form-item><el-form-item label="认定日期" required><el-date-picker v-model="externalForm.recognition_date" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item><el-form-item label="认定说明"><el-input v-model="externalForm.comment" type="textarea" :rows="3" /></el-form-item></el-form>
      <template #footer><el-button @click="externalDialog = false">取消</el-button><el-button type="primary" :loading="externalSaving" @click="saveExternalRecognition">保存外部认定</el-button></template>
    </el-dialog>
  </div>
</template>
<style scoped>
.quant-tabs :deep(.el-tabs__header){margin:0;padding:0 16px;background:#fafbfc;border-bottom:1px solid var(--line);border-radius:8px 8px 0 0}.quant-tabs :deep(.el-tabs__nav-wrap::after){display:none}.quant-tabs :deep(.el-tabs__item){height:52px;font-size:13px}.quant-tab-body{padding:20px}.section-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px}.section-heading .section-title{margin:0}.annual-heading{margin-top:20px}.annual-tools{display:flex;align-items:center;gap:12px}.annual-confirm-action{display:flex;align-items:center;justify-content:flex-end;gap:14px;margin-top:14px;color:#68768c;font-size:12px}.source-collapse{margin-top:18px;border-top:1px solid var(--line);border-bottom:1px solid var(--line)}.source-collapse :deep(.el-collapse-item__header){height:44px;color:#66758d;font-size:12px}.source-collapse :deep(.el-collapse-item__wrap){border-bottom:0}.source-grid{padding:4px 0 16px}.form-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.form-grid.two{grid-template-columns:1fr 1fr;margin-top:12px}.five-year-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px 24px}.five-year-grid.single{grid-template-columns:1fr}.five-year-grid label{display:block;margin-bottom:7px;color:#66758d;font-size:12px}.responsibility-collapse{margin-top:20px;border-top:1px solid var(--line);border-bottom:1px solid var(--line)}.responsibility-collapse :deep(.el-collapse-item__content){padding-bottom:18px}.department-row{display:grid;grid-template-columns:220px 115px minmax(0,1fr) 50px;gap:10px;margin-bottom:9px}@media(max-width:1400px){.form-grid{grid-template-columns:1fr 1fr}}@media(max-width:1200px){.five-year-grid{grid-template-columns:1fr}}
</style>
