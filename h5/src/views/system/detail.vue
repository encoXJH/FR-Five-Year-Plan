<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import UnitsPanel from './components/UnitsPanel.vue'
import OrgPanel from './components/OrgPanel.vue'
import MajorGroupsPanel from './components/MajorGroupsPanel.vue'
import UsersPanel from './components/UsersPanel.vue'
import DirectionsPanel from './components/DirectionsPanel.vue'
import DictsPanel from './components/DictsPanel.vue'
import SettingsPanel from './components/SettingsPanel.vue'
import FeishuPanel from './components/FeishuPanel.vue'

const route = useRoute()
const router = useRouter()
const tab = computed(() => String(route.params.tab ?? ''))

const panels: Record<string, { title: string; component: unknown }> = {
  units: { title: '执行单元', component: UnitsPanel },
  org: { title: '组织架构', component: OrgPanel },
  'major-groups': { title: '专业群', component: MajorGroupsPanel },
  users: { title: '用户管理', component: UsersPanel },
  directions: { title: '建设方向', component: DirectionsPanel },
  dicts: { title: '字典管理', component: DictsPanel },
  settings: { title: '系统参数', component: SettingsPanel },
  feishu: { title: '飞书集成', component: FeishuPanel },
}
const current = computed(() => panels[tab.value] ?? null)
</script>

<template>
  <div class="page" :class="{ 'page--footer': tab === 'settings' }">
    <button type="button" class="back-link" @click="router.push('/system')">
      <van-icon name="arrow-left" size="14" /> 返回系统管理
    </button>
    <component :is="current?.component" v-if="current" />
    <div v-else class="empty-state">未知的系统管理页面</div>
  </div>
</template>

<style scoped>
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 36px;
  margin-bottom: 8px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--brand);
  font-size: 13px;
}
</style>
