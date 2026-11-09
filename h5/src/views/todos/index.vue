<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import PageState from '@/components/PageState.vue'
import {
  getTodos,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from '@/api/todo'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import { enumLabel, enumOptions } from '@/utils/enumLabels'
import { formatRelativeTime } from '@/utils/format'
import { formatDeadline } from '@/utils/task'
import {
  TODO_GROUPS,
  resolveNotificationLink,
  resolveTodoLink,
  sortTodoItems,
  todoGroupLabel,
} from '@/utils/todo'
import type { TodoGroup, TodoItem, TodosResult } from '@/types/todo'
import type { NotificationItem } from '@/types/notification'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const appStore = useAppStore()

const activeTab = ref(route.query.tab === 'notifications' ? 'notifications' : 'todos')

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
  return sorted.filter((item) => item.type === activeGroup.value)
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
  if (link) router.push(link)
  else showToast('该待办暂无可跳转页面')
}

// 消息
const notificationsLoading = ref(false)
const notifications = ref<NotificationItem[]>([])
const notificationsTotal = ref(0)
const unreadCount = ref(0)
const notifFilter = reactive({ type: '', page: 1, pageSize: 20 })
const notificationTypeOptions = computed(() => enumOptions('NotificationType', userStore.enums))
const typeFilterVisible = ref(false)
const typeFilterLabel = computed(() =>
  notifFilter.type ? enumLabel('NotificationType', notifFilter.type, userStore.enums) : '全部类型')

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
    appStore.setUnread(result.unreadCount)
  } finally {
    notificationsLoading.value = false
  }
}

async function handleRead(item: NotificationItem): Promise<void> {
  const link = resolveNotificationLink(item)
  if (!link) {
    showToast('该通知暂无可跳转页面')
    return
  }
  if (!item.readAt) {
    try {
      const updated = await markNotificationRead(item.id)
      item.readAt = updated.readAt
      unreadCount.value = Math.max(0, unreadCount.value - 1)
      appStore.setUnread(unreadCount.value)
    } catch {
      // 拦截器已提示
    }
  }
  router.push(link)
}

const markingAll = ref(false)
async function handleReadAll(): Promise<void> {
  markingAll.value = true
  try {
    await markAllNotificationsRead()
    const now = new Date().toISOString()
    notifications.value.forEach((item) => {
      item.readAt = item.readAt ?? now
    })
    unreadCount.value = 0
    appStore.setUnread(0)
    showToast('已全部标记为已读')
  } finally {
    markingAll.value = false
  }
}

function chooseType(type: string): void {
  notifFilter.type = type
  notifFilter.page = 1
  typeFilterVisible.value = false
  void loadNotifications()
}

function changeNotifPage(next: number): void {
  notifFilter.page = next
  void loadNotifications()
}

const notifTotalPages = computed(() => Math.max(1, Math.ceil(notificationsTotal.value / notifFilter.pageSize)))

watch(activeTab, (tab) => {
  void router.replace({ path: '/todos', query: tab === 'notifications' ? { tab: 'notifications' } : {} })
})

onMounted(() => {
  void loadTodos()
  void loadNotifications()
})
</script>

