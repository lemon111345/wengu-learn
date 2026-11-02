<script setup lang="ts">
// 应用框架中各页面的顶栏（ui.md 4.1）：左边宋体标题，右边放各页面自己的操作
defineProps<{ title: string }>()
</script>

<template>
  <header class="page-header">
    <h1>{{ title }}</h1>
    <div class="actions">
      <slot />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  min-height: 68px;
  padding: 12px 40px;
  border-bottom: 1px solid var(--wg-border);
}

h1 {
  font-size: 20px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

@media (max-width: 600px) {
  .page-header {
    padding: 12px 16px;
  }
}
</style>
