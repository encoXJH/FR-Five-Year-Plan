<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Check, Clock, RefreshLeft } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage, executionApi } from '@/api'
import type { CollegeTask, ID, Indicator, Milestone, ReviewRecord, Submission } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { canSubmissionAction } from '@/utils/permissions'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import ValueDisplay from '@/components/ValueDisplay.vue'
import VersionTag from '@/components/VersionTag.vue'
import MaterialPanel from '@/components/MaterialPanel.vue'
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const submissionId = String(route.params.submissionId)
const current = ref<Submission>()
const displayed = ref<Submission>()
const task = ref<CollegeTask>()
const milestone = ref<Milestone>()
const indicator = ref<Indicator>()
const versions = ref<Submission[]>([])
const reviews = ref<ReviewRecord[]>([])
const loading = ref(true)
const error = ref('')
const reviewMode = ref<'APPROVED' | 'RETURNED' | 'CORRECT'>('APPROVED')
const reviewForm = ref<{ review_comment: string; recognized_value: number | string | null; recognized_bool: number | null; recognized_text: string | null; recognized_numerator: number | string | null; recognized_denominator: number | string | null; recognition_basis: string; correction_reason: string }>({ review_comment: '', recognized_value: null, recognized_bool: null, recognized_text: null, recognized_numerator: null, recognized_denominator: null, recognition_basis: '', correction_reason: '' })
const reviewing = ref(false)
const isSchoolStaff = computed(() => auth.roles.includes('SCHOOL_OWNER') || auth.roles.includes('ADMIN'))
const canReview = computed(() => current.value && canSubmissionAction(current.value, 'review', 'SCHOOL_OWNER') && isSchoolStaff.value)
const canCorrect = computed(() => current.value && canSubmissionAction(current.value, 'correct', 'SCHOOL_OWNER') && isSchoolStaff.value && reviews.value.length > 0)
function resetReview(mode: 'APPROVED' | 'RETURNED' | 'CORRECT' = 'APPROVED') {
  reviewMode.value = mode
  const source = current.value
  reviewForm.value = { review_comment: '', recognized_value: source?.reported_value ?? null, recognized_bool: source?.reported_bool === null || source?.reported_bool === undefined ? null : Number(source.reported_bool), recognized_text: source?.reported_text ?? null, recognized_numerator: source?.reported_numerator ?? null, recognized_denominator: source?.reported_denominator ?? null, recognition_basis: '', correction_reason: '' }
}
async function load(displayId?: ID) {
  loading.value = true; error.value = ''
  try {
    const data = await executionApi.submission(submissionId)
    current.value = data
    const [taskData, versionData, reviewData] = await Promise.all([executionApi.task(data.task_id || data.task?.id || ''), executionApi.milestoneSubmissions(data.milestone_id), executionApi.reviews(data.id)])
    task.value = taskData; versions.value = versionData.items.sort((a, b) => b.version_no - a.version_no); reviews.value = reviewData.items
    milestone.value = taskData.milestones?.find((item) => item.id === data.milestone_id)
    displayed.value = displayId && displayId !== data.id ? await executionApi.submission(displayId) : data
    if (data.indicator) indicator.value = data.indicator
    resetReview()
  } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
function normalizedDecimal(value: unknown) {
  if (value === null || value === undefined || value === '') return ''
  const text = String(value).trim()
  if (!/^[+-]?\d+(?:\.\d+)?$/.test(text)) return text
  const negative = text.startsWith('-')
  const unsigned = text.replace(/^[+-]/, '')
  const [whole, fraction = ''] = unsigned.split('.')
  const normalizedWhole = whole.replace(/^0+(?=\d)/, '')
  const normalizedFraction = fraction.replace(/0+$/, '')
  return `${negative ? '-' : ''}${normalizedWhole}${normalizedFraction ? `.${normalizedFraction}` : ''}`
}
function numericChanged(left: unknown, right: unknown) { return normalizedDecimal(left) !== normalizedDecimal(right) }
const recognizedDiffers = computed(() => {
  const source = current.value
  const boolChanged = (reviewForm.value.recognized_bool === null) !== (source?.reported_bool === null || source?.reported_bool === undefined) || (reviewForm.value.recognized_bool !== null && source?.reported_bool !== null && source?.reported_bool !== undefined && Number(reviewForm.value.recognized_bool) !== Number(source.reported_bool))
  return numericChanged(reviewForm.value.recognized_value, source?.reported_value) || String(reviewForm.value.recognized_text ?? '') !== String(source?.reported_text ?? '') || numericChanged(reviewForm.value.recognized_numerator, source?.reported_numerator) || numericChanged(reviewForm.value.recognized_denominator, source?.reported_denominator) || boolChanged
})
async function submitReview() {
  if (!current.value) return
  if (!reviewForm.value.review_comment.trim()) { ElMessage.warning('审核意见必填；退回须明确需修订内容'); return }
  if (reviewMode.value === 'APPROVED' && recognizedDiffers.value && !reviewForm.value.recognition_basis.trim()) { ElMessage.warning('认定值与学院填报值不同，必须填写认定依据'); return }
  if (reviewMode.value === 'CORRECT' && !reviewForm.value.correction_reason.trim()) { ElMessage.warning('审核更正原因必填'); return }
  const approved = reviewMode.value !== 'RETURNED'
  await ElMessageBox.confirm(`确认${reviewMode.value === 'RETURNED' ? '退回该填报并通知学院修订' : reviewMode.value === 'CORRECT' ? '新增审核更正记录（不修改原审核）' : '形成人工认定结论'}？提交后不可直接编辑原审核。`, '审核结论二次确认', { type: 'warning', confirmButtonText: '确认提交结论' })
  reviewing.value = true
  try {
    if (reviewMode.value === 'CORRECT') {
      const latest = reviews.value[0]
      await executionApi.correctReview(latest.id, { review_result: 'APPROVED', ...reviewForm.value, lock_version: latest.lock_version }, current.value.lock_version)
    } else {
      await executionApi.review(current.value.id, { review_result: approved ? 'APPROVED' : 'RETURNED', ...reviewForm.value, recognized_value: approved ? reviewForm.value.recognized_value : null, recognized_bool: approved ? reviewForm.value.recognized_bool : null, recognized_text: approved ? reviewForm.value.recognized_text : null, lock_version: current.value.lock_version }, current.value.lock_version)
    }
    ElMessage.success(reviewMode.value === 'RETURNED' ? '已退回学院修订' : '审核认定已保存并触发年度核算'); router.push('/execution/reviews')
  } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { reviewing.value = false }
}
onMounted(() => load())
</script>
<template>
  <div class="page-container" v-loading="loading">
    <PageHeader :title="`审核工作台 · ${current?.indicator_name || ''}`" :description="current ? `${current.plan_name || ''} · ${current.college_org_name} · ${current.target_year} 年 · ${current.task_code}` : '读取审核上下文'"><template #actions><el-button v-if="canCorrect" type="warning" @click="resetReview('CORRECT')"><el-icon><RefreshLeft /></el-icon>发起审核更正</el-button></template></PageHeader>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"><el-button link @click="load()">重新加载</el-button></el-alert>
    <template v-if="current && displayed && task">
      <div class="review-layout">
        <div>
          <section class="panel"><div class="panel-header"><h3 class="panel-title">目标与统计口径</h3><div><StatusTag :status="milestone?.milestone_status" /><StatusTag :status="displayed.submission_status" style="margin-left:5px" /></div></div><div class="panel-body"><dl class="description-grid"><div class="description-item"><dt>年度</dt><dd>{{ current.target_year }}</dd></div><div class="description-item"><dt>原年度目标</dt><dd><ValueDisplay :value="milestone?.original_target_value" :text="milestone?.original_target_text" :bool-value="milestone?.original_target_bool" :value-kind="task.value_kind" :unit="task.unit_name || task.unit_code" /></dd></div><div class="description-item"><dt>当前执行目标</dt><dd><ValueDisplay :value="milestone?.current_target_value" :text="milestone?.current_target_text" :bool-value="milestone?.current_target_bool" :value-kind="task.value_kind" :unit="task.unit_name || task.unit_code" /><VersionTag v-if="milestone" :version="milestone.target_version_no" style="margin-left:5px" /></dd></div><div class="description-item"><dt>核算方式</dt><dd><StatusTag :status="task.calc_method" /></dd></div><div class="description-item span-4"><dt>指标完整口径</dt><dd class="long-text">{{ task.statistical_rule || indicator?.statistical_rule || '待补口径，不能自动按 0 或已完成处理' }}</dd></div></dl></div></section>
          <section class="panel"><div class="panel-header"><h3 class="panel-title">学院填报</h3><div><VersionTag :version="displayed.version_no" :current="displayed.is_current" /><span v-if="displayed.id !== current.id" class="warning-text small" style="margin-left:8px">正在查看历史只读版本</span></div></div><div class="panel-body"><dl class="description-grid"><div class="description-item"><dt>学院填报值</dt><dd><ValueDisplay :value="displayed.reported_value" :text="displayed.reported_text" :bool-value="displayed.reported_bool" :value-kind="task.value_kind" :unit="task.unit_name || task.unit_code" /></dd></div><div class="description-item"><dt>成果名称</dt><dd>{{ displayed.achievement_name || '未提供' }}</dd></div><div class="description-item"><dt>完成日期</dt><dd>{{ displayed.completion_date }}</dd></div><div class="description-item"><dt>提交时间</dt><dd>{{ formatDateTime(displayed.submitted_at) }}</dd></div><div v-if="task.calc_method === 'RATIO'" class="description-item span-2"><dt>分子／分母</dt><dd>{{ displayed.reported_numerator ?? '未提供' }} / {{ displayed.reported_denominator ?? '未提供' }}</dd></div><div class="description-item span-4"><dt>完成说明</dt><dd class="long-text">{{ displayed.completion_desc }}</dd></div><div v-if="displayed.withdraw_reason" class="description-item span-4"><dt>撤回原因</dt><dd class="warning-text">{{ displayed.withdraw_reason }}</dd></div></dl></div></section>
          <section class="panel"><div class="panel-header"><h3 class="panel-title">版本佐证材料</h3><span class="form-tip">材料归属于 V{{ displayed.version_no }}，审核无需下载</span></div><div class="panel-body"><MaterialPanel :submission-id="displayed.id" :materials="displayed.materials || []" readonly /></div></section>
          <section class="panel"><div class="panel-header"><h3 class="panel-title">历史版本与审核记录</h3></div><div class="panel-body history-grid"><div><h4 class="section-title">填报版本</h4><div class="review-versions"><button v-for="version in versions" :key="version.id" :class="{ active: displayed.id === version.id }" @click="load(version.id)"><VersionTag :version="version.version_no" :current="version.is_current" /><StatusTag :status="version.submission_status" /><span>{{ formatDateTime(version.submitted_at || version.created_at) }}</span></button></div></div><div><h4 class="section-title">审核结论</h4><el-timeline v-if="reviews.length"><el-timeline-item v-for="review in reviews" :key="review.id" :timestamp="formatDateTime(review.reviewed_at)" placement="top"><StatusTag :status="review.review_result" /><span class="small" style="margin-left:7px">{{ review.reviewer_name || review.reviewer_id }}</span><div class="long-text small" style="margin-top:5px">{{ review.review_comment }}</div><div v-if="review.recognition_basis" class="subtle small">认定依据：{{ review.recognition_basis }}</div><div v-if="review.correction_reason" class="warning-text small">更正原因：{{ review.correction_reason }}</div></el-timeline-item></el-timeline><el-empty v-else description="尚无审核结论" :image-size="55" /></div></div></section>
        </div>
        <aside class="panel sticky-panel"><div class="panel-header"><h3 class="panel-title">人工审核面板</h3></div><div class="panel-body">
          <template v-if="canReview || reviewMode === 'CORRECT'"><el-radio-group v-if="canReview" v-model="reviewMode" style="width:100%;margin-bottom:16px"><el-radio-button value="APPROVED">通过认定</el-radio-button><el-radio-button value="RETURNED">退回更正</el-radio-button></el-radio-group><el-alert v-if="reviewMode === 'CORRECT'" title="审核更正新增记录，不覆盖原审核与原填报。" type="warning" :closable="false" style="margin-bottom:14px" />
            <el-form label-position="top"><template v-if="reviewMode !== 'RETURNED'"><el-form-item v-if="task.calc_method === 'BOOLEAN'" label="认定完成值" required><el-radio-group v-model="reviewForm.recognized_bool"><el-radio :value="1">是</el-radio><el-radio :value="0">否</el-radio></el-radio-group></el-form-item><template v-else-if="task.calc_method === 'RATIO'"><el-form-item label="认定分子值"><el-input-number v-model="reviewForm.recognized_numerator" :controls="false" style="width:100%" /></el-form-item><el-form-item label="认定分母值"><el-input-number v-model="reviewForm.recognized_denominator" :controls="false" style="width:100%" /></el-form-item><el-form-item label="认定比值文本"><el-input v-model="reviewForm.recognized_text" /></el-form-item></template><el-form-item v-else :label="task.calc_method === 'GROWTH' ? '认定年度绝对值' : '认定完成值'" required><el-input-number v-if="task.value_kind !== 'COMPOUND_DECIMAL'" v-model="reviewForm.recognized_value" :controls="false" style="width:100%" /><el-input v-else v-model="reviewForm.recognized_text" /></el-form-item><el-form-item v-if="recognizedDiffers" label="认定依据（值不同必填）" required><el-input v-model="reviewForm.recognition_basis" type="textarea" :rows="3" /></el-form-item></template><el-form-item :label="reviewMode === 'RETURNED' ? '退回意见／具体修订要求' : '审核意见'" required><el-input v-model="reviewForm.review_comment" type="textarea" :rows="5" maxlength="2000" show-word-limit /></el-form-item><el-form-item v-if="reviewMode === 'CORRECT'" label="更正原因" required><el-input v-model="reviewForm.correction_reason" type="textarea" :rows="3" /></el-form-item></el-form>
            <div class="review-note"><el-icon><Clock /></el-icon><span>仅认定值计入正式完成量；通过后系统比较目标形成“已完成”或“已认定未达标”。</span></div><el-button type="primary" style="width:100%" :loading="reviewing" @click="submitReview"><el-icon><Check /></el-icon>{{ reviewMode === 'RETURNED' ? '确认退回' : reviewMode === 'CORRECT' ? '提交更正记录' : '提交认定结论' }}</el-button>
          </template><el-result v-else icon="info" title="当前无需审核" sub-title="只有审核中的填报可由本项目校级负责人形成结论；校领导仅查看审核结果。" />
        </div></aside>
      </div>
    </template>
  </div>
</template>
<style scoped>.history-grid{display:grid;grid-template-columns:260px minmax(0,1fr);gap:22px}.review-versions{display:flex;flex-direction:column;gap:6px}.review-versions button{padding:8px;display:flex;align-items:center;gap:6px;text-align:left;border:1px solid #e5eaf1;border-radius:5px;background:#fff;cursor:pointer}.review-versions button.active{border-color:#8da8e5;background:#f5f8ff}.review-versions button>span:last-child{margin-left:auto;color:#8a96a8;font-size:10px}.review-note{display:flex;gap:7px;margin:10px 0 16px;color:#7f8da2;font-size:11px;line-height:1.6}.review-note .el-icon{margin-top:2px}</style>
