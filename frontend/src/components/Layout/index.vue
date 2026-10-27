<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Expand, Fold } from '@element-plus/icons-vue'
import HeaderBar from './HeaderBar.vue'
import SideMenu from './SideMenu.vue'
import { listPlans } from '@/api/plan'
import type { PlanCard } from '@/types/plan'

const route = useRoute()

const pageTitle = computed(() => (typeof route.meta.title === 'string' ? route.meta.title : ''))
const isHome = computed(() => route.path === '/plans' || route.path === '/dashboard')

// ---------- 侧栏折叠（UI 规范 5.1），状态持久化到 localStorage ----------
const COLLAPSED_KEY = 'fzsu.menuCollapsed'
const collapsed = ref(localStorage.getItem(COLLAPSED_KEY) === '1')
const asideWidth = computed(() =>
  collapsed.value ? 'var(--app-aside-collapsed-width)' : 'var(--app-aside-width)',
)

function toggleCollapse(): void {
  collapsed.value = !collapsed.value
  localStorage.setItem(COLLAPSED_KEY, collapsed.value ? '1' : '0')
}

// ---------- 面包屑计划名（首页 / 计划名 / 页面） ----------
const plans = ref<PlanCard[]>([])
const routePlanId = computed(() =>
  typeof route.params.planId === 'string' ? route.params.planId : '',
)
const currentPlanName = computed(
  () => plans.value.find((p) => p.id === routePlanId.value)?.name ?? '',
)

onMounted(() => {
  listPlans()
    .then((list) => {
      plans.value = list
    })
    .catch(() => undefined)
})

/** 移动端（≤768px）侧边栏收起为抽屉，由顶栏汉堡按钮打开 */
const menuDrawerVisible = ref(false)

function openMenuDrawer(): void {
  menuDrawerVisible.value = true
}

function closeMenuDrawer(): void {
  menuDrawerVisible.value = false
}
</script>

<template>
  <el-container class="app-layout">
    <el-header class="app-header" :height="'56px'">
      <HeaderBar @toggle-menu="openMenuDrawer" />
    </el-header>
    <el-container class="app-body">
      <el-aside class="app-aside" :width="asideWidth">
        <div class="aside-inner">
          <SideMenu :collapsed="collapsed" class="aside-menu" />
          <el-tooltip :content="collapsed ? '展开导航' : '收起导航'" placement="right" :show-after="300">
            <button
              type="button"
              class="collapse-toggle"
              :class="{ 'is-collapsed': collapsed }"
              :aria-label="collapsed ? '展开导航' : '收起导航'"
              @click="toggleCollapse"
            >
              <el-icon :size="17"><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
              <span v-if="!collapsed" class="collapse-label">收起导航</span>
            </button>
          </el-tooltip>
        </div>
      </el-aside>
      <el-container class="app-right">
        <div class="app-breadcrumb">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/plans' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item
              v-if="routePlanId && currentPlanName"
              :to="{ path: `/plans/${routePlanId}` }"
            >
              {{ currentPlanName }}
            </el-breadcrumb-item>
            <el-breadcrumb-item v-if="!isHome && pageTitle">{{ pageTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <el-main class="app-main">
          <router-view />
        </el-main>
      </el-container>
    </el-container>

    <el-drawer
      v-model="menuDrawerVisible"
      direction="ltr"
      size="216px"
      :with-header="false"
      class="menu-drawer"
    >
      <div @click="closeMenuDrawer">
        <SideMenu />
      </div>
    </el-drawer>
  </el-container>
</template>

<style scoped>
.app-layout {
  height: 100%;
}

.app-header {
  padding: 0;
  background-color: var(--el-color-primary);
  border-bottom: 2px solid var(--el-color-primary-dark-2);
}

.app-body {
  height: calc(100% - var(--app-header-height));
  overflow-x: auto;
}

/* 折叠直接切换宽度，避免工作台表格随宽度过渡逐帧重排 */
.app-aside {
  overflow: hidden;
  background-color: var(--el-bg-color);
  border-right: 1px solid var(--el-border-color-light);
}

.aside-inner {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.aside-menu {
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
}

.collapse-toggle {
  display: flex;
  flex: 0 0 44px;
  gap: var(--space-2);
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 44px;
  padding: 0 var(--space-4);
  border: none;
  border-top: 1px solid var(--el-border-color-light);
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: 12px;
  cursor: pointer;
  outline: none;
  transition: color 0.2s ease, background-color 0.2s ease;
}

.collapse-toggle.is-collapsed {
  padding: 0;
}

.collapse-toggle:hover {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.collapse-toggle:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
}

.collapse-label {
  white-space: nowrap;
}

.app-right {
  min-width: calc(var(--app-content-min-width) - var(--app-aside-width));
  flex-direction: column;
}

.app-breadcrumb {
  display: flex;
  align-items: center;
  height: var(--app-breadcrumb-height);
  padding: 0 var(--space-5);
  background-color: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-light);
}

.app-main {
  padding: var(--space-5);
  background-color: var(--el-bg-color-page);
}

/* 移动端（≤768px）：侧边栏隐藏改顶栏抽屉菜单，内容区取消最小宽度（UI 规范 5.2） */
@media (max-width: 768px) {
  .app-aside {
    display: none;
  }

  .app-right {
    min-width: 0;
  }

  .app-breadcrumb {
    display: none;
  }

  .app-main {
    padding: var(--space-3);
  }
}
</style>
