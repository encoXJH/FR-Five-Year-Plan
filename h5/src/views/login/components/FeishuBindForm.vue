<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { showToast } from 'vant'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import type { ClientEnv } from '@/utils/feishu'

const props = defineProps<{
  bindingTicket: string
  bindingTicketExpiresAt: string
  clientType: ClientEnv
}>()

const emit = defineEmits<{
  success: []
  cancel: []
}>()

const userStore = useUserStore()
const submitting = ref(false)
const errorMessage = ref('')
const form = reactive({ name: '', employeeId: '' })

const expiresAtText = computed(() => {
  const value = new Date(props.bindingTicketExpiresAt)
  return Number.isNaN(value.getTime()) ? '' : value.toLocaleString('zh-CN', { hour12: false })
})

async function submit(): Promise<void> {
  if (!form.name.trim()) {
    showToast('请输入姓名')
    return
  }
  if (!form.employeeId.trim()) {
    showToast('请输入工号')
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await userStore.bindFeishu(
      {
        bindingTicket: props.bindingTicket,
        name: form.name.trim(),
        employeeId: form.employeeId.trim(),
      },
      props.clientType,
    )
    emit('success')
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === 20006 && error.message.includes('票据')) {
        errorMessage.value = '绑定已失效，请返回后重新使用飞书登录'
      } else if (error.code === 20006 || error.code === 20007) {
        errorMessage.value = '姓名或工号核验未通过，请核对后重试；如仍失败请联系管理员'
      } else {
        errorMessage.value = `${error.message || '校内身份核验失败，请稍后重试'}${error.requestId ? `（请求编号：${error.requestId}）` : ''}`
      }
    } else {
      errorMessage.value = '校内身份核验失败，请稍后重试'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="bind-form">
    <h2>首次登录身份核验</h2>
    <p class="muted">请填写与校内教师信息一致的姓名和工号。验证通过后，下次可直接使用飞书登录。</p>
    <div v-if="errorMessage" class="error-tip">{{ errorMessage }}</div>
    <div class="field">
      <label>姓名<span class="required">*</span></label>
      <van-field v-model="form.name" placeholder="请输入姓名" autocomplete="name" />
    </div>
    <div class="field">
      <label>工号<span class="required">*</span></label>
      <van-field v-model="form.employeeId" placeholder="请输入工号" autocomplete="off" />
    </div>
    <van-button block type="primary" class="btn-height" :loading="submitting" @click="submit">
      验证并绑定
    </van-button>
    <div class="bind-footer">
      <button type="button" class="text-link" :disabled="submitting" @click="emit('cancel')">返回登录</button>
      <span v-if="expiresAtText" class="muted small">请于 {{ expiresAtText }} 前完成</span>
    </div>
  </div>
</template>

<style scoped>
.bind-form h2 {
  margin: 0 0 8px;
  font-size: 19px;
  text-align: center;
}
.bind-form > p {
  margin: 0 0 16px;
  font-size: 12px;
  line-height: 20px;
}
.error-tip {
  margin-bottom: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fdecec;
  color: var(--danger);
  font-size: 12px;
  line-height: 18px;
}
.field {
  margin-bottom: 12px;
}
.field label {
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
.bind-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 14px;
}
.text-link {
  min-height: 40px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--brand);
  font-size: 13px;
}
</style>
