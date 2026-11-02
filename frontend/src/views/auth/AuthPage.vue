<script setup lang="ts">
// 注册页和登录页共用的框架：左栏 + 右侧表单。表单字段由页面放进默认插槽
import AuthShowcase from './AuthShowcase.vue'

defineProps<{
  title: string
  /** 为空时不显示表单和提交按钮，如注册关闭时 */
  submitLabel?: string
  submitting?: boolean
  /** 不属于某个字段的错误，如“用户名或密码错误”、连不上服务器 */
  formError?: string
}>()

defineEmits<{ submit: [] }>()
</script>

<template>
  <div class="auth-page">
    <main class="main">
      <div class="panel">
        <h1>{{ title }}</h1>
        <form v-if="submitLabel" class="form" novalidate @submit.prevent="$emit('submit')">
          <slot />
          <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
          <el-button type="primary" native-type="submit" class="submit" :loading="submitting">
            {{ submitLabel }}
          </el-button>
          <slot name="after-submit" />
        </form>
        <slot v-else name="closed" />
        <p class="switch">
          <slot name="switch" />
        </p>
      </div>
    </main>
    <AuthShowcase class="showcase" />
  </div>
</template>

<style scoped>
.auth-page {
  display: flex;
  min-height: 100vh;
}

.showcase {
  flex: 1 1 520px;
  order: -1;
}

.main {
  flex: 1 1 440px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 56px 48px;
}

.panel {
  width: 100%;
  max-width: 380px;
  display: flex;
  flex-direction: column;
  gap: 22px;
}

/* 表单标题用黑体，和截图一致；宋体留给左栏 */
h1 {
  font-family: var(--wg-font-sans);
  font-size: 26px;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.form-error {
  color: var(--wg-warn);
  font-size: 14px;
}

.submit {
  width: 100%;
  height: 48px;
  margin-top: 4px;
  font-size: 16px;
}

.switch {
  padding-top: 18px;
  border-top: 1px solid var(--wg-border);
  color: var(--wg-text-muted);
  font-size: 14px;
}

.switch :slotted(a) {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  padding: 0 4px;
  font-weight: 700;
}

/* 窄屏：表单在上，左栏换到下方（ui.md 4.3） */
@media (max-width: 959px) {
  .auth-page {
    flex-direction: column;
  }

  .showcase {
    flex: none;
    order: 1;
  }

  .main {
    flex: none;
    padding: 40px 16px;
  }
}
</style>
