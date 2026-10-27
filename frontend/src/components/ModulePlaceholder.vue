<script setup lang="ts">
defineProps<{ title: string }>()
</script>

<template>
  <div class="page-header">
    <h1 class="page-title">{{ title }}</h1>
  </div>
  <el-card class="placeholder-card" shadow="never">
    <el-empty :description="`${title}模块建设中，敬请期待`" />
  </el-card>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
}

.placeholder-card {
  border-radius: var(--radius-card);
  padding: var(--space-8) 0;
}
</style>
