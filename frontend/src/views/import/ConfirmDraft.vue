<script setup lang="ts">
// 确认知识点（ui.md 5.8），下一步实现
import type { DraftView } from '@/api/drafts'

defineProps<{ draft: DraftView }>()
defineEmits<{ changed: [] }>()
</script>

<template>
  <p class="wg-page-body wg-muted">确认页在下一步实现。</p>
</template>
