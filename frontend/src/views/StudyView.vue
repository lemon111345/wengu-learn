<script setup lang="ts">
// 占位，由 #23 实现
defineProps<{ kpId: string }>()
</script>

<template>
  <h1>学习</h1>
  <p class="wg-muted">这个页面还在开发中（知识点 {{ kpId }}）。</p>
</template>
