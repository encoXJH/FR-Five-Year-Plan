<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchFeishuIntegrationStatus,
  syncFeishuOrg,
  type FeishuIntegrationStatus,
  type FeishuSyncSummary,
} from '@/api/feishu'
import { newIdempotencyKey } from '@/utils/idempotency'

const loading = ref(false)
const syncing = ref(false)
/** null = 状态接口未就绪，降级展示可推算信息 */
const status = ref<FeishuIntegrationStatus | null>(null)
const statusReady = ref(false)
const syncResult = ref<FeishuSyncSummary | null>(null)

/** 事件回调地址：优先用后端返回，缺省按当前部署域名推算（API 约定 11） */
const eventCallbackUrl = computed(
  () => status.value?.eventCallbackUrl ?? `${window.location.origin}/api/v1/integrations/feishu/events`,
)
const cardActionCallbackUrl = computed(
  () => `${window.location.origin}/api/v1/integrations/feishu/card-actions`,
)
/** 免登 / OAuth 回调地址（前端路由，供配置到飞书后台「重定向 URL」） */
const authorizeCallbackUrl = computed(
  () => status.value?.authorizeCallbackUrl ?? `${window.location.origin}/login/feishu-callback`,
)

const lastSyncText = computed(() => {
  const result = status.value?.lastSyncResult
  if (!result) return null
  const parts: string[] = []
  if (typeof result.departmentCount === 'number') parts.push(`部门 ${result.departmentCount}`)
  if (typeof result.feishuUserCount === 'number') parts.push(`飞书人员 ${result.feishuUserCount}`)
  if (typeof result.matchedCount === 'number') parts.push(`匹配 ${result.matchedCount}`)
  if (typeof result.openIdBoundCount === 'number') parts.push(`绑定 ${result.openIdBoundCount}`)
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
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

async function handleSync(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将从飞书同步组织架构与人员，并按绑定关系更新平台用户。确认立即同步？',
      '组织同步',
      { confirmButtonText: '立即同步', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  syncing.value = true
  syncResult.value = null
  try {
    syncResult.value = await syncFeishuOrg(newIdempotencyKey())
    ElMessage.success('组织同步已执行')
    load().catch(() => undefined)
  } catch {
    // 拦截器已提示
  } finally {
    syncing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="feishu-panel">
    <el-alert
      v-if="!loading && !statusReady"
      type="warning"
      :closable="false"
      class="panel-alert"
      title="飞书配置状态接口未就绪，以下为按当前部署地址推算的信息"
    />

    <div class="settings-group">
      <div class="group-title">应用状态</div>
      <el-descriptions :column="1" border size="small" class="desc">
        <el-descriptions-item label="配置状态">
          <el-tag v-if="statusReady" :type="status?.configured ? 'success' : 'danger'" effect="light">
            {{ status?.configured ? '已配置' : '未配置' }}
          </el-tag>
          <span v-else class="muted">—</span>
          <span v-if="statusReady && !status?.configured" class="muted hint">
            未配置时登录页自动隐藏飞书入口，仅保留账号密码登录
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="AppID">
          <span class="mono">{{ status?.appIdMasked ?? '—' }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <div class="settings-group">
      <div class="group-title">回调地址（复制到飞书开放平台后台）</div>
      <el-descriptions :column="1" border size="small" class="desc">
        <el-descriptions-item label="免登 / OAuth 回调地址">
          <span class="mono url-text">{{ authorizeCallbackUrl }}</span>
          <el-button link type="primary" @click="copy(authorizeCallbackUrl, '回调地址')">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="事件订阅回调地址">
          <span class="mono url-text">{{ eventCallbackUrl }}</span>
          <el-button link type="primary" @click="copy(eventCallbackUrl, '事件回调地址')">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="卡片交互回调地址">
          <span class="mono url-text">{{ cardActionCallbackUrl }}</span>
          <el-button link type="primary" @click="copy(cardActionCallbackUrl, '卡片回调地址')">复制</el-button>
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <div class="settings-group">
      <div class="group-title">组织同步</div>
      <el-descriptions :column="1" border size="small" class="desc">
        <el-descriptions-item label="最近一次同步时间">
          <span class="mono">{{ status?.lastSyncAt ?? '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="最近一次同步结果">
          {{ lastSyncText ?? '—' }}
        </el-descriptions-item>
      </el-descriptions>
      <div class="sync-actions">
        <el-button type="primary" :loading="syncing" @click="handleSync">立即同步</el-button>
        <span class="muted">同步结果写入审计日志</span>
      </div>
      <el-alert
        v-if="syncResult"
        type="success"
        :closable="false"
        class="panel-alert"
      >
        <template #title>
          同步完成：
          <template v-if="typeof syncResult.departmentCount === 'number'">部门 {{ syncResult.departmentCount }} 个，</template>
          <template v-if="typeof syncResult.feishuUserCount === 'number'">飞书人员 {{ syncResult.feishuUserCount }} 人，</template>
          <template v-if="typeof syncResult.openIdBoundCount === 'number'">绑定平台用户 {{ syncResult.openIdBoundCount }} 人，</template>
          <template v-if="typeof syncResult.unmatchedCount === 'number'">未匹配 {{ syncResult.unmatchedCount }} 人</template>
        </template>
      </el-alert>
    </div>
  </div>
</template>

<style scoped>
.feishu-panel {
  max-width: 860px;
}

.panel-alert {
  margin-bottom: var(--space-4);
}

.settings-group {
  margin-bottom: var(--space-5);
}

.group-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: var(--space-3);
  padding-left: 8px;
  border-left: 3px solid var(--el-color-primary);
}

.desc {
  max-width: 860px;
}

.mono {
  font-family: var(--app-font-mono);
}

.url-text {
  margin-right: var(--space-2);
  word-break: break-all;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.hint {
  margin-left: var(--space-2);
}

.sync-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-3);
}
</style>
