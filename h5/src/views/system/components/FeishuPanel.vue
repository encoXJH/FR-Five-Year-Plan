<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { showConfirmDialog, showToast } from 'vant'
import {
  fetchFeishuIntegrationStatus,
  syncFeishuOrg,
  type FeishuIntegrationStatus,
  type FeishuSyncSummary,
} from '@/api/feishu'
import { newIdempotencyKey } from '@/utils/idempotency'

const loading = ref(false)
const syncing = ref(false)
const status = ref<FeishuIntegrationStatus | null>(null)
const statusReady = ref(false)
const syncResult = ref<FeishuSyncSummary | null>(null)

const eventCallbackUrl = computed(
  () => status.value?.eventCallbackUrl ?? `${window.location.origin}/api/v1/integrations/feishu/events`,
)
const cardActionCallbackUrl = computed(
  () => `${window.location.origin}/api/v1/integrations/feishu/card-actions`,
)
const authorizeCallbackUrl = computed(
  () => status.value?.authorizeCallbackUrl ?? `${window.location.origin}/api/callback`,
)
const lastSyncText = computed(() => {
  const result = status.value?.lastSyncResult
  if (!result) return null
  const parts: string[] = []
  if (typeof result.departmentCount === 'number') parts.push(`部门 ${result.departmentCount}`)
  if (typeof result.feishuUserCount === 'number') parts.push(`飞书人员 ${result.feishuUserCount}`)
  if (typeof result.matchedCount === 'number') parts.push(`匹配 ${result.matchedCount}`)
  if (typeof result.unmatchedCount === 'number') parts.push(`未匹配 ${result.unmatchedCount}`)
  return parts.length > 0 ? parts.join(' / ') : null
})

async function load(): Promise<void> {
  loading.value = true
  try {
    status.value = await fetchFeishuIntegrationStatus()
    statusReady.value = status.value !== null
  } finally {
    loading.value = false
  }
}

async function copy(text: string, label: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      showToast(`${label}已复制`)
      return
    } catch {
      // 降级
    }
  }
  const input = document.createElement('textarea')
  input.value = text
  document.body.appendChild(input)
  input.select()
  document.execCommand('copy')
  input.remove()
  showToast(`${label}已复制`)
}

async function handleSync(): Promise<void> {
  try {
    await showConfirmDialog({
      title: '组织同步',
      message: '将从飞书同步组织架构与人员，并按绑定关系更新平台用户。确认立即同步？',
      confirmButtonText: '立即同步',
    })
  } catch {
    return
  }
  syncing.value = true
  syncResult.value = null
  try {
    syncResult.value = await syncFeishuOrg(newIdempotencyKey())
    showToast('组织同步已执行')
    await load()
  } finally {
    syncing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel-head"><h2>飞书集成</h2></div>
    <div v-if="!loading && !statusReady" class="warn-box">
      飞书配置状态接口未就绪，以下为按当前部署地址推算的信息
    </div>

    <div class="section-title">应用状态</div>
    <div class="card card--tight">
      <div class="kv-list">
        <div class="kv"><span class="kv__label">配置状态</span><span class="kv__value">{{ statusReady ? (status?.configured ? '已配置' : '未配置') : '—' }}</span></div>
        <div class="kv"><span class="kv__label">AppID</span><span class="kv__value">{{ status?.appIdMasked ?? '—' }}</span></div>
      </div>
      <p v-if="statusReady && !status?.configured" class="small muted">
        未配置时登录页自动隐藏飞书入口，仅保留账号密码登录
      </p>
    </div>

    <div class="section-title">回调地址（复制到飞书开放平台后台）</div>
    <div class="card card--tight">
      <div class="url-block">
        <div class="small muted">免登 / OAuth 回调地址</div>
        <div class="url-text">{{ authorizeCallbackUrl }}</div>
        <van-button size="mini" plain type="primary" @click="copy(authorizeCallbackUrl, '回调地址')">复制</van-button>
      </div>
      <div class="url-block">
        <div class="small muted">事件订阅回调地址</div>
        <div class="url-text">{{ eventCallbackUrl }}</div>
        <van-button size="mini" plain type="primary" @click="copy(eventCallbackUrl, '事件回调地址')">复制</van-button>
      </div>
      <div class="url-block">
        <div class="small muted">卡片交互回调地址</div>
        <div class="url-text">{{ cardActionCallbackUrl }}</div>
        <van-button size="mini" plain type="primary" @click="copy(cardActionCallbackUrl, '卡片回调地址')">复制</van-button>
      </div>
    </div>

    <div class="section-title">组织同步</div>
    <div class="card card--tight">
      <div class="kv-list">
        <div class="kv"><span class="kv__label">最近同步</span><span class="kv__value">{{ status?.lastSyncAt ?? '—' }}</span></div>
        <div class="kv"><span class="kv__label">同步结果</span><span class="kv__value">{{ lastSyncText ?? '—' }}</span></div>
      </div>
      <van-button block type="primary" style="margin-top: 12px" :loading="syncing" @click="handleSync">立即同步</van-button>
      <div v-if="syncResult" class="ok-box" style="margin-top: 10px">
        同步完成：部门 {{ syncResult.departmentCount ?? '—' }} 个，飞书人员 {{ syncResult.feishuUserCount ?? '—' }} 人，未匹配 {{ syncResult.unmatchedCount ?? '—' }} 人
      </div>
    </div>
  </div>
</template>

<style scoped>
.panel-head {
  margin-bottom: 10px;
}
.panel-head h2 {
  margin: 0;
  font-size: 18px;
}
.warn-box,
.ok-box {
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 18px;
}
.warn-box {
  background: #fdf3e5;
  color: #8f5d16;
}
.ok-box {
  background: #e8f7f0;
  color: #147a51;
}
.url-block {
  padding: 8px 0;
  border-bottom: 1px solid var(--line);
}
.url-block:last-child {
  border-bottom: none;
}
.url-text {
  margin: 4px 0;
  color: var(--text);
  font-family: Consolas, Menlo, monospace;
  font-size: 11px;
  word-break: break-all;
}
</style>
