<script setup lang="ts">
// 修改密码：当前密码、新密码、再输一次。新密码的规则和注册一致，前端先查一遍，最终以后端为准
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import FormField from '@/components/FormField.vue'
import { changePassword } from '@/api/auth'
import { ApiError } from '@/api/http'
import { checkPassword } from '@/views/auth/validation'

const open = defineModel<boolean>({ required: true })

const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const errors = reactive<{ oldPassword?: string; newPassword?: string; confirm?: string }>({})
const saving = ref(false)

// 每次打开都是空表单
watch(open, (value) => {
  if (value) {
    Object.assign(form, { oldPassword: '', newPassword: '', confirm: '' })
    Object.assign(errors, { oldPassword: undefined, newPassword: undefined, confirm: undefined })
  }
})

function validate(): boolean {
  errors.oldPassword = form.oldPassword === '' ? '请输入当前密码' : undefined
  errors.newPassword = checkPassword(form.newPassword)
  errors.confirm = form.confirm !== form.newPassword ? '两次输入的新密码不一样' : undefined
  return !errors.oldPassword && !errors.newPassword && !errors.confirm
}

async function submit() {
  if (!validate()) {
    return
  }
  saving.value = true
  try {
    await changePassword(form.oldPassword, form.newPassword)
    open.value = false
    ElMessage.success('密码已修改，下次登录时用新密码')
  } catch (e) {
    // 当前密码不对是最常见的错误，放在当前密码下面
    if (e instanceof ApiError && e.status === 400) {
      errors.oldPassword = e.message
    } else {
      ElMessage.error(e instanceof ApiError ? e.message : '修改失败，请重试')
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="open" title="修改密码" width="440" align-center>
    <form class="form" @submit.prevent="submit">
      <FormField id="password-old" label="当前密码" :error="errors.oldPassword">
        <el-input id="password-old" v-model="form.oldPassword" type="password" autocomplete="current-password" />
      </FormField>
      <FormField id="password-new" label="新密码" hint="至少 8 位" :error="errors.newPassword">
        <el-input id="password-new" v-model="form.newPassword" type="password" autocomplete="new-password" />
      </FormField>
      <FormField id="password-confirm" label="再输一次新密码" :error="errors.confirm">
        <el-input id="password-confirm" v-model="form.confirm" type="password" autocomplete="new-password" />
      </FormField>
      <!-- 让输入框里按 Enter 也能提交 -->
      <button type="submit" hidden />
    </form>
    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">修改密码</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.form :deep(.el-input__wrapper) {
  min-height: 44px;
}
</style>
