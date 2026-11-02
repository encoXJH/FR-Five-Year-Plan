<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { menuGroups } from './menu'
import type { MenuGroup, MenuItem } from './menu'

withDefaults(defineProps<{ collapsed?: boolean }>(), { collapsed: false })

const route = useRoute()
const userStore = useUserStore()

function isVisible(item: MenuItem): boolean {
  if (item.visibleWhen && !item.visibleWhen(route)) return false
  if (item.permissions && !userStore.can(...item.permissions)) return false
  if (
    item.hideForRolesOnly &&
    userStore.roles.length > 0 &&
    userStore.roles.every((role) => item.hideForRolesOnly!.includes(role))
  ) {
    return false
  }
  return true
}

const visibleGroups = computed<MenuGroup[]>(() =>
  menuGroups
    .map((group) => ({
      ...group,
      items: group.items.filter(isVisible),
    }))
    .filter((group) => group.items.length > 0),
)

function resolveIndex(item: MenuItem): string {
  return item.resolvePath ? item.resolvePath(route) : item.path
}
</script>

<template>
  <el-menu
    class="app-menu"
    :class="{ 'is-collapsed': collapsed }"
    :default-active="route.path"
    :collapse="collapsed"
    :collapse-transition="false"
    router
  >
    <el-menu-item-group v-for="group in visibleGroups" :key="group.key">
      <template #title>{{ group.title }}</template>
      <el-menu-item v-for="item in group.items" :key="item.path" :index="resolveIndex(item)">
        <el-icon v-if="item.icon" :size="16"><component :is="item.icon" /></el-icon>
        <template #title>{{ item.title }}</template>
      </el-menu-item>
    </el-menu-item-group>
  </el-menu>
</template>

<style scoped>
.app-menu {
  border-right: none;
  padding: var(--space-2) 0;
}

.app-menu :deep(.el-menu-item-group__title) {
  padding: var(--space-2) var(--space-5) var(--space-1);
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 20px;
  white-space: nowrap;
}

.app-menu :deep(.el-menu-item) {
  height: 40px;
  margin: 0 var(--space-2);
  border-radius: var(--el-border-radius-base);
  line-height: 40px;
  transition: color 0.2s ease, background-color 0.2s ease;
}

.app-menu :deep(.el-menu-item.is-active) {
  position: relative;
  color: var(--el-color-primary);
  background-color: var(--el-color-primary-light-9);
  font-weight: 600;
}

.app-menu :deep(.el-menu-item.is-active)::before {
  position: absolute;
  top: 6px;
  bottom: 6px;
  left: calc(-1 * var(--space-2));
  width: 3px;
  border-radius: 2px;
  background-color: var(--el-color-primary);
  content: '';
}

.app-menu.is-collapsed :deep(.el-menu-item-group__title) {
  height: 1px;
  margin: var(--space-2) var(--space-3);
  padding: 0;
  overflow: hidden;
  background-color: var(--el-border-color-lighter);
  font-size: 0;
  line-height: 1px;
}

.app-menu.is-collapsed :deep(.el-menu-item) {
  justify-content: center;
  margin: 0 var(--space-2);
  padding: 0;
}

.app-menu.is-collapsed :deep(.el-menu-item .el-menu-tooltip__trigger) {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  padding: 0;
}

.app-menu.is-collapsed :deep(.el-menu-item .el-icon) {
  margin: 0;
}

@media (min-width: 769px) and (max-height: 700px) {
  .app-menu {
    padding: 2px 0;
  }

  .app-menu :deep(.el-menu-item-group__title) {
    padding: 2px var(--space-3) 0;
    font-size: 10px;
    line-height: 12px;
  }

  .app-menu :deep(.el-menu-item) {
    height: 24px;
    font-size: 11px;
    line-height: 24px;
  }

  .app-menu :deep(.el-menu-item.is-active)::before {
    top: 3px;
    bottom: 3px;
  }
}
</style>
