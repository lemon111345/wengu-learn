<script setup lang="ts">
// 《用户协议》《隐私政策》的占位页，正式文本在上线前补上（M3）
const props = defineProps<{ kind: 'terms' | 'privacy' }>()

const title = props.kind === 'terms' ? '用户协议' : '隐私政策'
</script>

<template>
  <main class="legal">
    <h1>{{ title }}</h1>
    <p class="wg-muted">正式文本会在开放使用前补上。</p>
  </main>
</template>

<style scoped>
.legal {
  max-width: 760px;
  margin: 0 auto;
  padding: 56px 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

h1 {
  font-size: 26px;
}
</style>
