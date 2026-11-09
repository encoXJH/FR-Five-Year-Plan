<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { useUserStore } from '@/stores/user'
import { fetchFeishuAuthorizeUrl } from '@/api/auth'
import { ApiError } from '@/utils/request'
import {
  detectClientEnv,
  FEISHU_OAUTH_STATE_KEY,
  isFeishuUnavailableCode,
  normalizeAuthorizeInfo,
  type ClientEnv,
  type FeishuAuthorizeInfo,
} from '@/utils/feishu'
import { createUuid } from '@/utils/uuid'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const loading = ref(false)
const form = reactive({ username: '', password: '' })

const clientEnv: ClientEnv = detectClientEnv(window.navigator.userAgent)
const feishuAuth = ref<FeishuAuthorizeInfo | null>(null)
const autoLoginPending = ref(clientEnv !== 'WEB')
const feishuLoginLoading = ref(false)
function redirectTarget(): string {
  if (userStore.isUnitOwnerOnly) return userStore.homePath
  if (userStore.roles.includes('ADMIN')) return '/plans'
  return typeof route.query.redirect === 'string' ? route.query.redirect : '/'
}

async function goFeishuAuthorize(): Promise<boolean> {
  if (!feishuAuth.value) return false
  feishuLoginLoading.value = true
  const state = createUuid()
  try {
    sessionStorage.setItem(FEISHU_OAUTH_STATE_KEY, state)
    const info = normalizeAuthorizeInfo(await fetchFeishuAuthorizeUrl(state))
    if (!info?.url) throw new Error('飞书授权地址不可用')
    window.location.assign(info.url)
    return true
  } catch {
    sessionStorage.removeItem(FEISHU_OAUTH_STATE_KEY)
    feishuLoginLoading.value = false
    showToast('暂时无法发起飞书登录，请稍后重试')
    return false
  }
}

onMounted(async () => {
  let info: FeishuAuthorizeInfo | null = null
  try {
    info = normalizeAuthorizeInfo(await fetchFeishuAuthorizeUrl())
  } catch (error) {
    if (!(error instanceof ApiError && isFeishuUnavailableCode(error.code))) {
      // 网络异常同样按不可用处理，只影响飞书入口显示
    }
  }
  feishuAuth.value = info
  if (clientEnv === 'WEB') {
    autoLoginPending.value = false
  } else if (info) {
    const started = await goFeishuAuthorize()
    if (!started) autoLoginPending.value = false
  } else {
    autoLoginPending.value = false
  }
})

