<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchEnums } from '@/api/dict'
import { getDictItems, saveDictItems } from '@/api/system'
import { useUserStore } from '@/stores/user'
import type { EnumDict } from '@/types/enums'
import type { DictItem } from '@/types/system'

const userStore = useUserStore()

/** 可维护的自定义字典（dict_item 表；PUT 整体替换） */
const CUSTOM_DICTS: { type: string; name: string }[] = [{ type: 'UNIT_LABEL', name: '计量单位' }]

// ---------- 自定义字典（可编辑） ----------
const customLoading = ref(false)
const customSaving = ref(false)
const activeDictType = ref(CUSTOM_DICTS[0].type)
const dictRows = ref<DictItem[]>([])
const dictDirty = ref(false)

function activeDictName(): string {
  return CUSTOM_DICTS.find((d) => d.type === activeDictType.value)?.name ?? activeDictType.value
}

async function loadCustomDict(): Promise<void> {
  customLoading.value = true
  try {
    dictRows.value = (await getDictItems(activeDictType.value)).map((d) => ({ ...d }))
    dictDirty.value = false
  } finally {
    customLoading.value = false
  }
}

function switchDict(type: string): void {
  if (type === activeDictType.value) return
  activeDictType.value = type
  loadCustomDict()
}

function addDictRow(): void {
  const maxSort = dictRows.value.reduce((m, d) => Math.max(m, d.sort ?? 0), 0)
  dictRows.value.push({ code: '', label: '', sort: maxSort + 1, isEnabled: true })
  dictDirty.value = true
}

