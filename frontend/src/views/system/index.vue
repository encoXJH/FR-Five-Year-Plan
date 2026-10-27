<script setup lang="ts">
import { computed, ref } from 'vue'
import { useUserStore } from '@/stores/user'
import OrgUnitPanel from './components/OrgUnitPanel.vue'
import DirectionPanel from './components/DirectionPanel.vue'
import MajorGroupPanel from './components/MajorGroupPanel.vue'
import UserPanel from './components/UserPanel.vue'
import DictPanel from './components/DictPanel.vue'
import SettingsPanel from './components/SettingsPanel.vue'
import FeishuPanel from './components/FeishuPanel.vue'

const userStore = useUserStore()
const activeTab = ref('orgUnits')
const isAdmin = computed(() => userStore.roles.includes('ADMIN'))
const canManageDirections = computed(() => isAdmin.value && userStore.can('system:manage'))
</script>

<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">系统管理</h1>
    </div>
    <el-card shadow="never">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="执行单元" name="orgUnits" lazy><OrgUnitPanel /></el-tab-pane>
        <el-tab-pane v-if="canManageDirections" label="S 级任务" name="directions" lazy><DirectionPanel /></el-tab-pane>
        <el-tab-pane label="专业群" name="majorGroups" lazy><MajorGroupPanel /></el-tab-pane>
        <el-tab-pane label="用户与角色" name="users" lazy><UserPanel /></el-tab-pane>
        <el-tab-pane label="字典" name="dicts" lazy><DictPanel /></el-tab-pane>
        <el-tab-pane label="参数配置" name="settings" lazy><SettingsPanel /></el-tab-pane>
        <el-tab-pane label="飞书集成" name="feishu" lazy><FeishuPanel /></el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
}
</style>