async function handleSubmit(): Promise<void> {
  if (!form.username.trim()) {
    showToast('请输入工号')
    return
  }
  if (!form.password) {
    showToast('请输入密码')
    return
  }
  loading.value = true
  try {
    await userStore.login({ username: form.username.trim(), password: form.password })
    router.replace(redirectTarget())
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

const DEMO_ACCOUNTS = [
  { username: 'admin', name: '平台管理员', desc: '全部菜单' },
  { username: 'leader', name: '校领导', desc: '审批 / 报表' },
  { username: 'owner1', name: '校级负责人', desc: '指标 / 审核' },
  { username: 'c01', name: '学院负责人', desc: '工作台 / 填报' },
] as const

async function quickLogin(username: string): Promise<void> {
  form.username = username
  form.password = 'Test@123456'
  await handleSubmit()
}
</script>

<template>
  <div class="login-page">
    <div class="login-hero">
      <div class="hero-badge">福州软件职业技术学院</div>
      <h1>五年计划<br />指标管理平台</h1>
      <p>目标量化 · 任务下发 · 过程留痕 · 实时可视</p>
      <div class="hero-circle hero-circle--1" />
      <div class="hero-circle hero-circle--2" />
    </div>

    <div class="login-body">
      <div class="login-card">
        <div v-if="autoLoginPending" class="auto-login">
          <van-loading size="28" color="#2B5BC0" vertical>正在跳转飞书授权…</van-loading>
        </div>

        <template v-else>
          <h2 class="login-title">账号密码登录</h2>
          <div class="field">
            <van-field
              v-model="form.username"
              placeholder="工号"
              autocomplete="username"
              left-icon="user-o"
              clearable
              @keyup.enter="handleSubmit"
            />
          </div>
          <div class="field">
            <van-field
              v-model="form.password"
              type="password"
              placeholder="密码"
              autocomplete="current-password"
              left-icon="lock"
              @keyup.enter="handleSubmit"
            />
          </div>
          <van-button
            block
            type="primary"
            class="login-button btn-height"
            :loading="loading"
            @click="handleSubmit"
          >登 录</van-button>

          <template v-if="feishuAuth">
            <div class="divider-text"><span>或</span></div>
            <van-button
              block
              plain
              type="primary"
              class="btn-height"
              :loading="feishuLoginLoading"
              @click="goFeishuAuthorize"
            >
              <van-icon name="link-o" class="mr-4" /> 飞书登录
            </van-button>
            <p class="feishu-hint">在飞书工作台打开可自动免登</p>
          </template>

          <div class="divider-text"><span>演示账号</span></div>
          <div class="demo-grid">
            <button
              v-for="account in DEMO_ACCOUNTS"
              :key="account.username"
              type="button"
              class="demo-btn"
              :disabled="loading"
              @click="quickLogin(account.username)"
            >
              <span class="demo-name">{{ account.name }}</span>
              <span class="demo-desc">{{ account.desc }}</span>
            </button>
          </div>
        </template>
      </div>
      <p class="login-footer">© 2026 福州软件职业技术学院 发展规划处</p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  background: var(--bg);
}
.login-hero {
  position: relative;
  overflow: hidden;
  padding: calc(48px + var(--safe-top)) 24px 58px;
  background: linear-gradient(140deg, #2b5bc0 0%, #1f4699 100%);
  color: #fff;
}
.hero-badge {
  display: inline-block;
  padding: 4px 10px;
  border: 1px solid rgba(255, 255, 255, 0.35);
  border-radius: 12px;
  font-size: 11px;
  letter-spacing: 1px;
  opacity: 0.92;
}
.login-hero h1 {
  position: relative;
  z-index: 1;
  margin: 16px 0 10px;
  font-size: 30px;
  line-height: 1.25;
  font-weight: 700;
  letter-spacing: 1px;
}
.login-hero p {
  position: relative;
  z-index: 1;
  margin: 0;
  font-size: 12px;
  letter-spacing: 1px;
  opacity: 0.8;
}
.hero-circle {
  position: absolute;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
}
.hero-circle--1 {
  right: -40px;
  top: -30px;
  width: 180px;
  height: 180px;
}
.hero-circle--2 {
  right: 70px;
  bottom: -80px;
  width: 140px;
  height: 140px;
}
.login-body {
  margin-top: -34px;
  padding: 0 16px calc(24px + var(--safe-bottom));
}
.login-card {
  position: relative;
  z-index: 2;
  padding: 20px 18px;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 8px 30px rgba(31, 45, 61, 0.1);
}
.login-title {
  margin: 0 0 16px;
  font-size: 18px;
  font-weight: 600;
  text-align: center;
}
.field {
  margin-bottom: 12px;
}
.field :deep(.van-field) {
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #f8fafd;
}
.login-button {
  margin-top: 4px;
  font-weight: 600;
  letter-spacing: 4px;
}
.divider-text {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 16px 0 12px;
  color: var(--text-placeholder);
  font-size: 12px;
}
.divider-text::before,
.divider-text::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--line);
}
.feishu-hint {
  margin: 8px 0 0;
  color: var(--text-secondary);
  font-size: 11px;
  text-align: center;
}
.mr-4 {
  margin-right: 4px;
}
.demo-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.demo-btn {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  min-height: 52px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  text-align: left;
}
.demo-name {
  color: var(--text);
  font-size: 13px;
  font-weight: 600;
}
.demo-desc {
  color: var(--text-secondary);
  font-size: 11px;
}
.auto-login {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 180px;
}
.login-footer {
  margin: 20px 0 0;
  color: var(--text-placeholder);
  font-size: 11px;
  text-align: center;
}
</style>
