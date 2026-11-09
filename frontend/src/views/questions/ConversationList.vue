<script setup lang="ts">
// 问答记录的左栏（ui.md 5.12）：搜索框和对话列表，顺序、匹配都由后端决定
import type { FollowUpSummary } from '@/api/followUps'
import { formatMonthDay } from '@/utils/date'

const props = defineProps<{
  /** 还没取到时为 null */
  items: FollowUpSummary[] | null
  loadError: string | null
  selectedId: number | null
  keyword: string
}>()
const emit = defineEmits<{ 'update:keyword': [value: string]; retry: [] }>()

function meta(item: FollowUpSummary): string {
  const parts = [`${item.questionCount} 问`, formatMonthDay(item.lastActiveDate)]
  return item.dueToday ? [...parts, '今天要复习'].join(' · ') : parts.join(' · ')
}

function isSelected(item: FollowUpSummary) {
  return item.studySessionId === props.selectedId
}
</script>

<template>
  <aside class="conversations">
    <el-input
      :model-value="keyword"
      class="search"
      placeholder="搜标题、问题和回答"
      aria-label="搜索问答记录"
      clearable
      @update:model-value="(v: string) => emit('update:keyword', v)"
    />
    <section v-if="loadError && !items" class="state" role="alert">
      <p>{{ loadError }}</p>
      <el-button @click="emit('retry')">重试</el-button>
    </section>
    <el-skeleton v-else-if="!items" :rows="5" animated />
    <p v-else-if="items.length === 0 && keyword.trim() !== ''" class="state">没有找到相关的对话</p>
    <p v-else-if="items.length === 0" class="state">学完一个知识点后，在评分页点“接着问”，问过的内容都会留在这里。</p>
    <ul v-else class="rows">
      <li v-for="item in items" :key="item.followUpSessionId">
        <RouterLink
          :to="`/questions/${item.studySessionId}`"
          class="row"
          :class="{ active: isSelected(item) }"
          :aria-current="isSelected(item) ? 'page' : undefined"
        >
          <span class="title">{{ item.kpTitle ?? '这个知识点已经不在了' }}</span>
          <span class="question">{{ item.lastQuestion }}</span>
          <span class="meta">{{ meta(item) }}</span>
        </RouterLink>
      </li>
    </ul>
  </aside>
</template>

<style scoped>
.conversations {
  position: sticky;
  top: 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: calc(100vh - 48px);
}

.search {
  flex: none;
}

.search :deep(.el-input__wrapper) {
  min-height: 44px;
}

.state {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
  color: var(--wg-text-muted);
  font-size: 14px;
  line-height: 1.75;
}

.rows {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-height: 0;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}

.row {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border-radius: var(--wg-radius-control);
  color: var(--wg-text);
}

.row:hover {
  color: var(--wg-text);
  background: var(--wg-tag-bg);
}

.row.active {
  background: var(--wg-primary-soft);
}

.title {
  font-size: 15px;
  font-weight: 700;
  line-height: 1.5;
}

.question {
  overflow: hidden;
  color: var(--wg-text-muted);
  font-size: 14px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.meta {
  color: var(--wg-text-muted);
  font-size: 13px;
}

@media (max-width: 900px) {
  .conversations {
    position: static;
    max-height: none;
  }

  .rows {
    overflow-y: visible;
  }
}
</style>
