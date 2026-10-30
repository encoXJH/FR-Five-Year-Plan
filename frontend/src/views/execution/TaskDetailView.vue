<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorMessage, executionApi } from '@/api'
import type { CollegeTask, Milestone } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { canTaskAction } from '@/utils/permissions'
import { formatDate, formatDateTime, remainingGap } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import ValueDisplay from '@/components/ValueDisplay.vue'
import VersionTag from '@/components/VersionTag.vue'
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const id = String(route.params.id)
const task = ref<CollegeTask>()
const loading = ref(true)
const error = ref('')
const role = computed(() => auth.roles.includes('COLLEGE_OWNER') ? 'COLLEGE_OWNER' : auth.roles.includes('SCHOOL_OWNER') ? 'SCHOOL_OWNER' : 'SCHOOL_LEADER')
const actionDialog = ref(false)
const actionType = ref<'objection' | 'reply'>('objection')
const actionForm = reactive({ content: '' })
const acting = ref(false)
async function load() { loading.value = true; error.value = ''; try { task.value = await executionApi.task(id) } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
async function receive() { if (!task.value) return; await ElMessageBox.confirm('请确认已阅读指标目标、年度里程碑、统计口径和校级说明。接收后不可由校级直接撤销。', '接收学院任务', { type: 'warning' }); try { await executionApi.receiveTask(id, task.value.lock_version); ElMessage.success('任务已接收'); await load() } catch (cause) { ElMessage.error(errorMessage(cause)) } }
async function revoke() { if (!task.value) return; const result = await ElMessageBox.prompt('请填写撤销原因。仅待接收任务可撤销。', '撤销任务下发', { inputValidator: (value) => Boolean(value?.trim()) || '原因必填', type: 'warning' }); try { await executionApi.revokeTask(id, result.value, task.value.lock_version); ElMessage.success('已撤销下发'); await load() } catch (cause) { ElMessage.error(errorMessage(cause)) } }
function openAction(type: 'objection' | 'reply') { actionType.value = type; actionForm.content = ''; actionDialog.value = true }
async function submitAction() {
  if (!actionForm.content.trim()) { ElMessage.warning('请填写具体内容'); return }
  acting.value = true
  try { if (actionType.value === 'objection') await executionApi.submitObjection(id, actionForm.content); else await executionApi.replyObjection(task.value?.objection_id || '', actionForm.content, task.value?.objection_lock_version); ElMessage.success(actionType.value === 'objection' ? '异议已提交，目标未被修改' : '异议答复已保存'); actionDialog.value = false; await load() } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { acting.value = false }
}
function openMilestone(milestone: Milestone) { if (role.value === 'COLLEGE_OWNER') router.push(`/execution/milestones/${milestone.id}/report`); else if (milestone.current_submission_id) router.push(`/execution/reviews/${milestone.current_submission_id}`); else ElMessage.info('该年度尚未形成填报版本') }
function startCarryover(milestone: Milestone) { router.push({ path: '/adjustments/carryovers', query: { create: '1', task_id: id, source_milestone_id: String(milestone.id), source_year: String(milestone.target_year) } }) }
onMounted(load)
</script>
<template>
  <div class="page-container" v-loading="loading">
    <PageHeader :title="task?.indicator_name || '任务执行详情'" :description="task ? `${task.task_code} · ${task.plan_name || ''} · 目标版本 V${task.current_target_version_no}` : '读取任务信息'"><template #actions><el-button v-if="task && canTaskAction(task, 'objection', role)" @click="openAction('objection')">提交口径异议</el-button><el-button v-if="task && role === 'SCHOOL_OWNER' && task.objection_content && !task.objection_reply" @click="openAction('reply')">答复异议</el-button><el-button v-if="task && canTaskAction(task, 'revoke', role)" type="warning" @click="revoke">撤销下发</el-button><el-button v-if="task && canTaskAction(task, 'receive', role)" type="primary" @click="receive">接收任务</el-button></template></PageHeader>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"><el-button link @click="load">重新加载</el-button></el-alert>
    <template v-if="task">
      <section class="panel"><div class="panel-body"><div class="inline-meta" style="margin-bottom:16px"><StatusTag :status="task.task_status" /><span>执行学院：<strong>{{ task.college_org_name }}</strong></span><span>负责人：{{ task.owner_user_name }}</span><span>下发：{{ formatDateTime(task.assigned_at) }}</span><span>接收：{{ formatDateTime(task.received_at) }}</span></div><dl class="description-grid"><div class="description-item"><dt>本学院五年任务目标</dt><dd><ValueDisplay :value="task.five_year_target_value" :text="task.five_year_target_text" :bool-value="task.five_year_target_bool" :value-kind="task.value_kind" :unit="task.unit_name || task.unit_code" /></dd></div><div class="description-item"><dt>指标类型／核算方式</dt><dd><StatusTag :status="task.indicator_type_code" /> <StatusTag :status="task.calc_method" /></dd></div><div class="description-item"><dt>执行范围</dt><dd>{{ task.execution_scope || '未单独限定' }}</dd></div><div class="description-item"><dt>指标单位</dt><dd>{{ task.unit_name || task.unit_raw || task.unit_code }}</dd></div><div class="description-item span-4"><dt>统计口径与校级执行说明</dt><dd class="long-text">{{ task.statistical_rule || '待补口径，请联系校级负责人，不能按 0 处理' }}</dd></div></dl></div></section>
      <section v-if="task.objection_content" class="panel"><div class="panel-header"><h3 class="panel-title">任务口径异议</h3><StatusTag :status="task.objection_status || (task.objection_reply ? 'CONFIRMED' : 'PENDING')" /></div><div class="panel-body"><div class="objection-block"><label>学院异议</label><p>{{ task.objection_content }}</p></div><div v-if="task.objection_reply" class="objection-block reply"><label>校级答复</label><p>{{ task.objection_reply }}</p></div><el-alert v-else title="校级处理前可继续查看，学院不能直接修改目标。" type="warning" :closable="false" /></div></section>
      <section class="panel"><div class="panel-header"><h3 class="panel-title">年度里程碑</h3><span class="form-tip">上传材料不等于完成，只有校级审核认定才更新正式完成值</span></div><el-table :data="task.milestones || []" row-key="id"><el-table-column prop="target_year" label="年度" width="75" align="center" /><el-table-column label="原目标 → 当前目标" min-width="190"><template #default="{ row }"><ValueDisplay :value="row.original_target_value" :text="row.original_target_text" :bool-value="row.original_target_bool" :value-kind="task.value_kind" :unit="task.unit_name || task.unit_code" /><span v-if="row.has_adjustment" class="primary-text" style="margin:0 6px">→</span><ValueDisplay v-if="row.has_adjustment" :value="row.current_target_value" :text="row.current_target_text" :bool-value="row.current_target_bool" :value-kind="task.value_kind" :unit="task.unit_name || task.unit_code" /><VersionTag :version="row.target_version_no" style="margin-left:6px" /></template></el-table-column><el-table-column label="认定完成值" min-width="130"><template #default="{ row }"><ValueDisplay :value="row.recognized_value" :text="row.recognized_text" :bool-value="row.recognized_bool" :value-kind="task.value_kind" :unit="task.unit_name || task.unit_code" empty-text="尚未认定" /></template></el-table-column><el-table-column label="缺口／补位" width="120"><template #default="{ row }"><span v-if="remainingGap(row.current_target_value, row.recognized_value, row.covered_value) !== null">缺口 {{ remainingGap(row.current_target_value, row.recognized_value, row.covered_value) }}<div class="success-text small">已补位 {{ row.covered_value ?? 0 }}</div></span><span v-else class="value-empty">不适用</span></template></el-table-column><el-table-column label="状态" width="130"><template #default="{ row }"><StatusTag :status="row.milestone_status" /><el-tag v-if="row.has_adjustment" size="small" type="warning" style="margin-left:4px">已调整</el-tag></template></el-table-column><el-table-column label="截止日期" width="110"><template #default="{ row }">{{ formatDate(row.due_date) }}</template></el-table-column><el-table-column label="操作" width="155" fixed="right"><template #default="{ row }"><el-button v-if="task.task_status === 'IN_PROGRESS' && (role === 'COLLEGE_OWNER' || row.current_submission_id)" link type="primary" @click="openMilestone(row)">{{ role === 'COLLEGE_OWNER' ? (row.milestone_status === 'UNDER_REVIEW' ? '查看／撤回' : '填报／版本') : '查看填报' }}</el-button><el-button v-if="role === 'SCHOOL_OWNER' && task.calc_method === 'CUMULATIVE' && ['RECOGNIZED_UNMET','COMPLETED'].includes(row.milestone_status)" link type="warning" @click="startCarryover(row)">发起顺延</el-button></template></el-table-column></el-table></section>
    </template>
    <el-dialog v-model="actionDialog" :title="actionType === 'objection' ? '提交任务口径异议' : '答复学院异议'" width="560px"><el-alert :title="actionType === 'objection' ? '异议不会直接修改学院目标，请明确说明统计范围、目标或执行说明中的问题。' : '答复保留在任务记录中，若需改变目标请走调整流程。'" type="info" :closable="false" style="margin-bottom:15px" /><el-input v-model="actionForm.content" type="textarea" :rows="5" maxlength="2000" show-word-limit :placeholder="actionType === 'objection' ? '异议说明（必填）' : '答复内容（必填）'" /><template #footer><el-button @click="actionDialog = false">取消</el-button><el-button type="primary" :loading="acting" @click="submitAction">确认提交</el-button></template></el-dialog>
  </div>
</template>
<style scoped>.objection-block{padding:12px;background:#fff8eb;border-radius:6px}.objection-block.reply{margin-top:10px;background:#f2f7ff}.objection-block label{color:#8b7958;font-size:11px}.objection-block.reply label{color:#657b9f}.objection-block p{margin:6px 0 0;font-size:12px;line-height:1.7;white-space:pre-wrap}</style>
