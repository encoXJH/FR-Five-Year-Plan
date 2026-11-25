<script setup lang="ts">
import UserPanel from '@/views/system/components/UserPanel.vue'
</script>

<template>
  <div class="user-directory-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">用户管理</h1>
        <p class="page-subtitle">查询全校人员及其所属学院、角色和账号状态，人员信息只读。</p>
      </div>
    </div>
    <el-card shadow="never">
      <UserPanel readonly unit-owner-directory />
    </el-card>
  </div>
</template>

<style scoped>
.user-directory-page {
  min-width: 0;
}

.page-header {
  margin-bottom: var(--space-4);
}

.page-title {
  margin: 0;
  font-size: 20px;
  line-height: 28px;
}

.page-subtitle {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
