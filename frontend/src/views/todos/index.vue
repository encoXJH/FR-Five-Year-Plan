<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getTodos,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from '@/api/todo'
import { useUserStore } from '@/stores/user'
import { enumLabel, enumOptions } from '@/utils/enumLabels'
import { formatRelativeTime } from '@/utils/format'
import { formatDeadline } from '@/utils/task'
import { TODO_GROUPS, resolveTodoLink, sortTodoItems, todoGroupLabel } from '@/utils/todo'
import type { TodoGroup, TodoItem, TodosResult } from '@/types/todo'
import type { NotificationItem } from '@/types/notification'

const router = useRouter()
const userStore = useUserStore()

const activeTab = ref('todos')

// ---------- 待办 ----------
const todosLoading = ref(false)
const todosResult = ref<TodosResult | null>(null)
const activeGroup = ref<string>('')

const groupCounts = computed(() => {
  const summary = todosResult.value?.summary ?? {}
  return TODO_GROUPS.map((g) => ({ ...g, count: Number(summary[g.value as TodoGroup] ?? 0) }))
})

const totalTodos = computed(() => groupCounts.value.reduce((sum, g) => sum + g.count, 0))

const visibleTodos = computed<TodoItem[]>(() => {
  const items = todosResult.value?.items ?? []
  const sorted = sortTodoItems(items)
  if (!activeGroup.value) return sorted
  return sorted.filter((i) => i.type === activeGroup.value)
})

async function loadTodos(): Promise<void> {
  todosLoading.value = true
  try {
    todosResult.value = await getTodos()
    if (!activeGroup.value) {
      const first = groupCounts.value.find((g) => g.count > 0)
      activeGroup.value = first?.value ?? ''
    }
  } finally {
    todosLoading.value = false
  }
}

function goHandle(todo: TodoItem): void {
  const link = resolveTodoLink(todo)
  if (link) {
    router.push(link)
  } else {
    ElMessage.info('该待办暂无可跳转页面')
  }
}

// ---------- 消息 ----------
const notificationsLoading = ref(false)
const notifications = ref<NotificationItem[]>([])
const notificationsTotal = ref(0)
const unreadCount = ref(0)
const notifFilter = reactive({ type: '', page: 1, pageSize: 20 })
const notificationTypeOptions = computed(() => enumOptions('NotificationType', userStore.enums))

async function loadNotifications(): Promise<void> {
  notificationsLoading.value = true
  try {
    const result = await listNotifications({
      type: notifFilter.type || undefined,
      page: notifFilter.page,
      pageSize: notifFilter.pageSize,
    })
    notifications.value = result.items
    notificationsTotal.value = result.total
    unreadCount.value = result.unreadCount
  } finally {
    notificationsLoading.value = false
  }
}

async function handleRead(item: NotificationItem): Promise<void> {
  if (item.readAt) {
    if (item.link) router.push(item.link)
    return
  }
  try {
    const updated = await markNotificationRead(item.id)
    item.readAt = updated.readAt
    unreadCount.value = Math.max(0, unreadCount.value - 1)
  } catch {
    // 拦截器已提示
  }
  if (item.link) router.push(item.link)
}

const markingAll = ref(false)

async function handleReadAll(): Promise<void> {
  markingAll.value = true
  try {
    await markAllNotificationsRead()
    const now = new Date().toISOString()
    notifications.value.forEach((n) => {
      n.readAt = n.readAt ?? now
    })
    unreadCount.value = 0
    ElMessage.success('已全部标记为已读')
  } catch {
    // 拦截器已提示
  } finally {
    markingAll.value = false
  }
}

onMounted(() => {
  loadTodos()
  loadNotifications()
})
</script>

