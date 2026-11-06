<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, HomeFilled, Tickets, User } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { detectClientEnv } from '@/utils/feishu'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 填报、发起调整等专注流程隐藏底栏，避免与底部吸附操作栏冲突 */
const visible = computed(() => route.name !== 'TaskSubmit' && route.name !== 'AdjustmentApply')

const homeEntry = computed(() =>
  userStore.isUnitOwnerOnly
    ? { path: '/workbench', label: '工作台', icon: Tickets }
    : { path: '/plans', label: '首页', icon: HomeFilled },
)

const activeKey = computed(() => {
  if (route.path === homeEntry.value.path) return 'home'
  if (route.path === '/todos') return route.query.tab === 'notifications' ? 'messages' : 'todos'
  return ''
})

function navigate(path: string): void {
  if (route.fullPath !== path) router.push(path)
}

// ---------- 我的 ----------
const profileVisible = ref(false)
const isFeishuClient = detectClientEnv(navigator.userAgent) !== 'WEB'

const rolesText = computed(() => {
  const labels = userStore.roles.map((role) => userStore.enums.Role?.[role] ?? role)
  return labels.length > 0 ? labels.join(' / ') : '—'
})

const unitName = computed(() => userStore.userInfo?.unit?.name ?? '校级账号')

async function handleLogout(): Promise<void> {
  profileVisible.value = false
  await userStore.logout()
  router.push('/login')
}
</script>

<template>
  <nav v-if="visible" class="mobile-tabbar" aria-label="移动端主导航">
    <button
      type="button"
      class="mobile-tabbar__item"
      :class="{ 'is-active': activeKey === 'home' }"
      @click="navigate(homeEntry.path)"
    >
      <el-icon :size="21"><component :is="homeEntry.icon" /></el-icon>
      <span>{{ homeEntry.label }}</span>
    </button>
    <button
      type="button"
      class="mobile-tabbar__item"
      :class="{ 'is-active': activeKey === 'todos' }"
      @click="navigate('/todos')"
    >
      <el-icon :size="21"><Tickets /></el-icon>
      <span>待办</span>
    </button>
    <button
      type="button"
      class="mobile-tabbar__item"
      :class="{ 'is-active': activeKey === 'messages' }"
      @click="navigate('/todos?tab=notifications')"
    >
      <el-icon :size="21"><Bell /></el-icon>
      <span>消息</span>
    </button>
    <button
      type="button"
      class="mobile-tabbar__item"
      :class="{ 'is-active': profileVisible }"
      @click="profileVisible = true"
    >
      <el-icon :size="21"><User /></el-icon>
      <span>我的</span>
    </button>
  </nav>

  <el-drawer
    v-model="profileVisible"
    direction="btt"
    size="auto"
    :with-header="false"
    class="profile-sheet"
  >
    <div class="profile-sheet__body">
      <div class="profile-sheet__handle" aria-hidden="true" />
      <div class="profile-sheet__user">
        <span class="profile-sheet__avatar">{{ userStore.userInfo?.name?.slice(0, 1) ?? '?' }}</span>
        <div class="profile-sheet__identity">
          <strong>{{ userStore.userInfo?.name ?? '未登录' }}</strong>
          <span>{{ unitName }}</span>
        </div>
      </div>
      <dl class="profile-sheet__meta">
        <div>
          <dt>角色</dt>
          <dd>{{ rolesText }}</dd>
        </div>
      </dl>
      <el-button
        v-if="!isFeishuClient"
        type="danger"
        plain
        class="profile-sheet__logout"
        @click="handleLogout"
      >
        退出登录
      </el-button>
    </div>
  </el-drawer>
</template>

<style scoped>
.mobile-tabbar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 100;
  display: none;
  height: calc(56px + env(safe-area-inset-bottom));
  padding: 0 var(--space-2) env(safe-area-inset-bottom);
  background: var(--el-bg-color);
  border-top: 1px solid var(--el-border-color-light);
  box-shadow: 0 -2px 12px rgba(31, 45, 61, 0.06);
}

.mobile-tabbar__item {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-width: 0;
  border: none;
  background: transparent;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 16px;
  cursor: pointer;
  outline: none;
  transition: color 0.15s ease;
}

.mobile-tabbar__item.is-active {
  color: var(--el-color-primary);
  font-weight: 600;
}

.mobile-tabbar__item:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
  border-radius: var(--el-border-radius-base);
}

@media (max-width: 768px) {
  .mobile-tabbar {
    display: flex;
    align-items: stretch;
  }
}

.profile-sheet :deep(.el-drawer__body) {
  padding: 0;
}

.profile-sheet__body {
  padding: var(--space-2) var(--space-5) calc(var(--space-5) + env(safe-area-inset-bottom));
}

.profile-sheet__handle {
  width: 36px;
  height: 4px;
  margin: 0 auto var(--space-4);
  border-radius: 2px;
  background: var(--el-border-color);
}

.profile-sheet__user {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.profile-sheet__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 18px;
  font-weight: 600;
}

.profile-sheet__identity {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.profile-sheet__identity strong {
  font-size: 17px;
  line-height: 24px;
}

.profile-sheet__identity span {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.profile-sheet__meta {
  margin: var(--space-4) 0;
}

.profile-sheet__meta > div {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  padding: 8px 0;
  font-size: 14px;
}

.profile-sheet__meta dt {
  flex: 0 0 auto;
  color: var(--el-text-color-secondary);
}

.profile-sheet__meta dd {
  margin: 0;
  overflow-wrap: anywhere;
  text-align: right;
}

.profile-sheet__logout {
  width: 100%;
  min-height: 44px;
}
</style>
