<script setup lang="ts">
// 表单的一项：标签在上，输入框放进插槽，下面是说明或错误（有错误时显示错误）
defineProps<{
  /** 输入框的 id，标签点击时聚焦它 */
  id: string
  label: string
  hint?: string
  error?: string
}>()
</script>

<template>
  <div class="field">
    <label :for="id">{{ label }}</label>
    <slot />
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-else-if="hint" class="hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

label {
  font-size: 14px;
  font-weight: 700;
}

.hint,
.error {
  font-size: 13px;
  line-height: 1.5;
}

.hint {
  color: var(--wg-text-muted);
}

.error {
  color: var(--wg-warn);
}
</style>
