<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '@/api/auth'
import { ApiError } from '@/api/http'
import { setCurrentUser } from '@/auth/session'
import FormField from '@/components/FormField.vue'
import { safeRedirect } from '@/router'
import AuthPage from './AuthPage.vue'

const route = useRoute()
const router = useRouter()

const form = reactive({ username: '', password: '' })
const errors = reactive<{ username?: string; password?: string; form?: string }>({})
const submitting = ref(false)

async function onSubmit() {
  if (submitting.value) {
    return
  }
  // 登录只检查有没有填：规则以注册时为准，不在这里重复
  errors.username = form.username.trim() === '' ? '请输入用户名' : undefined
  errors.password = form.password === '' ? '请输入密码' : undefined
  errors.form = undefined
  if (errors.username || errors.password) {
    return
  }

  submitting.value = true
  try {
    setCurrentUser(await login({ username: form.username.trim(), password: form.password }))
    await router.replace(safeRedirect(route.query.redirect))
  } catch (e) {
    // 用户名或密码错误也走这里：后端返回 401 LOGIN_FAILED，不区分是哪一个错了
    errors.form = e instanceof ApiError ? e.message : '登录失败，请重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthPage title="登录" submit-label="登录" :submitting="submitting" :form-error="errors.form" @submit="onSubmit">
    <FormField id="username" label="用户名" :error="errors.username">
      <el-input id="username" v-model="form.username" autocomplete="username" autofocus />
    </FormField>
    <FormField id="password" label="密码" :error="errors.password">
      <el-input id="password" v-model="form.password" type="password" autocomplete="current-password" />
    </FormField>
    <template #switch>
      还没有账号？<RouterLink to="/register">注册</RouterLink>
    </template>
  </AuthPage>
</template>
