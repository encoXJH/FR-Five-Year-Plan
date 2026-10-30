<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Avatar, Lock, Medal, OfficeBuilding, School, UserFilled } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/api'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ account_name: 'school', password: 'Dev@2026' })
const rules: FormRules = { account_name: [{ required: true, message: '请输入登录账号', trigger: 'blur' }], password: [{ required: true, message: '请输入登录密码', trigger: 'blur' }] }
const demos = [
  { account: 'admin', name: '平台管理员', role: '组织、账号与运行配置', icon: UserFilled },
  { account: 'leader', name: '校领导', role: '全局进度与划转终审', icon: Medal },
  { account: 'school', name: '校级项目干系人', role: '计划、下发与业务审核', icon: School },
  { account: 'college', name: '学院项目干系人', role: '任务接收与年度填报', icon: OfficeBuilding },
]
function selectDemo(account: string) { form.account_name = account; form.password = 'Dev@2026' }
async function submit() {
  if (!await formRef.value?.validate().catch(() => false)) return
  loading.value = true
  try {
    await auth.login(form.account_name, form.password)
    ElMessage.success(`欢迎，${auth.user?.user_name || '用户'}`)
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : '/dashboard'
    await router.replace(redirect)
  } catch (error) { ElMessage.error(errorMessage(error)) } finally { loading.value = false }
}
</script>
<template>
  <div class="login-page">
    <section class="login-hero">
      <div class="login-brand"><div class="brand-mark">五</div><span>五年建设任务管理平台</span></div>
      <div class="hero-copy"><h1>让每一项建设目标<br />可执行、可认定、可追溯</h1><p>从建设方向到关键产出指标，从学院年度任务到人工审核认定，以统一口径支撑 2026—2030 年建设全过程。</p><div class="hero-features"><span class="hero-feature">三级任务体系</span><span class="hero-feature">版本化审核</span><span class="hero-feature">全链路留痕</span></div></div>
      <div class="login-footer">目标量化 · 学院协同 · 材料佐证 · 动态调整 · 分层甘特</div>
    </section>
    <section class="login-form-side">
      <div class="login-form-wrap">
        <h2>欢迎登录</h2><p>使用平台账号进入您的工作台</p>
        <el-alert v-if="route.query.expired" title="会话已失效，请重新登录" type="warning" :closable="false" style="margin-bottom:16px" />
        <el-form ref="formRef" :model="form" :rules="rules" size="large" label-position="top" @keyup.enter="submit">
          <el-form-item label="登录账号" prop="account_name"><el-input v-model="form.account_name" placeholder="请输入账号" autocomplete="username"><template #prefix><el-icon><Avatar /></el-icon></template></el-input></el-form-item>
          <el-form-item label="登录密码" prop="password"><el-input v-model="form.password" type="password" show-password placeholder="请输入密码" autocomplete="current-password"><template #prefix><el-icon><Lock /></el-icon></template></el-input></el-form-item>
          <el-button type="primary" size="large" style="width:100%;margin-top:8px" :loading="loading" @click="submit">登 录</el-button>
        </el-form>
        <div class="demo-title">四角色演示账号快捷入口</div>
        <div class="demo-accounts"><button v-for="demo in demos" :key="demo.account" class="demo-account" @click="selectDemo(demo.account)"><el-icon><component :is="demo.icon" /></el-icon><span><strong>{{ demo.name }}</strong><small>{{ demo.account }} · {{ demo.role }}</small></span></button></div>
        <div class="local-note">演示密码：Dev@2026 · 仅用于本地环境展示</div>
      </div>
    </section>
  </div>
</template>
