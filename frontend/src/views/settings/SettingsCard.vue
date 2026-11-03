<script setup lang="ts">
// 设置页的一张卡片：标题加内容（ui.md 5.10，数值见 mockups/13-settings）
defineProps<{ title: string }>()
</script>

<template>
  <section class="card">
    <h2>{{ title }}</h2>
    <slot />
  </section>
</template>

<style scoped>
.card {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 28px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

h2 {
  font-size: 18px;
  font-weight: 700;
}

@media (max-width: 600px) {
  .card {
    padding: 20px;
  }
}
</style>
