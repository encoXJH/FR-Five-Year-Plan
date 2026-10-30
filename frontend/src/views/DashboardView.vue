<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Calendar, CircleCheck, Clock, DataAnalysis, Document, Right, Warning } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorMessage, executionApi, planningApi } from '@/api'
import type { ConstructionPlan, TodoItem } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import RemoteState from '@/components/RemoteState.vue'

const router = useRouter()
const auth = useAuthStore()
const todos = ref<TodoItem[]>([])
const plans = ref<ConstructionPlan[]>([])
const loading = ref(true)
const error = ref('')
const activeStatus = ref('PENDING')
const pending = computed(() => todos.value.filter((item) => ['PENDING', 'READ'].includes(item.todo_status)))
const filtered = computed(() => activeStatus.value === 'ALL' ? todos.value : activeStatus.value === 'PENDING' ? pending.value : todos.value.filter((item) => item.todo_status === activeStatus.value))
const counts = computed(() => ({ pending: pending.value.length, review: pending.value.filter((item) => item.biz_type === 'REVIEW').length, transfer: pending.value.filter((item) => item.biz_type.includes('TRANSFER')).length, unmet: todos.value.filter((item) => item.biz_type.includes('UNMET')).length }))
const greeting = computed(() => { const hour = new Date().getHours(); return hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好' })
async function load() {
  loading.value = true; error.value = ''
  try {
    const [todoResult] = await Promise.all([executionApi.todos({ page_size: 100 }), loadPlans()])
    todos.value = todoResult.items
  } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
async function loadPlans() {
  if (auth.roles.includes('ADMIN') && auth.roles.length === 1) return
  try { plans.value = (await planningApi.plans({ page_size: 6 })).items } catch { plans.value = [] }
}
function todoPath(todo: TodoItem) {
  if (todo.action_url?.startsWith('/')) return todo.action_url
  const routes: Record<string, string> = { TASK_ASSIGN: `/execution/tasks/${todo.biz_id}`, REVIEW: `/execution/reviews/${todo.biz_id}`, REVIEW_RETURN: `/execution/tasks/${todo.biz_id}`, TRANSFER_CONFIRM: `/adjustments/transfers/${todo.biz_id}`, TRANSFER_APPROVAL: `/adjustments/transfers/${todo.biz_id}`, CARRYOVER: '/adjustments/carryovers', RECOGNIZED_UNMET: '/gantt' }
  return routes[todo.biz_type] || '/dashboard'
}
async function openTodo(todo: TodoItem) {
  if (todo.todo_status === 'PENDING') { try { await executionApi.readTodo(todo.id) } catch { /* 业务入口仍可打开 */ } }
  const path = todoPath(todo)
  if (path === '/dashboard') ElMessage.info('该待办暂未提供独立业务入口')
  else router.push(path)
}
onMounted(load)
</script>
<template>
  <div class="page-container">
    <PageHeader :title="`${greeting}，${auth.user?.user_name || '用户'}`" description="这里汇总需要您处理的任务、审核和调整事项；待办只提供业务入口，不直接修改业务数据。"><template #actions><el-button v-if="auth.roles.includes('SCHOOL_LEADER')" type="primary" plain @click="router.push('/dashboard/leader')"><el-icon><DataAnalysis /></el-icon>数据看板</el-button><el-button @click="load"><el-icon><Clock /></el-icon>刷新待办</el-button></template></PageHeader>
    <div class="stat-grid">
      <div class="stat-card" style="--stat-color:#e6edff"><div class="stat-label">当前待办</div><div class="stat-value">{{ counts.pending }}</div><div class="stat-note">待处理及已读未完成事项</div></div>
      <div class="stat-card" style="--stat-color:#e8f2ff"><div class="stat-label">待审核填报</div><div class="stat-value">{{ counts.review }}</div><div class="stat-note">审核结论形成前可撤回</div></div>
      <div class="stat-card" style="--stat-color:#fff1d9"><div class="stat-label">划转待处理</div><div class="stat-value">{{ counts.transfer }}</div><div class="stat-note">按转出、转入、校领导顺序处理</div></div>
      <div class="stat-card" style="--stat-color:#fee8e7"><div class="stat-label">未达标提示</div><div class="stat-value">{{ counts.unmet }}</div><div class="stat-note">可进入顺延或后续补位流程</div></div>
    </div>
    <RemoteState :loading="loading" :error="error" @retry="load">
      <div class="dashboard-grid">
        <section class="panel">
          <div class="panel-header"><h3 class="panel-title">我的待办<span class="panel-subtitle">共 {{ filtered.length }} 项</span></h3><el-radio-group v-model="activeStatus" size="small"><el-radio-button value="PENDING">待处理</el-radio-button><el-radio-button value="COMPLETED">已完成</el-radio-button><el-radio-button value="ALL">全部</el-radio-button></el-radio-group></div>
          <div v-if="filtered.length" class="todo-list"><button v-for="todo in filtered" :key="todo.id" class="todo-item" @click="openTodo(todo)"><span class="todo-icon" :class="{ warning: todo.biz_type.includes('TRANSFER'), danger: todo.biz_type.includes('UNMET') }"><el-icon><Warning v-if="todo.biz_type.includes('UNMET')" /><Document v-else /></el-icon></span><span class="todo-content"><strong>{{ todo.title }}</strong><small>{{ todo.plan_name }}<template v-if="todo.target_year"> · {{ todo.target_year }} 年</template><template v-if="todo.content_summary"> · {{ todo.content_summary }}</template></small></span><StatusTag :status="todo.todo_status" /><span class="todo-time">{{ formatDateTime(todo.created_at) }}</span><el-icon class="todo-arrow"><Right /></el-icon></button></div>
          <el-empty v-else description="当前没有需要处理的事项" :image-size="90" />
        </section>
        <aside class="panel">
          <div class="panel-header"><h3 class="panel-title">我参与的计划</h3><el-button v-if="!auth.roles.includes('ADMIN')" link type="primary" @click="router.push('/planning/plans')">全部计划</el-button></div>
          <div v-if="plans.length" class="plan-list"><button v-for="plan in plans" :key="plan.id" @click="router.push(auth.roles.includes('COLLEGE_OWNER') ? '/gantt' : '/planning/indicators')"><el-icon><Calendar /></el-icon><span><strong>{{ plan.plan_name }}</strong><small>{{ plan.start_year }}—{{ plan.end_year }} · {{ plan.construction_scope }}</small></span><StatusTag :status="plan.plan_status" /></button></div>
          <el-empty v-else description="暂无可见计划" :image-size="80" />
          <div class="work-rule"><el-icon><CircleCheck /></el-icon><span>仅审核认定值计入正式完成量<br />空值、0、不适用分别保留业务语义</span></div>
        </aside>
      </div>
    </RemoteState>
  </div>
</template>
<style scoped>
.dashboard-grid { display:grid;grid-template-columns:minmax(0,1fr) 340px;gap:14px; }.todo-list{display:flex;flex-direction:column}.todo-item{width:100%;min-height:72px;padding:12px 15px;display:flex;align-items:center;gap:12px;text-align:left;border:0;border-bottom:1px solid #edf0f5;background:#fff;cursor:pointer}.todo-item:hover{background:#f7f9ff}.todo-icon{width:34px;height:34px;display:grid;place-items:center;flex:0 0 auto;color:#3164d6;background:#edf2ff;border-radius:6px}.todo-icon.warning{color:#ae721b;background:#fff5df}.todo-icon.danger{color:#c6535d;background:#fff0f0}.todo-content{flex:1;min-width:0}.todo-content strong,.todo-content small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.todo-content strong{font-size:13px;font-weight:550}.todo-content small{margin-top:5px;color:#8592a5;font-size:11px}.todo-time{color:#9aa4b5;font-size:10px;white-space:nowrap}.todo-arrow{color:#b3bdcc}.plan-list{padding:7px 14px}.plan-list button{width:100%;padding:13px 0;display:flex;align-items:flex-start;gap:9px;text-align:left;border:0;border-bottom:1px solid #edf0f5;background:#fff;cursor:pointer}.plan-list button>span{flex:1;min-width:0}.plan-list strong,.plan-list small{display:block}.plan-list strong{font-size:12px;line-height:1.55}.plan-list small{margin-top:4px;color:#8c98aa;font-size:10px}.plan-list>.el-icon{color:#4e76c8}.work-rule{margin:15px;padding:12px;display:flex;gap:9px;color:#687b99;background:#f3f7ff;border-radius:6px;font-size:11px;line-height:1.8}.work-rule .el-icon{margin-top:3px;color:#3276b9}@media(max-width:1400px){.dashboard-grid{grid-template-columns:minmax(0,1fr) 300px}.todo-time{display:none}}
</style>
