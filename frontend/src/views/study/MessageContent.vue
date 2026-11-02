<script setup lang="ts">
// 一条消息的正文。老师的回复按常见的几种 Markdown 显示（utils/markdown.ts，内容已转义，v-html 安全）；
// 学生的回答是自己打的字，原样显示、保留换行
import { computed } from 'vue'
import { renderMessage } from '@/utils/markdown'

const props = defineProps<{ content: string; formatted: boolean }>()

const html = computed(() => (props.formatted ? renderMessage(props.content) : ''))
</script>

<template>
  <div v-if="formatted" class="message-content" v-html="html" />
  <div v-else class="message-content plain">{{ content }}</div>
</template>

<style scoped>
.message-content {
  font-size: 16px;
  line-height: 1.85;
  overflow-wrap: anywhere;
}

.plain {
  white-space: pre-wrap;
}

.message-content :deep(p) {
  margin: 0 0 12px;
}

.message-content :deep(p:last-child),
.message-content :deep(ul:last-child),
.message-content :deep(ol:last-child),
.message-content :deep(pre:last-child) {
  margin-bottom: 0;
}

.message-content :deep(ul),
.message-content :deep(ol) {
  margin: 0 0 12px;
  padding-left: 24px;
}

.message-content :deep(code) {
  padding: 1px 6px;
  border-radius: var(--wg-radius-kbd);
  background: var(--wg-tag-bg);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 14px;
}

/* 代码块在自己的容器内横向滚动，页面不横向滚动（ui.md 4.3） */
.message-content :deep(pre) {
  margin: 0 0 12px;
  padding: 12px 16px;
  overflow-x: auto;
  border-radius: var(--wg-radius-card);
  background: var(--wg-surface);
  line-height: 1.6;
}

.message-content :deep(pre code) {
  padding: 0;
  background: none;
}

/* 表格宽时在自己的容器内横向滚动 */
.message-content :deep(.table-wrap) {
  margin: 0 0 12px;
  overflow-x: auto;
}

.message-content :deep(table) {
  border-collapse: collapse;
  font-size: 14px;
  line-height: 1.6;
}

.message-content :deep(th),
.message-content :deep(td) {
  padding: 6px 12px;
  border: 1px solid var(--wg-border);
  text-align: left;
  white-space: nowrap;
}

.message-content :deep(th) {
  background: var(--wg-surface);
}
</style>