<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">待办与消息</h1>
    </div>
    <el-card shadow="never">
      <el-tabs v-model="activeTab">
        <el-tab-pane name="todos">
          <template #label>待办<template v-if="totalTodos > 0">（{{ totalTodos }}）</template></template>
          <div v-loading="todosLoading" class="todos-body">
            <div class="todo-groups">
              <div
                class="todo-group-item"
                :class="{ 'is-active': activeGroup === '' }"
                @click="activeGroup = ''"
              >
                全部<span class="group-count">{{ totalTodos }}</span>
              </div>
              <div
                v-for="g in groupCounts"
                :key="g.value"
                class="todo-group-item"
                :class="{ 'is-active': activeGroup === g.value }"
                @click="activeGroup = g.value"
              >
                {{ g.label }}<span class="group-count">{{ g.count }}</span>
              </div>
            </div>
            <div class="todo-list">
              <el-empty
                v-if="!todosLoading && visibleTodos.length === 0"
                description="暂无待办"
                :image-size="60"
              />
              <div v-for="todo in visibleTodos" :key="`${todo.type}-${todo.bizId}`" class="todo-item">
                <el-tag size="small" effect="light" class="todo-tag">
                  {{ todoGroupLabel(todo.type) }}
                </el-tag>
                <div class="todo-main">
                  <div class="todo-title">{{ todo.detail ?? todo.bizCode ?? '—' }}</div>
                  <div class="todo-meta">
                    <span v-if="todo.bizCode">关联：{{ todo.bizCode }}</span>
                    <span>发起于 {{ formatRelativeTime(todo.todoSince) }}</span>
                  </div>
                </div>
                <span
                  class="todo-deadline"
                  :class="{
                    'is-urgent': formatDeadline(todo.deadline).urgent && !formatDeadline(todo.deadline).overdue,
                    'is-overdue': formatDeadline(todo.deadline).overdue,
                  }"
                >
                  {{ formatDeadline(todo.deadline).text }}
                </span>
                <el-button type="primary" size="small" @click="goHandle(todo)">去处理</el-button>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane name="notifications">
          <template #label>消息<template v-if="unreadCount > 0">（{{ unreadCount }} 未读）</template></template>
          <div class="notif-toolbar">
            <el-select
              v-model="notifFilter.type"
              clearable
              placeholder="全部类型"
              style="width: 180px"
              @change="
                () => {
                  notifFilter.page = 1
                  loadNotifications()
                }
              "
            >
              <el-option
                v-for="o in notificationTypeOptions"
                :key="o.value"
                :label="o.label"
                :value="o.value"
              />
            </el-select>
            <div class="toolbar-spacer" />
            <el-button :loading="markingAll" :disabled="unreadCount === 0" @click="handleReadAll">
              全部已读
            </el-button>
          </div>
          <div v-loading="notificationsLoading">
            <el-empty
              v-if="!notificationsLoading && notifications.length === 0"
              description="暂无消息"
              :image-size="60"
            />
            <div
              v-for="item in notifications"
              :key="item.id"
              class="notif-item"
              :class="{ 'is-unread': !item.readAt }"
              @click="handleRead(item)"
            >
              <span class="unread-dot" />
              <el-tag size="small" effect="light" class="notif-tag">
                {{ enumLabel('NotificationType', item.type, userStore.enums) }}
              </el-tag>
              <div class="notif-main">
                <div class="notif-title">{{ item.title }}</div>
                <div v-if="item.content" class="notif-content">{{ item.content }}</div>
              </div>
              <div class="notif-time">{{ formatRelativeTime(item.createdAt) }}</div>
            </div>
            <div class="pagination-bar">
              <el-pagination
                background
                layout="total, sizes, prev, pager, next, jumper"
                :total="notificationsTotal"
                :current-page="notifFilter.page"
                :page-size="notifFilter.pageSize"
                :page-sizes="[20, 50, 100]"
                @size-change="
                  (s: number) => {
                    notifFilter.pageSize = s
                    notifFilter.page = 1
                    loadNotifications()
                  }
                "
                @current-change="
                  (p: number) => {
                    notifFilter.page = p
                    loadNotifications()
                  }
                "
              />
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
}

.todos-body {
  display: flex;
  gap: var(--space-4);
  min-height: 320px;
}

.todo-groups {
  width: 160px;
  flex-shrink: 0;
  border-right: 1px solid var(--el-border-color-light);
  padding-right: var(--space-3);
}

.todo-group-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 14px;
  cursor: pointer;
}

.todo-group-item:hover {
  background-color: var(--el-fill-color-light);
}

.todo-group-item.is-active {
  background-color: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}

.group-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.todo-list {
  flex: 1;
  min-width: 0;
}

.todo-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 10px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.todo-tag {
  flex-shrink: 0;
}

.todo-main {
  flex: 1;
  min-width: 0;
}

.todo-title {
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.todo-meta {
  display: flex;
  gap: var(--space-3);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.todo-deadline {
  flex-shrink: 0;
  width: 170px;
  text-align: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.todo-deadline.is-urgent {
  color: var(--el-color-warning);
  font-weight: 600;
}

.todo-deadline.is-overdue {
  color: var(--el-color-danger);
  font-weight: 600;
}

.notif-toolbar {
  display: flex;
  align-items: center;
  margin-bottom: var(--space-3);
}

.toolbar-spacer {
  flex: 1;
}

.notif-item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: 10px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}

.notif-item:hover {
  background-color: var(--el-fill-color-light);
}

.unread-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: transparent;
  margin-top: 7px;
  flex-shrink: 0;
}

.notif-item.is-unread .unread-dot {
  background-color: var(--el-color-danger);
}

.notif-item.is-unread .notif-title {
  font-weight: 600;
}

.notif-tag {
  flex-shrink: 0;
  margin-top: 1px;
}

.notif-main {
  flex: 1;
  min-width: 0;
}

.notif-title {
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.notif-content {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.notif-time {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}

/* 移动端（≤768px，UI 规范 5.2）：分组侧栏改顶部横向滚动 tab，消息项压缩 */
@media (max-width: 768px) {
  .todos-body {
    flex-direction: column;
    gap: var(--space-3);
  }

  .todo-groups {
    display: flex;
    width: 100%;
    overflow-x: auto;
    border-right: none;
    border-bottom: 1px solid var(--el-border-color-light);
    padding-right: 0;
    padding-bottom: var(--space-2);
    gap: var(--space-1);
  }

  .todo-group-item {
    flex-shrink: 0;
    padding: 6px 10px;
    font-size: 13px;
  }

  .todo-item {
    flex-wrap: wrap;
    padding: 10px 8px;
  }

  .todo-item .el-button {
    min-height: 32px;
  }

  .notif-time {
    display: none;
  }

  .pagination-bar {
    justify-content: center;
  }
}
</style>
