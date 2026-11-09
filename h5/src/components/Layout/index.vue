<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const appStore = useAppStore()

const rootPages = new Set(['Plans', 'Workbench', 'Todos', 'Profile'])
const hideNav = computed(() => route.meta.hideNav === true)
const hideTabbar = computed(() => route.meta.hideTabbar === true)
const showBack = computed(() => !rootPages.has(String(route.name ?? '')))

const title = computed(() => (typeof route.meta.title === 'string' ? route.meta.title : ''))

function goBack(): void {
  if (window.history.length > 1) router.back()
  else router.push(userStore.homePath)
}

function openNotifications(): void {
  router.push({ path: '/todos', query: { tab: 'notifications' } })
}

let timer: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  appStore.refreshUnread()
  timer = setInterval(() => appStore.refreshUnread(), 60000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="app-container">
    <van-nav-bar
      v-if="!hideNav"
      fixed
      placeholder
      :title="title"
      :left-arrow="showBack"
      safe-area-inset-top
      @click-left="goBack"
    >
      <template #right>
        <div class="nav-bell" aria-label="消息中心" @click="openNotifications">
          <van-badge :content="appStore.unreadCount > 0 ? (appStore.unreadCount > 99 ? '99+' : appStore.unreadCount) : ''">
            <van-icon name="bell" size="21" />
          </van-badge>
        </div>
      </template>
    </van-nav-bar>

    <router-view />

    <van-tabbar
      v-if="!hideTabbar"
      route
      fixed
      safe-area-inset-bottom
      active-color="#2B5BC0"
      inactive-color="#8A97A8"
      class="main-tabbar"
    >
      <van-tabbar-item
        v-if="!userStore.isUnitOwnerOnly"
        replace
        to="/plans"
        icon="wap-home-o"
      >
        首页
      </van-tabbar-item>
      <van-tabbar-item replace to="/workbench" icon="todo-list-o">工作台</van-tabbar-item>
      <van-tabbar-item replace to="/todos" icon="records-o">待办</van-tabbar-item>
      <van-tabbar-item replace to="/profile" icon="user-o">我的</van-tabbar-item>
    </van-tabbar>
  </div>
</template>

<style scoped>
.nav-bell {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  margin-right: -12px;
  color: var(--text);
}
.main-tabbar {
  max-width: 720px;
  left: 50%;
  transform: translateX(-50%);
}
</style>
