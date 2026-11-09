<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const groups = computed(() => [
  {
    title: '组织与人员',
    items: [
      { key: 'units', name: '执行单元', desc: '增删改、启停与承接新任务配置', icon: 'cluster-o' },
      { key: 'org', name: '组织架构', desc: '飞书同步的组织架构只读树', icon: 'share-o' },
      { key: 'major-groups', name: '专业群', desc: '专业群与依托单元、负责人', icon: 'friends-o' },
      { key: 'users', name: '用户管理', desc: '账号、角色、启停与密码重置', icon: 'manager-o' },
    ],
  },
  {
    title: '指标配置',
    items: [
      { key: 'directions', name: '建设方向', desc: '方向维护与指标归属', icon: 'guide-o' },
      { key: 'dicts', name: '字典管理', desc: '自定义字典与内置枚举', icon: 'orders-o' },
    ],
  },
  {
    title: '平台设置',
    items: [
      { key: 'settings', name: '系统参数', desc: '文件、预警、年度节点参数', icon: 'setting-o' },
      { key: 'feishu', name: '飞书集成', desc: '应用状态、回调地址与组织同步', icon: 'link-o' },
    ],
  },
])

const isAdmin = computed(() => userStore.roles.includes('ADMIN') && userStore.can('system:manage'))

function open(key: string): void {
  router.push(`/system/${key}`)
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h1>系统管理</h1>
      <p class="muted small">平台级配置与组织人员维护，仅平台管理员可见</p>
    </div>
    <div v-if="!isAdmin" class="warn-box">当前账号没有系统管理权限</div>
    <template v-for="group in groups" :key="group.title">
      <div class="section-title">{{ group.title }}</div>
      <div class="menu-list">
        <button v-for="item in group.items" :key="item.key" type="button" class="menu-item" @click="open(item.key)">
          <span class="menu-icon"><van-icon :name="item.icon" size="19" /></span>
          <span class="menu-main">
            <strong>{{ item.name }}</strong>
            <small>{{ item.desc }}</small>
          </span>
          <van-icon name="arrow" size="14" color="#A2ADBD" />
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page-head {
  margin: 2px 0 12px;
}
.page-head h1 {
  margin: 0;
  font-size: 22px;
}
.page-head p {
  margin: 3px 0 0;
}
.warn-box {
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fdf3e5;
  color: #8f5d16;
  font-size: 12px;
}
.menu-list {
  display: flex;
  flex-direction: column;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
  overflow: hidden;
}
.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 62px;
  padding: 10px 12px;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--text);
  text-align: left;
}
.menu-item:last-child {
  border-bottom: none;
}
.menu-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: #eef3fc;
  color: var(--brand);
  flex-shrink: 0;
}
.menu-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.menu-main strong {
  font-size: 14px;
}
.menu-main small {
  color: var(--text-secondary);
  font-size: 11px;
}
</style>
