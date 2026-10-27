<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getSettings, updateSettings } from '@/api/system'
import type { SettingItem, SettingPatchItem } from '@/types/system'

/** 参数分组（按 key 前缀；标签优先用后端 description） */
const GROUPS: { title: string; match: (key: string) => boolean }[] = [
  { title: '文件上传', match: (k) => k.startsWith('file.') },
  { title: '预警与提醒', match: (k) => k.startsWith('warning.') || k.startsWith('reminder.') },
  { title: '年度节点', match: (k) => k.startsWith('plan.') },
]

const loading = ref(false)
const saving = ref(false)
const items = ref<SettingItem[]>([])
/** 编辑中的新值：key → value（只收集被改动的项） */
const edits = ref<Record<string, string>>({})

const dirty = computed(() => Object.keys(edits.value).length > 0)

function groupOf(key: string): string {
  return GROUPS.find((g) => g.match(key))?.title ?? '其他参数'
}

const groupedItems = computed(() => {
  const order = [...GROUPS.map((g) => g.title), '其他参数']
  const map = new Map<string, SettingItem[]>()
  for (const item of items.value) {
    const title = groupOf(item.key)
    if (!map.has(title)) map.set(title, [])
    map.get(title)!.push(item)
  }
  return order.filter((t) => map.has(t)).map((t) => ({ title: t, list: map.get(t)! }))
})

function valueOf(item: SettingItem): string {
  return edits.value[item.key] ?? item.value
}

function setValue(item: SettingItem, value: string): void {
  if (value === item.value) {
    const next = { ...edits.value }
    delete next[item.key]
    edits.value = next
  } else {
    edits.value = { ...edits.value, [item.key]: value }
  }
}

function labelOf(item: SettingItem): string {
  return item.description || item.key
}

function hintOf(item: SettingItem): string {
  if (item.valueType === 'NUMBER') return '数值'
  if (item.valueType === 'BOOLEAN') return '布尔（true / false）'
  if (item.valueType === 'JSON') return 'JSON（如 ["pdf","docx"]）'
  return ''
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await getSettings()
    items.value = result.items
    edits.value = {}
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  const patch: SettingPatchItem[] = Object.entries(edits.value).map(([key, value]) => {
    const item = items.value.find((i) => i.key === key)!
    return { key, value, version: item.version }
  })
  if (patch.length === 0) return
  saving.value = true
  try {
    const result = await updateSettings(patch)
    items.value = result.items
    edits.value = {}
    ElMessage.success('参数已保存，将在下次操作时生效')
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="settings-panel">
    <template v-for="group in groupedItems" :key="group.title">
      <div class="settings-group">
        <div class="group-title">{{ group.title }}</div>
        <el-form label-width="280px" label-position="left">
          <el-form-item v-for="item in group.list" :key="item.key" :label="labelOf(item)">
            <el-input
              :model-value="valueOf(item)"
              :placeholder="hintOf(item)"
              style="width: 360px"
              @update:model-value="(v: string) => setValue(item, v)"
            />
            <span class="setting-key">{{ item.key }}</span>
          </el-form-item>
        </el-form>
      </div>
    </template>

    <el-empty v-if="!loading && items.length === 0" description="暂无参数" :image-size="60" />

    <div class="settings-footer">
      <el-button :disabled="!dirty" @click="load">重置</el-button>
      <el-button type="primary" :loading="saving" :disabled="!dirty" @click="save">保存</el-button>
    </div>
  </div>
</template>

<style scoped>
.settings-panel {
  max-width: 860px;
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

.setting-key {
  margin-left: var(--space-3);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.settings-footer {
  display: flex;
  gap: var(--space-3);
  padding-top: var(--space-4);
  border-top: 1px solid var(--el-border-color-light);
}
</style>
