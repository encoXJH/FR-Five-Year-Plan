<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getLastRequestId } from '@/utils/request'

const router = useRouter()
const requestId = ref(getLastRequestId())

function copyRequestId(): void {
  void navigator.clipboard?.writeText(requestId.value).then(() => ElMessage.success('已复制请求编号'))
}
</script>

<template>
  <el-result icon="warning" title="暂无访问权限">
    <template #sub-title>
      <p>你没有访问该页面的权限，如有疑问请联系平台管理员</p>
      <p v-if="requestId" class="request-id">
        请求编号：{{ requestId }}
        <el-button link type="primary" size="small" @click="copyRequestId">复制</el-button>
      </p>
    </template>
    <template #extra>
      <el-button type="primary" @click="router.push('/plans')">返回首页</el-button>
    </template>
  </el-result>
</template>

<style scoped>
.request-id {
  margin-top: 8px;
  font-family: var(--app-font-mono);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
