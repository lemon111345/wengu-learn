<script setup lang="ts">
// 收尾与评分（ui.md 5.4），下一步实现
import type { SessionView } from '@/api/sessions'

defineProps<{ session: SessionView }>()
</script>

<template>
  <p class="wg-muted">学完了，评分在下一步实现。</p>
</template>
