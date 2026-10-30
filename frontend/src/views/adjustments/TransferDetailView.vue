<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Check } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adjustmentApi, errorMessage, transferAttachmentUrl } from '@/api'
import type { Transfer, TransferApproval, TransferAttachment } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
const route = useRoute()
const auth = useAuthStore()
const id = String(route.params.id)
const transfer = ref<Transfer>()
const loading = ref(true)
const error = ref('')
const acting = ref(false)
const comment = ref('')
const isAdmin = computed(() => auth.roles.includes('ADMIN'))
const currentAction = computed<'CONFIRM' | 'APPROVE' | null>(() => {
  if (!transfer.value) return null
  const status = transfer.value.transfer_status
  if (status === 'PENDING_OUT_CONFIRM' && (isAdmin.value || (auth.roles.includes('COLLEGE_OWNER') && auth.user?.primary_org_id === transfer.value.source_college_org_id))) return 'CONFIRM'
  if (status === 'PENDING_IN_CONFIRM' && (isAdmin.value || (auth.roles.includes('COLLEGE_OWNER') && auth.user?.primary_org_id === transfer.value.target_college_org_id))) return 'CONFIRM'
  if (status === 'PENDING_LEADER_APPROVAL' && (isAdmin.value || auth.roles.includes('SCHOOL_LEADER'))) return 'APPROVE'
  return null
})
const canWithdraw = computed(() => transfer.value && (auth.roles.includes('SCHOOL_OWNER') || isAdmin.value) && (isAdmin.value || transfer.value.initiated_by === auth.user?.id) && ['PENDING_OUT_CONFIRM', 'PENDING_IN_CONFIRM', 'PENDING_LEADER_APPROVAL'].includes(transfer.value.transfer_status))
const steps = computed(() => [
  { code: 'OUT_COLLEGE', title: '转出学院确认', description: transfer.value?.source_college_org_name },
  { code: 'IN_COLLEGE', title: '转入学院确认', description: transfer.value?.target_college_org_name },
  { code: 'SCHOOL_LEADER', title: '校领导最终审批', description: '批准后双方目标生效' },
])
function approval(code: string): TransferApproval | undefined { return transfer.value?.approvals?.find((item) => item.step_code === code) }
function stepStatus(code: string) { const record = approval(code); if (record) return record.step_status; const order = ['PENDING_OUT_CONFIRM', 'PENDING_IN_CONFIRM', 'PENDING_LEADER_APPROVAL']; const currentIndex = order.indexOf(transfer.value?.transfer_status || ''); const stepIndex = steps.value.findIndex((item) => item.code === code); return currentIndex === stepIndex ? 'PENDING' : currentIndex > stepIndex ? 'CONFIRMED' : 'NOT_STARTED' }
async function load() { loading.value = true; error.value = ''; try { transfer.value = await adjustmentApi.transfer(id) } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
async function act(action: 'CONFIRM' | 'APPROVE' | 'RETURN') {
  if (!transfer.value) return
  if (action === 'RETURN' && !comment.value.trim()) { ElMessage.warning('退回必须填写具体意见'); return }
  const label = action === 'RETURN' ? '退回并终止' : action === 'APPROVE' ? '最终批准并生效' : '确认并流转下一环节'
  await ElMessageBox.confirm(`确认${label}？${action === 'APPROVE' ? '终审将事务内重查占用量并更新双方目标。' : ''}`, '划转操作二次确认', { type: 'warning' })
  acting.value = true
  try { await adjustmentApi.actTransfer(id, action, comment.value, transfer.value.lock_version); ElMessage.success(action === 'RETURN' ? '划转单已退回终止，目标未改变' : action === 'APPROVE' ? '划转已生效，前后快照与日志已保存' : '确认成功，已进入下一审批环节'); await load(); comment.value = '' } catch (cause) { ElMessage.error(errorMessage(cause)) } finally { acting.value = false }
}
async function withdraw() { if (!transfer.value) return; const result = await ElMessageBox.prompt('请填写撤回原因，撤回后释放占用量。', '撤回划转单', { inputValidator: (value) => Boolean(value?.trim()) || '原因必填', type: 'warning' }); try { await adjustmentApi.withdrawTransfer(id, result.value, transfer.value.lock_version); ElMessage.success('划转单已撤回'); await load() } catch (cause) { ElMessage.error(errorMessage(cause)) } }
function openAttachment(item: TransferAttachment) {
  const url = transferAttachmentUrl(item)
  if (!url) { ElMessage.info('附件预览地址尚未生成，请稍后刷新重试'); return }
  window.open(url, '_blank', 'noopener,noreferrer')
}
onMounted(load)
</script>
<template>
  <div class="page-container" v-loading="loading"><PageHeader :title="`划转单 ${transfer?.transfer_no || ''}`" :description="transfer ? `${transfer.plan_name || ''} · ${transfer.indicator_name} · 发起于 ${formatDateTime(transfer.initiated_at)}` : '读取划转单与审批链'"><template #actions><el-button v-if="canWithdraw" type="warning" @click="withdraw">撤回划转</el-button></template></PageHeader><el-alert v-if="error" :title="error" type="error" show-icon :closable="false"><el-button link @click="load">重新加载</el-button></el-alert>
    <template v-if="transfer"><section class="panel"><div class="panel-body"><div class="inline-meta" style="margin-bottom:18px"><StatusTag :status="transfer.transfer_status" /><span>影响年度：<strong>{{ transfer.transfer_year || '五年总目标' }}</strong></span><span>划转量：<strong>{{ transfer.transfer_amount }}</strong></span></div><div class="transfer-route"><div><small>转出学院</small><strong>{{ transfer.source_college_org_name }}</strong><span>{{ transfer.source_task_code }}</span></div><div class="route-arrow">→</div><div><small>转入学院</small><strong>{{ transfer.target_college_org_name }}</strong><span>{{ transfer.target_task_id ? '转入既有任务' : '生效后生成新任务' }}</span></div></div><dl class="description-grid" style="margin-top:20px"><div class="description-item span-4"><dt>划转原因</dt><dd class="long-text">{{ transfer.reason }}</dd></div><div v-if="transfer.terminated_reason" class="description-item span-4"><dt>退回／终止原因</dt><dd class="danger-text">{{ transfer.terminated_reason }}</dd></div><div v-if="transfer.attachment_json?.length" class="description-item span-4"><dt>说明材料</dt><dd><div class="material-list"><div v-for="item in transfer.attachment_json" :key="item.object_key" class="material-item"><span class="material-info text-truncate">{{ item.name || item.original_file_name || item.file_name || '附件' }}</span><el-button link type="primary" :disabled="!transferAttachmentUrl(item)" @click="openAttachment(item)">打开预览</el-button></div></div></dd></div></dl></div></section>
      <div class="transfer-detail-layout"><section class="panel"><div class="panel-header"><h3 class="panel-title">顺序审批链</h3><span class="form-tip">任一方退回即终止，不改变目标</span></div><div class="panel-body"><el-steps direction="vertical" :active="4"><el-step v-for="step in steps" :key="step.code" :title="step.title" status="process"><template #description><div class="approval-step"><StatusTag :status="stepStatus(step.code)" /><div class="small">{{ step.description }}</div><template v-if="approval(step.code)"><div>处理人：{{ approval(step.code)?.approver_user_name || approval(step.code)?.approver_user_id || '待处理' }}</div><div>时间：{{ formatDateTime(approval(step.code)?.acted_at) }}</div><div v-if="approval(step.code)?.action_comment">意见：{{ approval(step.code)?.action_comment }}</div></template></div></template></el-step></el-steps></div></section>
        <aside><section v-if="currentAction" class="panel"><div class="panel-header"><h3 class="panel-title">当前环节处理</h3></div><div class="panel-body"><el-alert :title="currentAction === 'APPROVE' ? '您是最终审批人，批准后目标立即生效。' : '双方学院看到同一调整单，请核对划转量与影响年度。'" type="warning" :closable="false" style="margin-bottom:14px" /><el-input v-model="comment" type="textarea" :rows="4" placeholder="处理意见；退回时必填" /><el-button type="primary" style="width:100%;margin-top:12px" :loading="acting" @click="act(currentAction)"><el-icon><Check /></el-icon>{{ currentAction === 'APPROVE' ? '批准并生效' : '确认并继续' }}</el-button><el-button type="danger" plain style="width:100%;margin:8px 0 0" :loading="acting" @click="act('RETURN')">退回并终止</el-button></div></section><section class="panel"><div class="panel-header"><h3 class="panel-title">目标前后快照</h3></div><div class="panel-body"><h4 class="snapshot-title">调整前</h4><pre class="snapshot-box">{{ JSON.stringify(transfer.before_snapshot_json || {}, null, 2) }}</pre><h4 class="snapshot-title">调整后{{ transfer.transfer_status === 'EFFECTIVE' ? '（已生效）' : '（尚未生成）' }}</h4><pre class="snapshot-box">{{ JSON.stringify(transfer.after_snapshot_json || {}, null, 2) }}</pre></div></section></aside></div>
    </template>
  </div>
</template>
<style scoped>.transfer-route{display:grid;grid-template-columns:1fr 80px 1fr;align-items:center;padding:18px;background:#f6f8fc;border-radius:7px}.transfer-route>div:not(.route-arrow){display:flex;flex-direction:column;gap:5px}.transfer-route small{color:#8390a4}.transfer-route strong{font-size:17px}.transfer-route span{color:#7c899c;font-size:11px}.route-arrow{text-align:center;color:#3164d6;font-size:28px}.transfer-detail-layout{display:grid;grid-template-columns:minmax(0,1fr) 390px;gap:14px;margin-top:14px;align-items:start}.approval-step{padding:3px 0 15px;color:#738198;font-size:11px;line-height:1.8}.approval-step>div:first-of-type{margin-top:5px}.snapshot-title{margin:0 0 7px;color:#718097;font-size:11px}.snapshot-box+.snapshot-title{margin-top:14px}</style>
