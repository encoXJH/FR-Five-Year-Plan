<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import {
  detectClientEnv,
  FEISHU_OAUTH_STATE_KEY,
  isFeishuOAuthStateValid,
  parseFeishuCallbackSearch,
} from '@/utils/feishu'
import FeishuBindForm from './components/FeishuBindForm.vue'
import type { FeishuBindingChallenge } from '@/types/auth'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const status = ref<'loading' | 'binding' | 'error'>('loading')
const errorMessage = ref('')
const bindingChallenge = ref<FeishuBindingChallenge | null>(null)
const clientEnv = detectClientEnv(window.navigator.userAgent)

function redirectTarget(): string {
  if (userStore.isUnitOwnerOnly) return userStore.homePath
  if (userStore.roles.includes('ADMIN')) return '/plans'
  return typeof route.query.redirect === 'string' ? route.query.redirect : '/'
}

function handleBindSuccess(): void {
  bindingChallenge.value = null
  router.replace(redirectTarget())
}

onMounted(async () => {
  const query = parseFeishuCallbackSearch(window.location.search)
  const expectedState = sessionStorage.getItem(FEISHU_OAUTH_STATE_KEY)
  sessionStorage.removeItem(FEISHU_OAUTH_STATE_KEY)
  window.history.replaceState(null, '', window.location.pathname)

  if (!isFeishuOAuthStateValid(query.state, expectedState)) {
    errorMessage.value = '飞书登录状态校验失败，请返回登录页重新发起'
    status.value = 'error'
    return
  }
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
        @cancel="router.replace('/login')"
      />
      <div v-else class="error-pane">
        <van-icon name="warning-o" size="42" color="#E45B5B" />
        <h2>飞书登录失败</h2>
        <p>{{ errorMessage }}</p>
        <van-button block type="primary" class="btn-height" @click="router.replace('/login')">
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
