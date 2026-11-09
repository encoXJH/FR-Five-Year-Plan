<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
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
import type { LoginRequest } from '@/types/auth'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive<LoginRequest>({
  username: '',
  password: '',
})

const rules: FormRules<LoginRequest> = {
  username: [{ required: true, message: '请输入工号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

// ---------- 飞书登录（P01 增强） ----------
/** 当前客户端环境（WEB / FEISHU_PC / FEISHU_MOBILE，API 约定 4.1） */
const clientEnv: ClientEnv = detectClientEnv(window.navigator.userAgent)
/** 后端飞书配置探测结果（null = 不可用，自动隐藏飞书入口） */
const feishuAuth = ref<FeishuAuthorizeInfo | null>(null)
/** 飞书客户端内自动免登进行中：不显示登录表单 */
const autoLoginPending = ref(clientEnv !== 'WEB')
const feishuLoginLoading = ref(false)
function redirectTarget(): string {
  if (userStore.isUnitOwnerOnly) return userStore.homePath
  if (userStore.roles.includes('ADMIN')) return '/plans'
  return typeof route.query.redirect === 'string' ? route.query.redirect : '/'
}

/** 生成并保存 OAuth state，再跳转飞书授权页 */
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
    ElMessage.error('暂时无法发起飞书登录，请稍后重试')
    return false
  }
}

onMounted(async () => {
  // 页面加载即探测飞书可用性（静默；20006 / 90001 视为未配置）
  let info: FeishuAuthorizeInfo | null = null
  try {
    info = normalizeAuthorizeInfo(await fetchFeishuAuthorizeUrl())
  } catch (error) {
    if (!(error instanceof ApiError && isFeishuUnavailableCode(error.code))) {
      // 网络异常等同样按不可用处理，只影响飞书入口显示
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
  const formEl = formRef.value
  if (!formEl) return
  const valid = await formEl.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userStore.login(form)
    router.push(redirectTarget())
  } catch {
    // 错误提示已由请求拦截器统一展示
  } finally {
    loading.value = false
  }
}

// ---------- 演示账号快捷登录（验收环境显示） ----------
const showDemoAccounts = true

const DEMO_ACCOUNTS = [
  { username: 'admin', name: '平台管理员', desc: '全部菜单' },
  { username: 'leader', name: '校领导', desc: '审批 / 报表 / 大屏' },
  { username: 'owner1', name: '校级项目负责人', desc: '指标 / 审核 / 初审' },
  { username: 'c01', name: '学院负责人（智能产业学院）', desc: '工作台 / 填报' },
] as const

async function quickLogin(username: string): Promise<void> {
  form.username = username
  form.password = 'Test@123456'
  await handleSubmit()
}
</script>

<template>
  <div class="login-page">
    <div class="brand-pane">
      <div class="brand-content">
        <div class="brand-school">福州软件职业技术学院</div>
        <div class="brand-platform">五年计划指标管理平台</div>
        <div class="brand-slogan">目标量化 · 任务下发 · 过程留痕 · 实时可视</div>
      </div>
      <div class="brand-footer">© 2026 福州软件职业技术学院 发展规划处</div>
    </div>
    <div class="form-pane">
      <!-- 飞书客户端内直接跳转 OAuth：不显示登录表单 -->
      <el-card v-if="autoLoginPending" class="login-card" shadow="never">
        <div v-loading="true" class="auto-login-pane" element-loading-text="正在跳转飞书授权…" />
      </el-card>

      <el-card v-else class="login-card" shadow="never">
        <h1 class="login-title">账号密码登录</h1>
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          size="large"
          @submit.prevent="handleSubmit"
        >
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="工号" autocomplete="username" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              show-password
              autocomplete="current-password"
            />
          </el-form-item>
          <el-form-item>
            <el-button
              class="login-button"
              type="primary"
              native-type="submit"
              :loading="loading"
            >
              登 录
            </el-button>
          </el-form-item>
        </el-form>

        <!-- 飞书入口：后端已配置时显示，自动免登失败后可手动重试 -->
        <template v-if="feishuAuth">
          <el-divider class="feishu-divider">或</el-divider>
          <el-button
            class="login-button"
            plain
            type="primary"
            :loading="feishuLoginLoading"
            @click="goFeishuAuthorize"
          >
            飞书登录
          </el-button>
          <p class="feishu-hint">在飞书工作台打开可自动免登</p>
        </template>

        <!-- 演示账号一键登录：验收环境显示 -->
        <template v-if="showDemoAccounts">
          <el-divider class="feishu-divider">演示账号</el-divider>
          <div class="demo-accounts">
            <el-button
              v-for="account in DEMO_ACCOUNTS"
              :key="account.username"
              class="demo-account"
              size="small"
              :disabled="loading"
              @click="quickLogin(account.username)"
            >
              <span class="demo-name">{{ account.name }}</span>
              <span class="demo-desc">{{ account.desc }}</span>
            </el-button>
          </div>
        </template>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  height: 100%;
  min-width: var(--app-content-min-width);
}

.brand-pane {
  position: relative;
  flex: 0 0 60%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-color-primary-dark-2);
  background: var(--el-color-primary-light-9);
}

.brand-content {
  text-align: left;
}

.brand-school {
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
  opacity: 0.9;
}

.brand-platform {
  margin-top: var(--space-2);
  font-size: 36px;
  font-weight: 600;
  line-height: 48px;
}

.brand-slogan {
  margin-top: var(--space-4);
  font-size: 14px;
  line-height: 22px;
  opacity: 0.85;
  letter-spacing: 2px;
}

.brand-footer {
  position: absolute;
  left: var(--space-6);
  bottom: var(--space-5);
  font-size: 12px;
  line-height: 20px;
  opacity: 0.7;
}

.form-pane {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--el-bg-color-page);
}

.login-card {
  width: 400px;
  border-radius: var(--radius-dialog);
  padding: var(--space-3);
}

.login-title {
  margin: 0 0 var(--space-5);
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
  text-align: center;
}

.login-button {
  width: 100%;
}

.auto-login-pane {
  height: 200px;
}

.feishu-divider {
  margin: var(--space-5) 0 var(--space-4);
}

.feishu-hint {
  margin: var(--space-2) 0 0;
  font-size: 12px;
  line-height: 20px;
  text-align: center;
  color: var(--el-text-color-secondary);
}

.demo-accounts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-2);
}

.demo-account {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  height: auto;
  padding: var(--space-2) var(--space-3);
  margin: 0;
}

.demo-account :deep(span) {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.demo-name {
  font-weight: 600;
}

.demo-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 移动端（≤768px）：品牌区压缩为顶部横幅，表单单列（UI 规范 5.2） */
@media (max-width: 768px) {
  .login-page {
    min-width: 0;
    flex-direction: column;
  }

  .brand-pane {
    flex: none;
    padding: var(--space-6) var(--space-4) var(--space-8);
  }

  .brand-school {
    font-size: 16px;
    line-height: 24px;
  }

  .brand-platform {
    font-size: 24px;
    line-height: 34px;
  }

  .brand-slogan {
    font-size: 12px;
    letter-spacing: 1px;
  }

  .brand-footer {
    display: none;
  }

  .form-pane {
    align-items: flex-start;
    padding: var(--space-4);
  }

  .login-card {
    width: 100%;
    max-width: 400px;
  }
}
</style>
