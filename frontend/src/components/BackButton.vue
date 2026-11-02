<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { resolveBackTarget } from '@/utils/navigation'

const props = withDefaults(defineProps<{ fallback?: string; dark?: boolean; previous?: boolean }>(), { fallback: '', dark: false, previous: false })
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const target = computed(() => {
  const previous = window.history.state?.back
  if (props.previous && typeof previous === 'string' && previous.startsWith('/') && !previous.startsWith('//') && !previous.includes('\\')) {
    const resolved = router.resolve(previous)
    if (resolved.name && resolved.name !== route.name && !['Login', 'FeishuCallback', 'Forbidden'].includes(String(resolved.name))) {
      return { href: resolved.fullPath, label: '返回' }
    }
  }
  const fallbackTarget = resolveBackTarget(route, router, userStore.homePath, props.fallback || undefined)
  return props.previous ? { ...fallbackTarget, label: '返回' } : fallbackTarget
})

function goBack(): void {
  void router.push(target.value.href)
}
</script>

<template>
  <nav class="back-navigation" :class="{ dark }" aria-label="页面返回导航">
    <button type="button" class="back-button" data-back-button @click="goBack">
      <el-icon><ArrowLeft /></el-icon>
      <span>{{ target.label }}</span>
    </button>
  </nav>
</template>

<style scoped>
.back-navigation { display: flex; margin-bottom: 12px; }
.back-button { min-height: 36px; display: inline-flex; align-items: center; gap: 7px; padding: 7px 14px; border: 1px solid #B9C9E8; border-radius: 6px; background: #F2F6FD; color: #244C9E; font-size: 14px; font-weight: 600; cursor: pointer; transition: background .15s, border-color .15s; }
.back-button:hover { background: #E4EDFC; border-color: #2B5BC0; }
.back-button:focus-visible { outline: 3px solid rgba(43,91,192,.25); outline-offset: 2px; }
.back-navigation.dark .back-button { color: #EAF2FF; border-color: rgba(133,174,255,.45); background: rgba(28,62,120,.55); }
.back-navigation.dark .back-button:hover { background: rgba(46,88,160,.75); }
@media (max-width: 768px) { .back-button { min-height: 44px; padding-inline: 16px; } }
</style>