<template>
  <div class="page page--tabbar">
    <div class="page-head">
      <h1>待办与消息</h1>
      <p class="muted small">任务填报、审核、接收确认与调整审批统一提醒</p>
    </div>

    <div class="tab-switch">
      <button type="button" class="tab-btn" :class="{ 'is-active': activeTab === 'todos' }" @click="activeTab = 'todos'">
        待办<template v-if="totalTodos > 0">（{{ totalTodos }}）</template>
      </button>
      <button type="button" class="tab-btn" :class="{ 'is-active': activeTab === 'notifications' }" @click="activeTab = 'notifications'">
        消息<template v-if="unreadCount > 0">（{{ unreadCount }} 未读）</template>
      </button>
    </div>

    <template v-if="activeTab === 'todos'">
      <div class="filter-scroll">
        <button type="button" class="filter-chip" :class="{ 'is-active': activeGroup === '' }" @click="activeGroup = ''">
          全部 {{ totalTodos }}
        </button>
        <button
          v-for="group in groupCounts"
          :key="group.value"
          type="button"
          class="filter-chip"
          :class="{ 'is-active': activeGroup === group.value }"
          @click="activeGroup = group.value"
        >{{ group.label }} {{ group.count }}</button>
      </div>

      <PageState :loading="todosLoading" :empty="visibleTodos.length === 0" empty-text="暂无待办">
        <div class="todo-list">
          <article v-for="todo in visibleTodos" :key="`${todo.type}-${todo.bizId}`" class="todo-card">
            <div class="row--between">
              <span class="chip">{{ todoGroupLabel(todo.type) }}</span>
              <span
                class="small"
                :class="formatDeadline(todo.deadline).overdue ? 'text-danger' : formatDeadline(todo.deadline).urgent ? 'text-warning' : 'muted'"
              >{{ formatDeadline(todo.deadline).text }}</span>
            </div>
            <div class="todo-title">{{ todo.detail ?? todo.bizCode ?? '—' }}</div>
            <div class="small muted">
              <span v-if="todo.bizCode">关联：{{ todo.bizCode }} · </span>
              发起于 {{ formatRelativeTime(todo.todoSince) }}
            </div>
            <div class="todo-actions">
              <van-button size="small" type="primary" @click="goHandle(todo)">去处理</van-button>
            </div>
          </article>
        </div>
      </PageState>
    </template>

    <template v-else>
      <div class="notif-toolbar">
        <button type="button" class="filter-btn" @click="typeFilterVisible = true">
          {{ typeFilterLabel }}
          <van-icon name="arrow-down" size="10" />
        </button>
        <van-button
          size="small"
          type="primary"
          plain
          :loading="markingAll"
          :disabled="unreadCount === 0"
          @click="handleReadAll"
        >全部已读</van-button>
      </div>

      <PageState :loading="notificationsLoading" :empty="notifications.length === 0" empty-text="暂无消息">
        <div class="notif-list">
          <article
            v-for="item in notifications"
            :key="item.id"
            class="notif-card"
            :class="{ 'is-unread': !item.readAt }"
            @click="handleRead(item)"
          >
            <span class="unread-dot" />
            <div class="notif-main">
              <div class="row--between">
                <span class="chip chip--gray">{{ enumLabel('NotificationType', item.type, userStore.enums) }}</span>
                <span class="small muted">{{ formatRelativeTime(item.createdAt) }}</span>
              </div>
              <div class="notif-title">{{ item.title }}</div>
              <div v-if="item.content" class="small muted ellipsis-2">{{ item.content }}</div>
            </div>
          </article>
        </div>
        <div class="pager">
          <van-button size="small" :disabled="notifFilter.page <= 1" @click="changeNotifPage(notifFilter.page - 1)">上一页</van-button>
          <span class="small muted">{{ notifFilter.page }} / {{ notifTotalPages }}（共 {{ notificationsTotal }} 条）</span>
          <van-button
            size="small"
            :disabled="notifFilter.page >= notifTotalPages"
            @click="changeNotifPage(notifFilter.page + 1)"
          >下一页</van-button>
        </div>
      </PageState>
    </template>

    <van-popup v-model:show="typeFilterVisible" position="bottom" round :style="{ maxHeight: '65vh' }">
      <div class="picker-sheet">
        <div class="sheet-head">
          <strong>消息类型</strong>
          <button type="button" class="close-btn" @click="typeFilterVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="type-list">
          <button type="button" class="picker-item" @click="chooseType('')">全部类型</button>
          <button
            v-for="option in notificationTypeOptions"
            :key="option.value"
            type="button"
            class="picker-item"
            @click="chooseType(option.value)"
          >{{ option.label }}</button>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.page-head {
  margin: 2px 0 10px;
}
.page-head h1 {
  margin: 0;
  font-size: 22px;
}
.page-head p {
  margin: 3px 0 0;
}
.tab-switch {
  display: flex;
  gap: 6px;
  margin-bottom: 10px;
  padding: 4px;
  border-radius: 10px;
  background: #eef1f6;
}
.tab-btn {
  flex: 1;
  min-height: 38px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
}
.tab-btn.is-active {
  background: #fff;
  color: var(--brand);
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(31, 45, 61, 0.08);
}
.todo-list,
.notif-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.todo-card {
  padding: 10px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.todo-title {
  margin-top: 6px;
  font-size: 14px;
  line-height: 20px;
}
.todo-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}
.notif-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.filter-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 17px;
  background: #fff;
  color: var(--text);
  font-size: 12px;
}
.notif-card {
  display: flex;
  gap: 8px;
  padding: 10px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.notif-card.is-unread {
  box-shadow: 0 0 0 1px #dbe6fb inset, var(--shadow);
}
.unread-dot {
  width: 7px;
  height: 7px;
  margin-top: 7px;
  border-radius: 50%;
  background: transparent;
  flex-shrink: 0;
}
.notif-card.is-unread .unread-dot {
  background: var(--danger);
}
.notif-main {
  flex: 1;
  min-width: 0;
}
.notif-title {
  margin: 6px 0 2px;
  font-size: 14px;
  font-weight: 600;
}
.notif-card.is-unread .notif-title {
  color: var(--text);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 12px 0;
}
.picker-sheet {
  padding: 14px 16px calc(14px + var(--safe-bottom));
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-size: 16px;
}
.close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background: #f4f6fa;
  color: var(--text-secondary);
}
.type-list {
  max-height: 55vh;
  overflow-y: auto;
}
.picker-item {
  width: 100%;
  min-height: 46px;
  padding: 8px 4px;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--text);
  text-align: left;
  font-size: 13px;
}
</style>
