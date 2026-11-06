<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
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
const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMessage = ref('')
const form = reactive({ name: '', employeeId: '' })

const rules: FormRules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  employeeId: [{ required: true, message: '请输入工号', trigger: 'blur' }],
}

const expiresAtText = computed(() => {
  const value = new Date(props.bindingTicketExpiresAt)
  return Number.isNaN(value.getTime()) ? '' : value.toLocaleString('zh-CN', { hour12: false })
})

async function submit(): Promise<void> {
  const formEl = formRef.value
  if (!formEl) return
  const valid = await formEl.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await userStore.bindFeishu({
      bindingTicket: props.bindingTicket,
      name: form.name.trim(),
      employeeId: form.employeeId.trim(),
    }, props.clientType)
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
  <div>
    <h1 class="bind-title">首次登录身份核验</h1>
    <p class="bind-description">
      请填写与校内教师信息一致的姓名和工号。验证通过后，下次可直接使用飞书登录。
    </p>
    <el-alert
      v-if="errorMessage"
      :title="errorMessage"
      type="error"
      :closable="false"
      class="bind-alert"
    />
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      size="large"
      @submit.prevent="submit"
    >
      <el-form-item prop="name">
        <el-input v-model="form.name" placeholder="姓名" autocomplete="name" />
      </el-form-item>
      <el-form-item prop="employeeId">
        <el-input v-model="form.employeeId" placeholder="工号" autocomplete="off" />
      </el-form-item>
      <el-form-item>
        <el-button
          class="bind-button"
          type="primary"
          native-type="submit"
          :loading="submitting"
        >
          验证并绑定
        </el-button>
      </el-form-item>
    </el-form>
    <div class="bind-footer">
      <el-button link type="primary" :disabled="submitting" @click="emit('cancel')">返回登录</el-button>
      <span v-if="expiresAtText" class="bind-expiry">请于 {{ expiresAtText }} 前完成</span>
    </div>
  </div>
</template>

<style scoped>
.bind-title {
  margin: 0 0 var(--space-3);
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
  text-align: center;
}

.bind-description {
  margin: 0 0 var(--space-5);
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 22px;
}

.bind-alert {
  margin-bottom: var(--space-4);
}

.bind-button {
  width: 100%;
}

.bind-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.bind-expiry {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-align: right;
}

@media (max-width: 768px) {
  .bind-button,
  .bind-footer :deep(.el-button) {
    min-height: 44px;
  }

  .bind-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .bind-expiry {
    text-align: left;
  }
}
</style>