async function removeDictRow(index: number): Promise<void> {
  const row = dictRows.value[index]
  if (row.id) {
    try {
      await ElMessageBox.confirm(
        `删除「${row.label}」后，历史数据中的该编码将不再显示中文名。确认删除？`,
        '删除字典项',
        { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }
  dictRows.value.splice(index, 1)
  dictDirty.value = true
}

function markDirty(): void {
  dictDirty.value = true
}

async function saveCustomDict(): Promise<void> {
  const invalid = dictRows.value.find((d) => !d.code.trim() || !d.label.trim())
  if (invalid) {
    ElMessage.warning('字典项的编码和显示名都不能为空')
    return
  }
  customSaving.value = true
  try {
    const payload: DictItem[] = dictRows.value.map((d, i) => ({
      id: d.id,
      code: d.code.trim().toUpperCase(),
      label: d.label.trim(),
      sort: d.sort ?? i + 1,
      isEnabled: d.isEnabled ?? true,
    }))
    dictRows.value = (await saveDictItems(activeDictType.value, payload)).map((d) => ({ ...d }))
    dictDirty.value = false
    ElMessage.success(`字典「${activeDictName()}」已保存`)
  } catch {
    // 拦截器已提示
  } finally {
    customSaving.value = false
  }
}

// ---------- 枚举字典（只读，GET /dicts/enums） ----------
const enumLoading = ref(false)
const enums = ref<EnumDict>({})
const activeEnumType = ref('')

const enumTypeNames = computed(() => Object.keys(enums.value).sort())

const enumItems = computed(() => {
  const group = enums.value[activeEnumType.value] ?? {}
  return Object.keys(group).map((value) => ({ value, label: group[value] }))
})

async function loadEnums(): Promise<void> {
  enumLoading.value = true
  try {
    enums.value = await fetchEnums()
    userStore.enums = enums.value
    if (!activeEnumType.value || !enums.value[activeEnumType.value]) {
      activeEnumType.value = enumTypeNames.value[0] ?? ''
    }
  } finally {
    enumLoading.value = false
  }
}

onMounted(() => {
  loadCustomDict()
  loadEnums()
})
</script>

<template>
  <div class="dict-panel-wrap">
    <div class="settings-group">
      <div class="group-title">自定义字典（可维护）</div>
      <div class="dict-panel">
        <div class="dict-types">
          <div
            v-for="d in CUSTOM_DICTS"
            :key="d.type"
            class="dict-type-item"
            :class="{ 'is-active': d.type === activeDictType }"
            @click="switchDict(d.type)"
          >
            {{ d.name }}
            <span class="dict-count">{{ d.type }}</span>
          </div>
        </div>
        <div class="dict-items" v-loading="customLoading">
          <div class="panel-toolbar">
            <el-button size="small" type="primary" @click="addDictRow">新增字典项</el-button>
            <div class="toolbar-spacer" />
            <el-button size="small" :disabled="!dictDirty" @click="loadCustomDict">重置</el-button>
            <el-button
              size="small"
              type="primary"
              :loading="customSaving"
              :disabled="!dictDirty"
              @click="saveCustomDict"
            >
              保存
            </el-button>
          </div>
          <el-table :data="dictRows" border size="default">
            <el-table-column label="编码" width="180">
              <template #default="{ row }">
                <el-input
                  v-model="row.code"
                  :disabled="!!row.id"
                  placeholder="如 XIANG"
                  @update:model-value="markDirty"
                />
              </template>
            </el-table-column>
            <el-table-column label="显示名" min-width="160">
              <template #default="{ row }">
                <el-input v-model="row.label" placeholder="如 项" @update:model-value="markDirty" />
              </template>
            </el-table-column>
            <el-table-column label="排序" width="110" align="center">
              <template #default="{ row }">
                <el-input-number
                  v-model="row.sort"
                  :min="0"
                  :max="9999"
                  controls-position="right"
                  style="width: 90px"
                  @update:model-value="markDirty"
                />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="center">
              <template #default="{ $index }">
                <el-button link type="danger" size="small" @click="removeDictRow($index)">
                  删除
                </el-button>
              </template>
            </el-table-column>
            <template #empty>暂无字典项，点击「新增字典项」添加</template>
          </el-table>
          <p class="dict-tip">保存为整体替换：列表中未出现的既有编码将被删除；编码保存后不可修改。</p>
        </div>
      </div>
    </div>

    <div class="settings-group">
      <div class="group-title">枚举字典（系统内置，只读）</div>
      <div class="dict-panel" v-loading="enumLoading">
        <div class="dict-types">
          <div
            v-for="name in enumTypeNames"
            :key="name"
            class="dict-type-item"
            :class="{ 'is-active': name === activeEnumType }"
            @click="activeEnumType = name"
          >
            {{ name }}
            <span class="dict-count">{{ Object.keys(enums[name] ?? {}).length }}</span>
          </div>
          <el-empty
            v-if="!enumLoading && enumTypeNames.length === 0"
            description="暂无字典"
            :image-size="60"
          />
        </div>
        <div class="dict-items">
          <div class="panel-toolbar">
            <span class="dict-current">{{ activeEnumType || '—' }}</span>
            <div class="toolbar-spacer" />
            <el-button size="small" @click="loadEnums">刷新</el-button>
          </div>
          <el-table :data="enumItems" border size="default">
            <el-table-column prop="value" label="枚举值" width="220" />
            <el-table-column prop="label" label="中文名" min-width="180" />
          </el-table>
          <p class="dict-tip">枚举字典由系统内置维护，如需调整请联系平台管理员（后端配置）。</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
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

.dict-panel {
  display: flex;
  gap: var(--space-4);
  min-height: 260px;
}

.dict-types {
  width: 220px;
  flex-shrink: 0;
  border-right: 1px solid var(--el-border-color-light);
  padding-right: var(--space-3);
  max-height: 420px;
  overflow-y: auto;
}

.dict-type-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 14px;
  cursor: pointer;
  color: var(--el-text-color-regular);
}

.dict-type-item:hover {
  background-color: var(--el-fill-color-light);
}

.dict-type-item.is-active {
  background-color: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}

.dict-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dict-items {
  flex: 1;
  min-width: 0;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  margin-bottom: var(--space-3);
}

.dict-current {
  font-weight: 600;
}

.toolbar-spacer {
  flex: 1;
}

.dict-tip {
  margin-top: var(--space-3);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
