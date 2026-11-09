<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { showToast } from 'vant'
import PageState from '@/components/PageState.vue'
import { getSettings, updateSettings } from '@/api/system'
import type { SettingItem, SettingPatchItem } from '@/types/system'

const GROUPS = [
  { title: '文件上传', match: (key: string) => key.startsWith('file.') },
  { title: '预警与提醒', match: (key: string) => key.startsWith('warning.') || key.startsWith('reminder.') },
  { title: '年度节点', match: (key: string) => key.startsWith('plan.') },
]
const loading = ref(false)
const saving = ref(false)
const items = ref<SettingItem[]>([])
const edits = ref<Record<string, string>>({})
const dirty = computed(() => Object.keys(edits.value).length > 0)

const groupedItems = computed(() => {
  const order = [...GROUPS.map((group) => group.title), '其他参数']
  const map = new Map<string, SettingItem[]>()
  for (const item of items.value) {
    const title = GROUPS.find((group) => group.match(item.key))?.title ?? '其他参数'
    if (!map.has(title)) map.set(title, [])
    map.get(title)!.push(item)
  }
  return order.filter((title) => map.has(title)).map((title) => ({ title, list: map.get(title)! }))
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
function hintOf(item: SettingItem): string {
  if (item.valueType === 'NUMBER') return '数值'
  if (item.valueType === 'BOOLEAN') return 'true / false'
  if (item.valueType === 'JSON') return 'JSON'
  return ''
}

async function load(): Promise<void> {
  loading.value = true
  try {
    items.value = (await getSettings()).items
    edits.value = {}
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  const patch: SettingPatchItem[] = Object.entries(edits.value).map(([key, value]) => {
    const item = items.value.find((entry) => entry.key === key)!
    return { key, value, version: item.version }
  })
  if (patch.length === 0) return
  saving.value = true
  try {
    items.value = (await updateSettings(patch)).items
    edits.value = {}
    showToast('参数已保存，将在下次操作时生效')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel-head"><h2>系统参数</h2></div>
    <PageState :loading="loading" :empty="items.length === 0" empty-text="暂无参数">
      <div v-for="group in groupedItems" :key="group.title">
        <div class="section-title">{{ group.title }}</div>
        <div class="setting-list">
          <div v-for="item in group.list" :key="item.key" class="setting-row">
            <div class="setting-label">{{ item.description || item.key }}</div>
            <van-field
              :model-value="valueOf(item)"
              :placeholder="hintOf(item)"
              @update:model-value="(value: string) => setValue(item, value)"
            />
            <div class="small muted">{{ item.key }} · {{ item.valueType }}</div>
          </div>
        </div>
      </div>
    </PageState>
    <div class="sticky-footer">
      <div class="sticky-footer__row">
        <van-button :disabled="!dirty" @click="load">重置</van-button>
        <van-button type="primary" :loading="saving" :disabled="!dirty" @click="save">保存</van-button>
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
.setting-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.setting-row {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
}
.setting-label {
  margin-bottom: 4px;
  font-size: 13px;
  font-weight: 600;
}
.setting-row :deep(.van-field) {
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fbfcfe;
  padding: 8px 10px;
}
</style>
