<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { showConfirmDialog, showToast } from 'vant'
import PageState from '@/components/PageState.vue'
import { fetchEnums } from '@/api/dict'
import { getDictItems, saveDictItems } from '@/api/system'
import { useUserStore } from '@/stores/user'
import type { EnumDict } from '@/types/enums'
import type { DictItem } from '@/types/system'

const userStore = useUserStore()
const CUSTOM_DICTS = [{ type: 'UNIT_LABEL', name: '计量单位' }]
const activeDictType = ref(CUSTOM_DICTS[0].type)
const dictRows = ref<DictItem[]>([])
const dictDirty = ref(false)
const loading = ref(false)
const saving = ref(false)
const enums = ref<EnumDict>({})
const activeEnumType = ref('')

const enumTypeNames = computed(() => Object.keys(enums.value).sort())
const enumItems = computed(() => {
  const group = enums.value[activeEnumType.value] ?? {}
  return Object.keys(group).map((value) => ({ value, label: group[value] }))
})

async function loadDict(): Promise<void> {
  loading.value = true
  try {
    dictRows.value = (await getDictItems(activeDictType.value)).map((item) => ({ ...item }))
    dictDirty.value = false
  } finally {
    loading.value = false
  }
}

function addRow(): void {
  const maxSort = dictRows.value.reduce((max, item) => Math.max(max, item.sort ?? 0), 0)
  dictRows.value.push({ code: '', label: '', sort: maxSort + 1, isEnabled: true })
  dictDirty.value = true
}

async function removeRow(index: number): Promise<void> {
  const row = dictRows.value[index]
  if (row.id) {
    try {
      await showConfirmDialog({
        title: '删除字典项',
        message: `删除「${row.label}」后，历史数据中的该编码将不再显示中文名。确认删除？`,
        confirmButtonText: '确认删除',
      })
    } catch {
      return
    }
  }
  dictRows.value.splice(index, 1)
  dictDirty.value = true
}

async function saveDict(): Promise<void> {
  const invalid = dictRows.value.find((item) => !item.code.trim() || !item.label.trim())
  if (invalid) {
    showToast('字典项的编码和显示名都不能为空')
    return
  }
  saving.value = true
  try {
    const payload = dictRows.value.map((item, index) => ({
      id: item.id,
      code: item.code.trim().toUpperCase(),
      label: item.label.trim(),
      sort: item.sort ?? index + 1,
      isEnabled: item.isEnabled ?? true,
    }))
    dictRows.value = (await saveDictItems(activeDictType.value, payload)).map((item) => ({ ...item }))
    dictDirty.value = false
    showToast('字典已保存')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  void loadDict()
  try {
    enums.value = await fetchEnums()
    userStore.enums = enums.value
    activeEnumType.value = enumTypeNames.value[0] ?? ''
  } catch {
    // 忽略
  }
})
</script>

<template>
  <div>
    <div class="panel-head"><h2>字典管理</h2></div>

    <div class="section-title">自定义字典（可维护）</div>
    <div class="filter-scroll">
      <button
        v-for="dict in CUSTOM_DICTS"
        :key="dict.type"
        type="button"
        class="filter-chip"
        :class="{ 'is-active': activeDictType === dict.type }"
        @click="activeDictType = dict.type; loadDict()"
      >{{ dict.name }}</button>
    </div>

    <div class="row actions">
      <van-button size="small" type="primary" plain @click="addRow">新增字典项</van-button>
      <van-button size="small" :disabled="!dictDirty" @click="loadDict">重置</van-button>
      <van-button size="small" type="primary" :loading="saving" :disabled="!dictDirty" @click="saveDict">保存</van-button>
    </div>

    <PageState :loading="loading" :empty="dictRows.length === 0" empty-text="暂无字典项">
      <div class="dict-list">
        <div v-for="(row, index) in dictRows" :key="row.id ?? index" class="dict-row">
          <div class="row">
            <van-field v-model="row.code" :disabled="!!row.id" placeholder="编码" class="grow" @update:model-value="dictDirty = true" />
            <van-field v-model="row.label" placeholder="显示名" class="grow" @update:model-value="dictDirty = true" />
          </div>
          <div class="row">
            <van-field
              :model-value="row.sort ?? ''"
              type="number"
              placeholder="排序"
              class="grow"
              @update:model-value="(value: string) => { row.sort = value === '' ? null : Number(value); dictDirty = true }"
            />
            <van-button size="small" type="danger" plain @click="removeRow(index)">删除</van-button>
          </div>
        </div>
      </div>
    </PageState>
    <p class="small muted">保存为整体替换：列表中未出现的既有编码将被删除；编码保存后不可修改。</p>

    <div class="section-title">枚举字典（系统内置，只读）</div>
    <div class="filter-scroll">
      <button
        v-for="name in enumTypeNames"
        :key="name"
        type="button"
        class="filter-chip"
        :class="{ 'is-active': activeEnumType === name }"
        @click="activeEnumType = name"
      >{{ name }}</button>
    </div>
    <div class="enum-list">
      <div v-for="item in enumItems" :key="item.value" class="enum-row">
        <span class="code">{{ item.value }}</span>
        <span>{{ item.label }}</span>
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
.actions {
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.dict-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.dict-row {
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
}
.dict-row :deep(.van-field) {
  padding: 6px 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  margin: 2px;
}
.enum-list {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  overflow: hidden;
}
.enum-row {
  display: flex;
  gap: 10px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--line);
  font-size: 12px;
}
.enum-row:last-child {
  border-bottom: none;
}
.code {
  color: var(--brand);
  font-family: Consolas, Menlo, monospace;
}
</style>
