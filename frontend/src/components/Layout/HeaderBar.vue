<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Bell, Menu, School } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { getUnreadNotificationCount } from '@/api/todo'
import { listPlans } from '@/api/plan'
import type { PlanCard } from '@/types/plan'
import { detectClientEnv } from '@/utils/feishu'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const emit = defineEmits<{ (e: 'toggle-menu'): void }>()

/** 飞书客户端内不提供「退出登录」（UI 规范 5.1） */
const isFeishuClient = detectClientEnv(navigator.userAgent) !== 'WEB'

// ---------- 当前计划切换（路由带 planId 时显示） ----------
const plans = ref<PlanCard[]>([])
const routePlanId = computed(() =>
  typeof route.params.planId === 'string' ? route.params.planId : '',
)
const currentPlan = computed(() => plans.value.find((p) => p.id === routePlanId.value) ?? null)

async function switchPlan(planId: string): Promise<void> {
  if (!planId || planId === routePlanId.value) return
  if (route.name) {
    await router.replace({
      name: route.name,
      params: { ...route.params, planId },
      query: route.query,
    })
  } else {
    await router.replace({
      path: route.path.replace(`/plans/${routePlanId.value}`, `/plans/${planId}`),
      query: route.query,
    })
  }
}

// ---------- 未读消息角标（60s 轮询） ----------
const POLL_INTERVAL_MS = 60_000
const unreadCount = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

async function refreshUnread(): Promise<void> {
  if (!userStore.isLoggedIn) return
  try {
    unreadCount.value = await getUnreadNotificationCount()
  } catch {
    // 静默失败，下轮重试
  }
}

function goTodos(): void {
  router.push('/todos')
}

onMounted(() => {
  refreshUnread()
  timer = setInterval(refreshUnread, POLL_INTERVAL_MS)
  listPlans()
    .then((list) => {
      plans.value = list
    })
    .catch(() => undefined)
})

onBeforeUnmount(() => {
  if (timer !== null) clearInterval(timer)
})

async function handleCommand(command: string): Promise<void> {
  if (command === 'logout') {
    await userStore.logout()
    router.push('/login')
  }
}
</script>

<template>
  <div class="header-bar">
    <div class="brand">
      <el-icon :size="22" class="menu-trigger" @click="emit('toggle-menu')"><Menu /></el-icon>
      <el-icon :size="24" class="brand-logo"><School /></el-icon>
      <span class="brand-name">福州软件职业技术学院 · 五年计划指标管理平台</span>
      <el-dropdown v-if="routePlanId" trigger="click" @command="switchPlan">
        <span class="plan-entry">
          <span class="plan-name">{{ currentPlan?.name ?? '当前计划' }}</span>
          <el-tag v-if="currentPlan?.status === 'ARCHIVED'" type="info" size="small">
            已归档
          </el-tag>
          <el-icon :size="12"><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item
              v-for="p in plans"
              :key="p.id"
              :command="p.id"
              :disabled="p.id === routePlanId"
            >
              {{ p.name }}
              <el-tag
                v-if="p.status === 'ARCHIVED'"
                type="info"
                size="small"
                class="plan-item-tag"
              >
                已归档
              </el-tag>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
    <div class="header-right">
      <el-badge
        :value="unreadCount"
        :max="99"
        :hidden="unreadCount === 0"
        class="todo-badge"
      >
        <el-icon :size="20" class="bell-icon" @click="goTodos"><Bell /></el-icon>
      </el-badge>
      <el-dropdown trigger="click" @command="handleCommand">
        <span class="user-entry">
          <span class="user-avatar">{{ userStore.userInfo?.name?.slice(0, 1) ?? ' ' }}</span>
          <span class="user-name">{{ userStore.userInfo?.name ?? '未登录' }}</span>
          <el-icon :size="12"><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="!isFeishuClient" command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<style scoped>
.header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
  padding: 0 var(--space-5);
  color: #ffffff;
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.brand-name {
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
}

.plan-entry {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: #ffffff;
  cursor: pointer;
  outline: none;
}

.plan-name {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}

.plan-item-tag {
  margin-left: var(--space-2);
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--space-5);
}

.todo-badge {
  display: flex;
  align-items: center;
}

.bell-icon {
  color: #ffffff;
  cursor: pointer;
  outline: none;
}

.user-entry {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: #ffffff;
  cursor: pointer;
  outline: none;
}

.user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background-color: rgba(255, 255, 255, 0.25);
  font-size: 13px;
}

.user-name {
  font-size: 14px;
}

.menu-trigger {
  display: none;
  color: #ffffff;
  cursor: pointer;
  outline: none;
}

/* 移动端（≤768px）：显示汉堡按钮，压缩品牌名 */
@media (max-width: 768px) {
  .menu-trigger {
    display: inline-flex;
  }

  .brand-name {
    font-size: 13px;
    line-height: 18px;
  }

  .header-bar {
    padding: 0 var(--space-3);
  }

  .header-right {
    gap: var(--space-3);
  }
}
</style>
