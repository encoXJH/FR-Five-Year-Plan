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

/** 飞书 OAuth 回调：已绑定直接登录，首次绑定进入校内姓名和工号核验。 */
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
  // 尽早从地址栏移除一次性 code 和 state，绑定表单停留期间不再暴露它们
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
    if (error instanceof ApiError && error.message) {
      errorMessage.value = error.message
    } else {
      errorMessage.value = '飞书登录失败，请稍后重试'
    }
    status.value = 'error'
  }
})
</script>

<template>
  <div class="callback-page">
    <el-card class="callback-card" shadow="never">
      <div v-if="status === 'loading'" v-loading="true" class="loading-pane" element-loading-text="飞书登录中…" />
      <FeishuBindForm
        v-else-if="status === 'binding' && bindingChallenge"
        :binding-ticket="bindingChallenge.bindingTicket"
        :binding-ticket-expires-at="bindingChallenge.bindingTicketExpiresAt"
        :client-type="clientEnv"
        @success="handleBindSuccess"
        @cancel="router.replace('/login')"
      />
      <el-result v-else icon="error" title="飞书登录失败" :sub-title="errorMessage">
        <template #extra>
          <el-button type="primary" @click="router.replace('/login')">返回登录页</el-button>
        </template>
      </el-result>
    </el-card>
  </div>
</template>

<style scoped>
.callback-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  background-color: var(--el-bg-color-page);
}

.callback-card {
  width: 420px;
  max-width: calc(100vw - 32px);
  border-radius: var(--radius-dialog);
}

.loading-pane {
  height: 200px;
}

@media (max-width: 768px) {
  .callback-card :deep(.el-button) {
    min-height: 44px;
  }
}
</style>
