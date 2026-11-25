<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, Bell, Menu, School } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { getUnreadNotificationCount } from '@/api/todo'
import { changePassword as changePasswordApi } from '@/api/auth'
import { detectClientEnv } from '@/utils/feishu'
import { INITIAL_PASSWORD, passwordChangeError } from '@/utils/password'
import { NOTIFICATIONS_CHANGED_EVENT } from '@/utils/todo'
import { acknowledgeInitialPasswordReminder, shouldShowInitialPasswordReminder } from '@/utils/initialPasswordReminder'

const router = useRouter()
const userStore = useUserStore()

const emit = defineEmits<{ (e: 'toggle-menu'): void }>()

/** 飞书客户端内不提供「退出登录」（UI 规范 5.1） */
const isFeishuClient = detectClientEnv(navigator.userAgent) !== 'WEB'

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
  void router.push({ path: '/todos', query: { tab: 'notifications' } })
}

onMounted(() => {
  void refreshUnread()
  window.addEventListener(NOTIFICATIONS_CHANGED_EVENT, refreshUnread)
  timer = setInterval(refreshUnread, POLL_INTERVAL_MS)
})

onBeforeUnmount(() => {
  if (timer !== null) clearInterval(timer)
  window.removeEventListener(NOTIFICATIONS_CHANGED_EVENT, refreshUnread)
})

const passwordDialogVisible = ref(false)
const passwordSaving = ref(false)
const passwordForm = reactive({ password: '', confirmPassword: '' })
const initialPasswordReminderVisible = ref(false)
watch(
  () => [userStore.userInfo?.id, userStore.passwordChangeRequired] as const,
  ([userId, required]) => {
    initialPasswordReminderVisible.value = shouldShowInitialPasswordReminder(userId, required)
  },
  { immediate: true },
)

function closeInitialPasswordReminder(): void {
  acknowledgeInitialPasswordReminder(userStore.userInfo?.id)
  initialPasswordReminderVisible.value = false
}

const passwordLengthError = computed(() => {
  if (!passwordForm.password) return ''
  if (passwordForm.password === INITIAL_PASSWORD) return '新密码不能仍为初始密码'
  return passwordForm.password.length < 8 || passwordForm.password.length > 64
    ? '密码长度必须为 8–64 位'
    : ''
})
const confirmPasswordError = computed(() => {
  if (!passwordForm.confirmPassword) return ''
  return passwordForm.password !== passwordForm.confirmPassword ? '两次输入的密码不一致' : ''
})
const canSubmitPassword = computed(() =>
  passwordChangeError(passwordForm.password, passwordForm.confirmPassword, true) === '',
)

function resetPasswordForm(): void {
  passwordForm.password = ''
  passwordForm.confirmPassword = ''
}

async function submitChangePassword(): Promise<void> {
  if (!canSubmitPassword.value || passwordSaving.value) return
  passwordSaving.value = true
  try {
    await changePasswordApi({ ...passwordForm })
    passwordDialogVisible.value = false
    ElMessage.success('密码修改成功，请使用新密码重新登录')
    await userStore.logout()
    await router.push('/login')
  } catch {
    // 拦截器已提示
  } finally {
    passwordSaving.value = false
  }
}

async function handleCommand(command: string): Promise<void> {
  if (command === 'changePassword') {
    passwordDialogVisible.value = true
    return
  }
  if (command === 'logout') {
    await userStore.logout()
    router.push('/login')
  }
}
</script>

<template>
  <div class="header-bar">
    <div class="brand">
      <el-icon
        :size="22"
        class="menu-trigger"
        role="button"
        tabindex="0"
        aria-label="打开导航菜单"
        @click="emit('toggle-menu')"
        @keydown.enter.prevent="emit('toggle-menu')"
        @keydown.space.prevent="emit('toggle-menu')"
      ><Menu /></el-icon>
      <el-icon :size="24" class="brand-logo"><School /></el-icon>
      <span class="brand-name">福州软件职业技术学院 · 双高管理平台</span>
    </div>
    <div class="header-right">
      <el-badge
        :value="unreadCount"
        :max="99"
        :hidden="unreadCount === 0"
        class="todo-badge"
      >
        <el-icon
          :size="20"
          class="bell-icon"
          role="button"
          tabindex="0"
          aria-label="待办与消息"
          @click="goTodos"
          @keydown.enter.prevent="goTodos"
          @keydown.space.prevent="goTodos"
        ><Bell /></el-icon>
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
            <el-dropdown-item command="changePassword">修改密码</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-dialog
        v-model="passwordDialogVisible"
        title="修改密码"
        width="420px"
        :close-on-click-modal="!passwordSaving"
        :close-on-press-escape="!passwordSaving"
        :show-close="!passwordSaving"
        @closed="resetPasswordForm"
      >
        <el-alert type="info" :closable="false" show-icon class="password-tip">
          密码长度为 8–64 位，修改成功后需要使用新密码重新登录。
        </el-alert>
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="新密码" required :error="passwordLengthError">
            <el-input
              v-model="passwordForm.password"
              type="password"
              show-password
              autocomplete="new-password"
              maxlength="64"
              placeholder="请输入新密码"
              :disabled="passwordSaving"
            />
          </el-form-item>
          <el-form-item label="确认新密码" required :error="confirmPasswordError">
            <el-input
              v-model="passwordForm.confirmPassword"
              type="password"
              show-password
              autocomplete="new-password"
              maxlength="64"
              placeholder="请再次输入新密码"
              :disabled="passwordSaving"
              @keyup.enter="submitChangePassword"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button :disabled="passwordSaving" @click="passwordDialogVisible = false">取消</el-button>
          <el-button
            type="primary"
            :loading="passwordSaving"
            :disabled="!canSubmitPassword"
            @click="submitChangePassword"
          >
            确认修改
          </el-button>
        </template>
      </el-dialog>
      <el-dialog
        v-model="initialPasswordReminderVisible"
        title="初始密码安全提醒"
        width="420px"
        @close="closeInitialPasswordReminder"
      >
        <el-alert type="warning" :closable="false" show-icon class="password-tip">
          检测到当前账号仍使用初始密码，为保障账号安全，建议尽快通过右上角账号菜单修改密码。
        </el-alert>
        <p class="reminder-tip">此提醒仅出现一次，不影响继续使用系统。</p>
        <template #footer>
          <el-button type="primary" @click="closeInitialPasswordReminder">我已知悉</el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<style scoped>
.password-tip {
  margin-bottom: var(--space-4);
}

.reminder-tip {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 22px;
}

.header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  min-width: 0;
  height: 100%;
  padding: 0 var(--space-5);
  color: #ffffff;
}

.brand {
  display: flex;
  align-items: center;
  flex: 1;
  gap: var(--space-3);
  min-width: 0;
}

.brand-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
}

.header-right {
  display: flex;
  align-items: center;
  flex-shrink: 0;
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

.bell-icon:focus-visible,
.user-entry:focus-visible,
.menu-trigger:focus-visible {
  outline: 2px solid #ffffff;
  outline-offset: 3px;
  border-radius: 4px;
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

@media (max-width: 600px) {
  .brand-name,
  .user-name {
    display: none;
  }

  .brand {
    gap: var(--space-2);
  }
}
</style>
