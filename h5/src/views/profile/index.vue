<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog } from 'vant'
import { useUserStore } from '@/stores/user'
import { detectClientEnv } from '@/utils/feishu'
import { getCurrentPlan } from '@/utils/currentPlan'
import { getLastRequestId } from '@/utils/request'

const router = useRouter()
const userStore = useUserStore()

const isFeishuClient = detectClientEnv(navigator.userAgent) !== 'WEB'
const rolesText = computed(() =>
  userStore.roles.map((role) => userStore.enums.Role?.[role] ?? role).join(' / ') || '—')
const unitName = computed(() => userStore.userInfo?.unit?.name ?? '校级账号')
const currentPlan = getCurrentPlan()

const modules = computed(() => {
  const list = [
    { name: '首页计划', desc: '计划列表与新建', icon: 'wap-home-o', path: '/plans', hide: userStore.isUnitOwnerOnly },
    { name: '学院工作台', desc: '任务填报与材料', icon: 'todo-list-o', path: '/workbench' },
    { name: '指标总表', desc: '指标、年度目标与任务', icon: 'apps-o', path: '/indicators', hide: userStore.isUnitOwnerOnly, permission: '' },
    { name: '分解下发', desc: '学院目标分解与下发', icon: 'cluster-o', path: '/allocation', permission: 'allocation:edit' },
    { name: '模板管理', desc: '计划模板维护', icon: 'description', path: '/templates', permission: 'plan:manage' },
    { name: '材料库', desc: '佐证材料检索与导出', icon: 'folder-o', path: '/materials', permission: 'material:view' },
    { name: '审核中心', desc: '填报审核与核减', icon: 'records-o', path: '/reviews', permission: 'submission:review' },
    { name: '调整审批', desc: '延期 / 转移 / 目标调整', icon: 'exchange', path: '/adjustments', permissions: ['adjustment:apply', 'adjustment:decide'] },
    { name: '统计报表', desc: '完成率、排名与下钻', icon: 'bar-chart-o', path: '/stats', permission: 'stats:view' },
    { name: '操作日志', desc: '审计留痕与导出', icon: 'notes-o', path: '/logs', permission: 'audit-log:view' },
    { name: '待办与消息', desc: '统一待办和通知', icon: 'bell', path: '/todos' },
    { name: '可视化大屏', desc: '数据大屏', icon: 'desktop-o', path: '/screen', permission: 'screen:view' },
    { name: '系统管理', desc: '平台配置与人员', icon: 'setting-o', path: '/system', permission: 'system:manage' },
  ]
  return list.filter((item) => {
    if (item.hide) return false
    if (item.permission && !userStore.can(item.permission)) return false
    if (item.permissions && !userStore.can(...item.permissions)) return false
    return true
  })
})

async function logout(): Promise<void> {
  try {
    await showConfirmDialog({
      title: '退出登录',
      message: '确认退出当前账号？',
      confirmButtonText: '退出',
    })
  } catch {
    return
  }
  await userStore.logout()
  router.replace('/login')
}

function go(path: string): void {
  if (path === '/screen') {
    const planId = currentPlan?.id
    router.push(planId ? `/screen/${planId}` : '/screen')
    return
  }
  router.push(path)
}

const lastRequestId = getLastRequestId()
</script>

<template>
  <div class="page page--tabbar">
    <div class="profile-head">
      <div class="avatar">{{ userStore.userInfo?.name?.slice(0, 1) ?? '?' }}</div>
      <div class="identity">
        <h1>{{ userStore.userInfo?.name ?? '未登录' }}</h1>
        <p>{{ unitName }}</p>
        <p class="small">角色：{{ rolesText }}</p>
      </div>
    </div>

    <div class="card">
      <div class="kv-list">
        <div class="kv"><span class="kv__label">当前项目</span><span class="kv__value">{{ currentPlan?.name ?? '未选择' }}</span></div>
        <div class="kv"><span class="kv__label">权限数量</span><span class="kv__value">{{ userStore.permissions.length }}</span></div>
        <div class="kv"><span class="kv__label">数据范围</span><span class="kv__value">{{ userStore.dataScope?.type === 'ALL' ? '全校' : userStore.dataScope?.type === 'UNIT' ? '本单元' : userStore.dataScope?.type === 'INDICATOR' ? '本人负责指标' : '—' }}</span></div>
        <div class="kv"><span class="kv__label">客户端</span><span class="kv__value">{{ isFeishuClient ? '飞书内置浏览器' : '手机浏览器' }}</span></div>
        <div v-if="lastRequestId" class="kv"><span class="kv__label">最近请求编号</span><span class="kv__value mono">{{ lastRequestId }}</span></div>
      </div>
    </div>

    <div class="section-title">功能模块</div>
    <div class="module-grid">
      <button v-for="item in modules" :key="item.name" type="button" class="module-item" @click="go(item.path)">
        <van-icon :name="item.icon" size="22" />
        <strong>{{ item.name }}</strong>
        <span>{{ item.desc }}</span>
      </button>
    </div>

    <van-button
      v-if="!isFeishuClient"
      block
      type="danger"
      plain
      class="logout-btn"
      @click="logout"
    >退出登录</van-button>

    <p class="version">双高管理平台 · H5 版</p>
  </div>
</template>

<style scoped>
.profile-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin: -12px -12px 12px;
  padding: 22px 18px 26px;
  border-radius: 0 0 18px 18px;
  background: linear-gradient(135deg, #2b5bc0, #1f4699);
  color: #fff;
}
.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 58px;
  height: 58px;
  border: 2px solid rgba(255, 255, 255, 0.5);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.18);
  font-size: 24px;
  font-weight: 600;
  flex-shrink: 0;
}
.identity {
  min-width: 0;
}
.identity h1 {
  margin: 0;
  font-size: 21px;
}
.identity p {
  margin: 3px 0 0;
  font-size: 12px;
  opacity: 0.85;
}
.mono {
  font-family: Consolas, Menlo, monospace;
  font-size: 11px;
  word-break: break-all;
}
.module-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.module-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  min-height: 86px;
  padding: 12px;
  border: none;
  border-radius: var(--radius);
  background: #fff;
  color: var(--brand);
  text-align: left;
  box-shadow: var(--shadow);
}
.module-item strong {
  color: var(--text);
  font-size: 13px;
}
.module-item span {
  color: var(--text-secondary);
  font-size: 10px;
  line-height: 15px;
}
.logout-btn {
  margin-top: 18px;
  min-height: 44px;
}
.version {
  margin: 16px 0 4px;
  color: var(--text-placeholder);
  font-size: 10px;
  text-align: center;
}
</style>
