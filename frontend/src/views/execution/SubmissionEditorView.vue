<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Check, Clock } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage, executionApi } from '@/api'
import type { CollegeTask, ID, Milestone, Submission } from '@/types/api'
import { canSubmissionAction } from '@/utils/permissions'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import ValueDisplay from '@/components/ValueDisplay.vue'
import VersionTag from '@/components/VersionTag.vue'
import MaterialPanel from '@/components/MaterialPanel.vue'
const route = useRoute()
const milestoneId = String(route.params.milestoneId)
const milestone = ref<Milestone>()
const task = ref<CollegeTask>()
const versions = ref<Submission[]>([])
const submission = ref<Submission>()
const form = ref<Partial<Submission>>({})
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const withdrawDialog = ref(false)
const withdrawReason = ref('')
const editable = computed(() => !submission.value || canSubmissionAction(submission.value, 'edit', 'COLLEGE_OWNER'))
const canRevise = computed(() => submission.value && canSubmissionAction(submission.value, 'revise', 'COLLEGE_OWNER'))
const canWithdraw = computed(() => submission.value && canSubmissionAction(submission.value, 'withdraw', 'COLLEGE_OWNER'))
function blankForm(): Partial<Submission> { return { milestone_id: milestoneId, reported_value: null, reported_bool: null, reported_text: null, reported_numerator: null, reported_denominator: null, completion_desc: '', achievement_name: '', completion_date: '' } }
function setSubmission(value?: Submission) { submission.value = value; form.value = value ? JSON.parse(JSON.stringify(value)) as Submission : blankForm() }
async function load(selectId?: ID) {
  loading.value = true; error.value = ''
  try {
    const detail = await executionApi.milestone(milestoneId)
    milestone.value = detail
    const [taskData, versionData] = await Promise.all([executionApi.task(detail.task_id), executionApi.milestoneSubmissions(milestoneId)])
    task.value = taskData; versions.value = versionData.items.sort((a, b) => b.version_no - a.version_no)
    const selected = selectId ? versionData.items.find((item) => item.id === selectId) : versionData.items.find((item) => item.is_current) || versionData.items[0]
    setSubmission(selected ? await executionApi.submission(selected.id) : undefined)
  } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
async function save(showMessage = true) {
  if (!form.value.completion_desc?.trim() || !form.value.completion_date) { ElMessage.warning('完成说明和完成日期为必填项，填写后方可保存草稿'); throw new Error('完成说明和完成日期必填') }
  saving.value = true
  try {
    const result = await executionApi.saveDraft(milestoneId, { ...form.value, id: undefined, materials: undefined, task: undefined, milestone: undefined, indicator: undefined }, submission.value?.lock_version, submission.value?.id)
    setSubmission(await executionApi.submission(result.id))
    if (showMessage) ElMessage.success('文字草稿已保存；材料上传状态单独显示')
    return result
  } catch (cause) { ElMessage.error(errorMessage(cause)); throw cause } finally { saving.value = false }
}
function validateSubmit(value: Submission) {
  if (!form.value.completion_desc?.trim() || !form.value.completion_date) { ElMessage.warning('完成说明和完成日期为必填项'); return false }
  if (task.value?.calc_method === 'BOOLEAN' && (form.value.reported_bool === null || form.value.reported_bool === undefined)) { ElMessage.warning('是否型指标必须明确填报“是”或“否”'); return false }
  if (task.value?.calc_method === 'RATIO' && (form.value.reported_numerator === null || form.value.reported_numerator === undefined || form.value.reported_denominator === null || form.value.reported_denominator === undefined || Number(form.value.reported_denominator) === 0)) { ElMessage.warning('比率型必须填写分子、分母，且分母不可为 0'); return false }
  if (task.value?.value_kind === 'COMPOUND_DECIMAL' && !form.value.reported_text?.trim()) { ElMessage.warning('请按复合单位口径填写完成值文本'); return false }
  if (task.value?.value_kind !== 'BOOLEAN' && task.value?.value_kind !== 'RATIO' && task.value?.value_kind !== 'COMPOUND_DECIMAL' && (form.value.reported_value === null || form.value.reported_value === undefined)) { ElMessage.warning('请填写完成值，空值不能作为完成结果提交'); return false }
  const ready = value.materials?.filter((item) => item.preview_status === 'READY') || []
  if (!ready.length) { ElMessage.warning('至少需要 1 项在线可预览的有效佐证材料'); return false }
  return true
}
async function submit() {
  saving.value = true
  try {
    const saved = await save(false)
    const current = await executionApi.submission(saved.id)
    if (!validateSubmit(current)) return
    await ElMessageBox.confirm(`提交后将生成不可变填报版本 V${current.version_no}，里程碑进入审核中。确认提交？`, '提交年度填报', { type: 'warning', confirmButtonText: '确认提交' })
    await executionApi.submitSubmission(current.id, current.lock_version)
    ElMessage.success('填报已提交，等待校级人工审核'); await load(current.id)
  } catch (cause) { if (cause !== 'cancel' && cause instanceof Error && cause.message.includes('必填')) return; if (cause !== 'cancel' && cause && typeof cause === 'object' && 'response' in cause) ElMessage.error(errorMessage(cause)) } finally { saving.value = false }
}
async function revise() {
  if (!submission.value) return
  try { const result = await executionApi.reviseSubmission(submission.value.id); ElMessage.success('已基于原填写内容生成新草稿，旧版本与材料保持只读'); await load(result.id) } catch (cause) { ElMessage.error(errorMessage(cause)) }
}
async function withdraw() {
  if (!submission.value || !withdrawReason.value.trim()) { ElMessage.warning('撤回原因必填'); return }
  try { await executionApi.withdrawSubmission(submission.value.id, withdrawReason.value, submission.value.lock_version); ElMessage.success('已撤回，原审核中版本留痕；可生成新草稿修订'); withdrawDialog.value = false; await load() } catch (cause) { ElMessage.error(errorMessage(cause)) }
}
function disableFuture(date: Date) { return date.getTime() > Date.now() }
onMounted(() => load())
</script>
<template>
  <div class="page-container" v-loading="loading">
    <PageHeader :title="`${milestone?.target_year || ''} 年度填报 · ${task?.indicator_name || ''}`" :description="task ? `${task.task_code} · ${task.college_org_name} · ${task.unit_name || task.unit_code}` : '读取年度里程碑与填报版本'"><template #actions><el-button v-if="canRevise" type="primary" @click="revise">生成修订草稿</el-button><el-button v-if="canWithdraw" type="warning" @click="withdrawReason = ''; withdrawDialog = true">撤回审核</el-button><el-button v-if="editable" :loading="saving" @click="save()">保存草稿</el-button><el-button v-if="editable && submission" type="primary" :loading="saving" @click="submit"><el-icon><Check /></el-icon>提交审核</el-button></template></PageHeader>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"><el-button link @click="load()">重新加载</el-button></el-alert>
    <template v-if="milestone && task">
      <div class="report-layout">
        <div>
          <section class="panel"><div class="panel-header"><h3 class="panel-title">年度目标与当前版本</h3><div><VersionTag v-if="submission" :version="submission.version_no" :current="submission.is_current" /><StatusTag v-if="submission" :status="submission.submission_status" style="margin-left:6px" /><StatusTag v-else status="DRAFT" /></div></div><div class="panel-body"><dl class="description-grid"><div class="description-item"><dt>原年度目标</dt><dd><ValueDisplay :value="milestone.original_target_value" :text="milestone.original_target_text" :bool-value="milestone.original_target_bool" :value-kind="task.value_kind" :unit="task.unit_name || task.unit_code" /></dd></div><div class="description-item"><dt>当前执行目标</dt><dd><ValueDisplay :value="milestone.current_target_value" :text="milestone.current_target_text" :bool-value="milestone.current_target_bool" :value-kind="task.value_kind" :unit="task.unit_name || task.unit_code" /></dd></div><div class="description-item"><dt>最近认定值</dt><dd><ValueDisplay :value="milestone.recognized_value" :text="milestone.recognized_text" :bool-value="milestone.recognized_bool" :value-kind="task.value_kind" :unit="task.unit_name || task.unit_code" empty-text="尚未认定" /></dd></div><div class="description-item"><dt>年度状态</dt><dd><StatusTag :status="milestone.milestone_status" /></dd></div><div class="description-item span-4"><dt>统计口径</dt><dd class="long-text">{{ task.statistical_rule || '待补口径' }}</dd></div></dl></div></section>
          <section class="panel"><div class="panel-header"><h3 class="panel-title">完成结果</h3><span v-if="!editable" class="form-tip">当前版本只读；退回或撤回后生成新草稿修订</span></div><div class="panel-body"><el-form :model="form" label-width="110px" :disabled="!editable">
            <el-form-item v-if="task.calc_method === 'BOOLEAN'" label="完成值" required><el-radio-group v-model="form.reported_bool"><el-radio :value="1">是</el-radio><el-radio :value="0">否</el-radio></el-radio-group></el-form-item>
            <template v-else-if="task.calc_method === 'RATIO'"><div class="form-pair"><el-form-item label="填报分子值" required><el-input-number v-model="form.reported_numerator" :controls="false" style="width:100%" /></el-form-item><el-form-item label="填报分母值" required><el-input-number v-model="form.reported_denominator" :controls="false" style="width:100%" /></el-form-item></div><el-form-item label="比值／完成值"><el-input v-model="form.reported_text" placeholder="如 1:350；自动比率由分子分母计算" /></el-form-item></template>
            <el-form-item v-else-if="task.value_kind === 'COMPOUND_DECIMAL'" label="完成值文本" required><el-input v-model="form.reported_text" placeholder="按已确认的复合单位口径填写" /></el-form-item>
            <el-form-item v-else :label="task.calc_method === 'GROWTH' ? '本年度绝对值' : '完成值'" required><el-input-number v-model="form.reported_value" :controls="false" style="width:260px" /><span class="form-tip" style="margin-left:10px">{{ task.unit_name || task.unit_code }}</span></el-form-item>
            <el-form-item label="成果名称"><el-input v-model="form.achievement_name" maxlength="500" /></el-form-item><el-form-item label="完成日期" required><el-date-picker v-model="form.completion_date" type="date" value-format="YYYY-MM-DD" :disabled-date="disableFuture" placeholder="不得晚于提交日期" /></el-form-item><el-form-item label="完成说明" required><el-input v-model="form.completion_desc" type="textarea" :rows="6" maxlength="5000" show-word-limit placeholder="说明完成过程、结果、统计范围与年度增量" /></el-form-item>
          </el-form></div></section>
          <section class="panel"><div class="panel-header"><h3 class="panel-title">佐证材料</h3><span class="form-tip">至少 1 项 READY 材料方可提交；审核中版本材料不可删除</span></div><div class="panel-body"><MaterialPanel v-if="submission" :submission-id="submission.id" :materials="submission.materials || []" :readonly="!editable" @changed="load(submission.id)" /><el-empty v-else description="请先保存文字草稿，再上传佐证材料" :image-size="75"><el-button type="primary" :loading="saving" @click="save()">保存草稿并上传</el-button></el-empty></div></section>
        </div>
        <aside class="panel sticky-panel"><div class="panel-header"><h3 class="panel-title">填报版本</h3><span class="panel-subtitle">{{ versions.length }} 个</span></div><div class="version-list"><button v-for="version in versions" :key="version.id" :class="{ active: submission?.id === version.id }" @click="load(version.id)"><div><VersionTag :version="version.version_no" :current="version.is_current" /><StatusTag :status="version.submission_status" /></div><strong>{{ version.achievement_name || '未命名成果' }}</strong><small>{{ version.submitted_at ? `提交 ${formatDateTime(version.submitted_at)}` : '尚未提交审核' }}</small><span>填报 <ValueDisplay :value="version.reported_value" :text="version.reported_text" :bool-value="version.reported_bool" :value-kind="task.value_kind" :unit="task.unit_name || task.unit_code" /></span></button><el-empty v-if="!versions.length" description="尚无填报版本" :image-size="65" /></div><div class="version-rule"><el-icon><Clock /></el-icon><p>每次提交生成独立版本。退回不覆盖旧版本；草稿、审核中和已退回版本均不计入正式完成量。</p></div></aside>
      </div>
    </template>
    <el-dialog v-model="withdrawDialog" title="撤回审核中填报" width="520px"><el-alert title="仅尚未形成审核结论时可撤回。撤回动作保留原因和日志，原版本不可直接覆盖。" type="warning" :closable="false" style="margin-bottom:14px" /><el-input v-model="withdrawReason" type="textarea" :rows="4" placeholder="请填写撤回原因（必填）" /><template #footer><el-button @click="withdrawDialog = false">取消</el-button><el-button type="warning" @click="withdraw">确认撤回</el-button></template></el-dialog>
  </div>
</template>
<style scoped>.report-layout{display:grid;grid-template-columns:minmax(0,1fr) 285px;gap:14px;align-items:start}.form-pair{display:grid;grid-template-columns:1fr 1fr;gap:12px}.version-list{padding:8px}.version-list button{width:100%;padding:11px;margin-bottom:7px;text-align:left;border:1px solid #e6eaf1;border-radius:6px;background:#fff;cursor:pointer}.version-list button.active{border-color:#87a5e7;background:#f5f8ff}.version-list button>div{display:flex;justify-content:space-between}.version-list strong,.version-list small,.version-list>button>span{display:block}.version-list strong{margin-top:8px;font-size:12px}.version-list small{margin:4px 0;color:#8e99ab;font-size:10px}.version-rule{display:flex;gap:8px;margin:10px;padding:12px;color:#7d8da7;background:#f5f7fb;border-radius:5px;font-size:11px;line-height:1.7}.version-rule p{margin:0}.version-rule .el-icon{margin-top:3px}</style>
