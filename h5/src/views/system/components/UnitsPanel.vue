<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { showToast } from 'vant'
import PageState from '@/components/PageState.vue'
import { createOrgUnit, listOrgUnitsAdmin, updateOrgUnit } from '@/api/system'
import type { OrgUnitAdmin } from '@/types/system'
import { enumLabel, enumOptions } from '@/utils/enumLabels'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const loading = ref(false)
const rows = ref<OrgUnitAdmin[]>([])
const formVisible = ref(false)
const saving = ref(false)
const editing = ref<OrgUnitAdmin | null>(null)
const typePickerVisible = ref(false)
const form = reactive({
  name: '',
  type: 'COLLEGE',
  sort: 0,
  isEnabled: true,
  isBAssignable: true,
})
const typeOptions = computed(() => enumOptions('OrgUnitType', userStore.enums))

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await listOrgUnitsAdmin()
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editing.value = null
  form.name = ''
  form.type = 'COLLEGE'
  form.sort = Math.max(0, ...rows.value.map((item) => item.sort ?? 0)) + 1
  form.isEnabled = true
  form.isBAssignable = true
  formVisible.value = true
}

function openEdit(row: OrgUnitAdmin): void {
  editing.value = row
  form.name = row.name
  form.type = String(row.type ?? 'COLLEGE')
  form.sort = row.sort ?? 0
  form.isEnabled = row.isEnabled
  form.isBAssignable = row.isBAssignable
  formVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.name.trim()) {
    showToast('请填写单元名称')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateOrgUnit(editing.value.id, {
        name: form.name.trim(),
        type: form.type,
        sort: form.sort,
        isEnabled: form.isEnabled,
        isBAssignable: form.isBAssignable,
        version: editing.value.version,
      })
      showToast('执行单元已更新')
    } else {
      await createOrgUnit({
        name: form.name.trim(),
        type: form.type,
        sort: form.sort,
        isEnabled: form.isEnabled,
        isBAssignable: form.isBAssignable,
      })
      showToast('执行单元已新增')
    }
    formVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function typeLabel(type: string | null): string {
  return type ? enumLabel('OrgUnitType', type, userStore.enums) : '—'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel-head">
      <h2>执行单元</h2>
      <van-button size="small" type="primary" @click="openCreate">新增单元</van-button>
    </div>
    <PageState :loading="loading" :empty="rows.length === 0" empty-text="暂无执行单元">
      <div class="unit-list">
        <article v-for="row in rows" :key="row.id" class="unit-card">
          <div class="row--between">
            <div class="row">
              <strong class="code">{{ row.code }}</strong>
              <span>{{ row.name }}</span>
            </div>
            <span class="chip" :class="row.isEnabled ? 'chip--success' : 'chip--gray'">
              {{ row.isEnabled ? '启用' : '停用' }}
            </span>
          </div>
          <div class="small muted">
            {{ typeLabel(row.type) }} · 排序 {{ row.sort ?? '—' }} · {{ row.isBAssignable ? '可承接新任务' : '不可承接新任务' }}
          </div>
          <div class="row actions">
            <van-button size="mini" plain type="primary" @click="openEdit(row)">编辑</van-button>
          </div>
        </article>
      </div>
    </PageState>

    <van-popup v-model:show="formVisible" position="bottom" round safe-area-inset-bottom>
      <div class="sheet">
        <div class="sheet-head">
          <strong>{{ editing ? '编辑执行单元' : '新增执行单元' }}</strong>
          <button type="button" class="close-btn" @click="formVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="field">
          <label>名称<span class="required">*</span></label>
          <van-field v-model="form.name" placeholder="单元名称" />
        </div>
        <div class="field">
          <label>类型</label>
          <van-field readonly is-link :model-value="typeLabel(form.type)" @click="typePickerVisible = true" />
        </div>
        <div class="field">
          <label>排序</label>
          <van-field v-model.number="form.sort" type="number" inputmode="numeric" />
        </div>
        <div class="switch-row">
          <div>
            <div class="field-label">启用</div>
            <p class="small muted">停用后不能用于新增任务或转移接收</p>
          </div>
          <van-switch v-model="form.isEnabled" size="22px" />
        </div>
        <div class="switch-row">
          <div>
            <div class="field-label">允许承接新任务</div>
            <p class="small muted">关闭后该单元不出现在分解与接收选项中</p>
          </div>
          <van-switch v-model="form.isBAssignable" size="22px" />
        </div>
        <van-button block type="primary" :loading="saving" @click="submit">保存</van-button>
      </div>
    </van-popup>

    <van-popup v-model:show="typePickerVisible" position="bottom" round>
      <van-picker
        :columns="typeOptions.map((item) => ({ text: item.label, value: item.value }))"
        @confirm="(value: { selectedOptions: Array<{ value: string }> }) => {
          form.type = value.selectedOptions[0]?.value ?? 'COLLEGE'
          typePickerVisible = false
        }"
        @cancel="typePickerVisible = false"
      />
    </van-popup>
  </div>
</template>

<style scoped>
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.panel-head h2 {
  margin: 0;
  font-size: 18px;
}
.unit-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.unit-card {
  padding: 10px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.code {
  color: var(--brand);
  font-family: Consolas, Menlo, monospace;
}
.actions {
  justify-content: flex-end;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid var(--line);
}
.sheet {
  padding: 16px 16px calc(16px + var(--safe-bottom));
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  font-size: 16px;
}
.close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background: #f4f6fa;
  color: var(--text-secondary);
}
.field {
  margin-bottom: 10px;
}
.field label,
.field-label {
  display: block;
  margin-bottom: 4px;
  color: var(--text-secondary);
  font-size: 13px;
}
.required {
  color: var(--danger);
}
.field :deep(.van-field) {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fbfcfe;
  padding: 10px 12px;
}
.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.switch-row .small {
  margin: 0;
}
</style>
