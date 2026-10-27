<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import { detectClientEnv, parseFeishuCallbackSearch } from '@/utils/feishu'

/**
 * 飞书 OAuth 回调页（浏览器环境）：飞书授权后重定向回本页并携带 code，
 * 用 code 调 POST /auth/feishu/login 换取平台 Token（API 约定 4.1 / 16.1）。
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const status = ref<'loading' | 'error'>('loading')
const errorMessage = ref('')

onMounted(async () => {
  const query = parseFeishuCallbackSearch(window.location.search)
  // 授权被取消 / 失败
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
    await userStore.loginFeishu(query.code, detectClientEnv(window.navigator.userAgent))
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    router.replace(redirect)
  } catch (error) {
    if (error instanceof ApiError && error.code === 20007) {
      errorMessage.value = '飞书账号未绑定平台用户，请联系管理员完成绑定后重试'
    } else if (error instanceof ApiError && error.message) {
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
</style>
