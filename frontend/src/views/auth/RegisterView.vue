<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchRegistration, register } from '@/api/auth'
import { ApiError } from '@/api/http'
import { setCurrentUser } from '@/auth/session'
import FormField from '@/components/FormField.vue'
import AuthPage from './AuthPage.vue'
import { checkPassword, checkUsername } from './validation'

const router = useRouter()

// 注册是否开放；查询完成前为 null，不显示表单，免得注册关闭时表单闪一下
const open = ref<boolean | null>(null)
const form = reactive({ username: '', password: '', confirm: '' })
const errors = reactive<{ username?: string; password?: string; confirm?: string; form?: string }>({})
const submitting = ref(false)

onMounted(async () => {
  try {
    open.value = (await fetchRegistration()).open
  } catch {
    // 查不到时照常显示表单，提交时会显示原因
    open.value = true
  }
})

function validate() {
  errors.username = checkUsername(form.username)
  errors.password = checkPassword(form.password)
  errors.confirm = form.confirm === form.password ? undefined : '两次输入的密码不一致'
  errors.form = undefined
  return !errors.username && !errors.password && !errors.confirm
}

async function onSubmit() {
  if (submitting.value || !validate()) {
    return
  }
  submitting.value = true
  try {
    const user = await register({
      username: form.username.trim(),
      password: form.password,
      // 浏览器的时区静默提交，界面上不提示（ui.md 5.1）
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    })
    setCurrentUser(user)
    // 注册成功后进入今天页，新用户看到空状态
    await router.replace('/')
  } catch (e) {
    if (e instanceof ApiError && e.code === 'USERNAME_TAKEN') {
      errors.username = e.message
    } else if (e instanceof ApiError && e.code === 'REGISTRATION_CLOSED') {
      open.value = false
    } else {
      errors.form = e instanceof ApiError ? e.message : '注册失败，请重试'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthPage
    title="注册"
    :submit-label="open ? '注册并开始' : undefined"
    :submitting="submitting"
    :form-error="errors.form"
    @submit="onSubmit"
  >
    <FormField id="username" label="用户名" :error="errors.username">
      <el-input id="username" v-model="form.username" autocomplete="username" autofocus />
    </FormField>
    <FormField id="password" label="密码" hint="至少 8 位" :error="errors.password">
      <el-input id="password" v-model="form.password" type="password" autocomplete="new-password" />
    </FormField>
    <FormField id="confirm" label="确认密码" :error="errors.confirm">
      <el-input id="confirm" v-model="form.confirm" type="password" autocomplete="new-password" />
    </FormField>
    <template #after-submit>
      <p class="agreement">
        注册即表示同意<a href="/terms" target="_blank">《用户协议》</a>和<a href="/privacy" target="_blank">《隐私政策》</a>
      </p>
    </template>
    <template #closed>
      <p v-if="open === false" class="closed">暂未开放注册</p>
    </template>
    <template #switch>
      已有账号？<RouterLink to="/login">登录</RouterLink>
    </template>
  </AuthPage>
</template>

<style scoped>
.agreement {
  color: var(--wg-text-muted);
  font-size: 13px;
}

.closed {
  color: var(--wg-text-muted);
  font-size: 15px;
}
</style>
