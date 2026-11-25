<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import {
  consumeFeishuOAuthContext,
  detectClientEnv,
  parseFeishuCallbackSearch,
} from '@/utils/feishu'
import { resolveSafeInternalRedirect } from '@/utils/navigation'
import FeishuBindForm from './components/FeishuBindForm.vue'
import type { FeishuBindingChallenge } from '@/types/auth'

const router = useRouter()
const userStore = useUserStore()

const status = ref<'loading' | 'binding' | 'error'>('loading')
const errorMessage = ref('')
const bindingChallenge = ref<FeishuBindingChallenge | null>(null)
const clientEnv = detectClientEnv(window.navigator.userAgent)
let safeReturnTo: string | null = null

function redirectTarget(): string {
  if (safeReturnTo) return safeReturnTo
  if (userStore.isTaskExecutorOnly) return userStore.homePath
  if (userStore.roles.includes('ADMIN')) return '/plans'
  return '/'
}

function returnToLogin(): void {
  router.replace(safeReturnTo ? { path: '/login', query: { redirect: safeReturnTo } } : '/login')
}

function handleBindSuccess(): void {
  bindingChallenge.value = null
  router.replace(redirectTarget())
}

onMounted(async () => {
  const query = parseFeishuCallbackSearch(window.location.search)
  const context = consumeFeishuOAuthContext(query.state)
  window.history.replaceState(null, '', window.location.pathname)

  if (!context) {
    errorMessage.value = '飞书登录状态校验失败或已过期，请返回登录页重新发起'
    status.value = 'error'
    return
  }
  safeReturnTo = resolveSafeInternalRedirect(router, context.returnTo)
  if (query.error) {
    errorMessage.value = query.errorDescription || `飞书授权失败（${query.error}）`
    status.value = 'error'
    return
  }
  if (!query.code) {
    errorMessage.value = '回调地址缺少授权码 code，请重新发起飞书登录'
    status.value = 'error'
    return
  }
  try {
    const challenge = await userStore.loginFeishu(query.code, clientEnv)
    if (challenge) {
      bindingChallenge.value = challenge
      status.value = 'binding'
      return
    }
    router.replace(redirectTarget())
  } catch (error) {
    errorMessage.value =
      error instanceof ApiError && error.message ? error.message : '飞书登录失败，请稍后重试'
    status.value = 'error'
  }
})
</script>

<template>
  <div class="callback-page">
    <div class="callback-card">
      <div v-if="status === 'loading'" class="loading-pane">
        <van-loading size="28" color="#2B5BC0" vertical>飞书登录中…</van-loading>
      </div>
      <FeishuBindForm
        v-else-if="status === 'binding' && bindingChallenge"
        :binding-ticket="bindingChallenge.bindingTicket"
        :binding-ticket-expires-at="bindingChallenge.bindingTicketExpiresAt"
        :client-type="clientEnv"
        @success="handleBindSuccess"
        @cancel="returnToLogin"
      />
      <div v-else class="error-pane">
        <van-icon name="warning-o" size="42" color="#E45B5B" />
        <h2>飞书登录失败</h2>
        <p>{{ errorMessage }}</p>
        <van-button block type="primary" class="btn-height" @click="returnToLogin">
          返回登录页
        </van-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.callback-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 20px 16px;
  background: var(--bg);
}
.callback-card {
  width: 100%;
  max-width: 420px;
  padding: 22px 18px;
  border-radius: 16px;
  background: #fff;
  box-shadow: var(--shadow);
}
.loading-pane {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 180px;
}
.error-pane {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  text-align: center;
}
.error-pane h2 {
  margin: 0;
  font-size: 18px;
}
.error-pane p {
  margin: 0 0 8px;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 20px;
}
</style>
