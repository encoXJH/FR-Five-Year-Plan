<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { showToast } from 'vant'
import PageState from '@/components/PageState.vue'
import {
  createMajorGroup,
  listMajorGroups,
  listOrgUnitsAdmin,
  listUsers,
  updateMajorGroup,
} from '@/api/system'
import type { MajorGroup, OrgUnitAdmin, SystemUser } from '@/types/system'

const loading = ref(false)
const rows = ref<MajorGroup[]>([])
const units = ref<OrgUnitAdmin[]>([])
const users = ref<SystemUser[]>([])
const formVisible = ref(false)
const saving = ref(false)
const editing = ref<MajorGroup | null>(null)
const unitPickerVisible = ref(false)
const ownerPickerVisible = ref(false)
const form = reactive({ name: '', unitId: '', ownerId: '', isEnabled: true })
const unitLabel = ref('')
const ownerLabel = ref('')

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await listMajorGroups()
  } finally {
    loading.value = false
  }
}

async function loadOptions(): Promise<void> {
  try {
    units.value = await listOrgUnitsAdmin()
    const result = await listUsers({ page: 1, pageSize: 200 })
    users.value = result.items
  } catch {
    // 忽略选项失败
  }
}

function openCreate(): void {
  editing.value = null
  form.name = ''
  form.unitId = ''
  form.ownerId = ''
  form.isEnabled = true
  unitLabel.value = ''
  ownerLabel.value = ''
  formVisible.value = true
}

function openEdit(row: MajorGroup): void {
  editing.value = row
  form.name = row.name
  form.unitId = row.unitId ?? ''
  form.ownerId = row.ownerId ?? ''
  form.isEnabled = row.isEnabled
  unitLabel.value = row.unitName ?? ''
  ownerLabel.value = row.ownerName ?? ''
  formVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.name.trim()) {
    showToast('请填写专业群名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      unitId: form.unitId || null,
      ownerId: form.ownerId || null,
      isEnabled: form.isEnabled,
    }
    if (editing.value) {
      await updateMajorGroup(editing.value.id, { ...payload, version: editing.value.version })
      showToast('专业群已更新')
    } else {
      await createMajorGroup(payload)
      showToast('专业群已新增')
    }
    formVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void load()
  void loadOptions()
})
</script>

<template>
  <div>
    <div class="panel-head">
      <h2>专业群</h2>
      <van-button size="small" type="primary" @click="openCreate">新增专业群</van-button>
    </div>
    <PageState :loading="loading" :empty="rows.length === 0" empty-text="暂无专业群">
      <div class="group-list">
        <article v-for="row in rows" :key="row.id" class="group-card">
          <div class="row--between">
            <strong>{{ row.name }}</strong>
            <span class="chip" :class="row.isEnabled ? 'chip--success' : 'chip--gray'">{{ row.isEnabled ? '启用' : '停用' }}</span>
          </div>
          <div class="small muted">依托单元：{{ row.unitName ?? '—' }} · 负责人：{{ row.ownerName ?? '—' }}</div>
          <div class="actions">
            <van-button size="mini" plain type="primary" @click="openEdit(row)">编辑</van-button>
          </div>
        </article>
      </div>
    </PageState>

    <van-popup v-model:show="formVisible" position="bottom" round safe-area-inset-bottom>
      <div class="sheet">
        <div class="sheet-head">
          <strong>{{ editing ? '编辑专业群' : '新增专业群' }}</strong>
          <button type="button" class="close-btn" @click="formVisible = false">
            <van-icon name="cross" size="18" />
          </button>
        </div>
        <div class="field">
          <label>名称<span class="required">*</span></label>
          <van-field v-model="form.name" placeholder="专业群名称" />
        </div>
        <div class="field">
          <label>依托执行单元</label>
          <van-field readonly is-link :model-value="unitLabel" placeholder="选择执行单元" @click="unitPickerVisible = true" />
        </div>
        <div class="field">
          <label>负责人</label>
          <van-field readonly is-link :model-value="ownerLabel" placeholder="选择负责人" @click="ownerPickerVisible = true" />
        </div>
        <div class="switch-row">
          <div class="field-label">启用</div>
          <van-switch v-model="form.isEnabled" size="22px" />
        </div>
        <van-button block type="primary" :loading="saving" @click="submit">保存</van-button>
      </div>
    </van-popup>

    <van-popup v-model:show="unitPickerVisible" position="bottom" round :style="{ height: '60vh' }">
      <div class="sheet picker">
        <div class="sheet-head"><strong>选择执行单元</strong></div>
        <div class="picker-list">
          <button type="button" class="picker-item" @click="form.unitId = ''; unitLabel = ''; unitPickerVisible = false">不指定</button>
          <button
            v-for="unit in units"
            :key="unit.id"
            type="button"
            class="picker-item"
            @click="form.unitId = unit.id; unitLabel = `${unit.code} ${unit.name}`; unitPickerVisible = false"
          >{{ unit.code }} {{ unit.name }}</button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="ownerPickerVisible" position="bottom" round :style="{ height: '60vh' }">
      <div class="sheet picker">
        <div class="sheet-head"><strong>选择负责人</strong></div>
        <div class="picker-list">
          <button type="button" class="picker-item" @click="form.ownerId = ''; ownerLabel = ''; ownerPickerVisible = false">不指定</button>
          <button
            v-for="user in users"
            :key="user.id"
            type="button"
            class="picker-item"
            @click="form.ownerId = user.id; ownerLabel = user.name; ownerPickerVisible = false"
          >{{ user.name }}（{{ user.username }}）</button>
        </div>
      </div>
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
.group-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.group-card {
  padding: 10px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: var(--shadow);
}
.actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid var(--line);
}
.sheet {
  padding: 16px 16px calc(16px + var(--safe-bottom));
}
.sheet.picker {
  display: flex;
  flex-direction: column;
  height: 100%;
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
  margin-bottom: 12px;
}
.picker-list {
  flex: 1;
  overflow-y: auto;
}
.picker-item {
  width: 100%;
  min-height: 46px;
  padding: 8px 4px;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--text);
  text-align: left;
  font-size: 13px;
}
</style>
