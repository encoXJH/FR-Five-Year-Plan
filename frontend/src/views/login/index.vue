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
  isFeishuUnavailableCode,
  normalizeAuthorizeInfo,
  type ClientEnv,
  type FeishuAuthorizeInfo,
} from '@/utils/feishu'
import { loadFeishuSdk, requestFeishuAccessCode } from '@/utils/feishuSdk'
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
  return typeof route.query.redirect === 'string' ? route.query.redirect : '/'
}

/** 飞书免登 code 换 Token 的统一收尾（20007 未绑定单独引导） */
async function doFeishuLogin(code: string): Promise<boolean> {
  try {
    await userStore.loginFeishu(code, clientEnv)
    router.push(redirectTarget())
    return true
  } catch (error) {
    if (error instanceof ApiError && error.code === 20007) {
      ElMessage.error('飞书账号未绑定平台用户，请联系管理员完成绑定后重试')
    } else if (error instanceof ApiError && error.message) {
      ElMessage.error(error.message)
    } else {
      ElMessage.error('飞书登录失败，请改用账号密码登录')
    }
    return false
  }
}

/** 浏览器环境：跳转飞书 OAuth 授权页（API 约定 4.1） */
function goFeishuAuthorize(): void {
  const url = feishuAuth.value?.url
  if (!url) return
  feishuLoginLoading.value = true
  window.location.assign(url)
}

/**
 * 飞书客户端内：JSAPI 免登。
 * 参考官方文档：https://open.feishu.cn/document/uYjL24iN/uITMuITMuITM
 * JSAPI 加载或授权失败时降级到账号密码登录。
 */
async function tryAutoFeishuLogin(): Promise<void> {
  const info = feishuAuth.value
  if (!info?.appId) {
    // 拿不到 AppID 无法调 JSAPI，降级到密码登录
    autoLoginPending.value = false
    return
  }
  try {
    await loadFeishuSdk()
    const code = await requestFeishuAccessCode(info.appId)
    const ok = await doFeishuLogin(code)
    if (!ok) autoLoginPending.value = false
  } catch {
    ElMessage.warning('飞书免登不可用，请使用账号密码登录')
    autoLoginPending.value = false
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
    await tryAutoFeishuLogin()
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

// ---------- 演示账号快捷登录（仅开发/验收环境显示，生产构建不含） ----------
const showDemoAccounts = import.meta.env.DEV

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
      <!-- 飞书客户端内自动免登中：不显示登录表单 -->
      <el-card v-if="autoLoginPending" class="login-card" shadow="never">
        <div v-loading="true" class="auto-login-pane" element-loading-text="正在使用飞书免登…" />
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

        <!-- 飞书入口：仅在浏览器环境且后端已配置飞书时显示 -->
        <template v-if="feishuAuth && clientEnv === 'WEB'">
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

        <!-- 演示账号一键登录：仅开发环境（import.meta.env.DEV）渲染，不进入生产构建 -->
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
  color: #ffffff;
  background: linear-gradient(135deg, var(--el-color-primary-dark-2) 0%, var(--el-color-primary) 60%, var(--el-color-primary-light-3) 100%);
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
