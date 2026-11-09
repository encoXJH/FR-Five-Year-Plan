<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getLastRequestId } from '@/utils/request'

const router = useRouter()
const userStore = useUserStore()
const requestId = computed(() => getLastRequestId())

function goHome(): void {
  router.replace(userStore.isLoggedIn ? userStore.homePath : '/login')
}
</script>

<template>
  <div class="page forbidden-page">
    <div class="forbidden-icon">
      <van-icon name="shield-o" size="44" />
    </div>
    <h1>暂无访问权限</h1>
    <p class="muted">当前账号没有该页面或数据的访问权限，如有疑问请联系平台管理员。</p>
    <p v-if="requestId" class="small muted mono">请求编号：{{ requestId }}</p>
    <van-button block type="primary" class="btn-height" @click="goHome">返回首页</van-button>
  </div>
</template>

<style scoped>
.forbidden-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 70vh;
  text-align: center;
}
.forbidden-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 88px;
  height: 88px;
  border-radius: 50%;
  background: #eef1f7;
  color: #8a97a8;
}
.forbidden-page h1 {
  margin: 4px 0 0;
  font-size: 20px;
}
.forbidden-page p {
  margin: 0;
  max-width: 300px;
  line-height: 20px;
}
.mono {
  font-family: Consolas, Menlo, monospace;
  word-break: break-all;
}
</style>
