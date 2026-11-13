<script setup lang="ts">
// 新学或复习列表（ui.md 5.2）：今天已完成的在前，显示对勾、灰色标题和评分，点开回顾，“答法”就地展开参考答法（#111）；
// 后面是还没学的，点一行就学这一个
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import type { CompletedItem, PlanItem } from '@/api/plan'
import { ratingColors, ratingLabels, typeLabels } from '@/utils/labels'
import ReferenceAnswer from '@/views/history/ReferenceAnswer.vue'

defineProps<{
  title: string
  completed: CompletedItem[]
  pending: PlanItem[]
  /** “下一个”的 id，这一条加粗 */
  nextKpId?: number
  /** 两个列表都为空时的说明 */
  emptyText: string
}>()

/** 展开了参考答法的那一行，同时只展开一行 */
const openKpId = ref<number | null>(null)

function toggle(kpId: number) {
  openKpId.value = openKpId.value === kpId ? null : kpId
}
</script>

<template>
  <section class="plan-list">
    <h2>
      {{ title }}
      <span class="count">{{ completed.length }} / {{ completed.length + pending.length }}</span>
    </h2>
    <ul v-if="completed.length + pending.length > 0">
      <li v-for="item in completed" :key="`done-${item.kpId}`" class="done-item">
        <div class="done-line">
          <!-- 没有会话的记录打不开，显示成普通的一行 -->
          <component
            :is="item.sessionId === null ? 'div' : RouterLink"
            class="row done"
            :class="{ pick: item.sessionId !== null }"
            :to="item.sessionId === null ? undefined : `/history/${item.sessionId}`"
          >
            <span class="title">
              <svg
                class="check"
                width="16"
                height="16"
                viewBox="0 0 16 16"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-label="已完成"
                role="img"
              >
                <path d="M3 8.5l3 3 7-7" />
              </svg>
              {{ item.title }}
            </span>
            <span class="rating">
              <span class="dot" :style="{ background: ratingColors[item.rating] }" />
              {{ ratingLabels[item.rating] }}
            </span>
          </component>
          <!-- 按钮放在链接外面：链接里不能再套按钮 -->
          <button
            v-if="item.sessionId !== null"
            type="button"
            class="answer-toggle"
            :aria-expanded="openKpId === item.kpId"
            @click="toggle(item.kpId)"
          >
            答法
          </button>
        </div>
        <ReferenceAnswer
          v-if="openKpId === item.kpId && item.sessionId !== null"
          class="answer"
          :session-id="item.sessionId"
        />
      </li>
      <li v-for="item in pending" :key="item.kpId">
        <!-- 用链接而不是点击事件：Tab 和 Enter 直接可用；焦点在链接上时，页面的 Enter 快捷键不生效，进的是这一行 -->
        <RouterLink class="row pick" :to="`/study/${item.kpId}`">
          <span class="title" :class="{ next: item.kpId === nextKpId }">{{ item.title }}</span>
          <span class="tag">{{ typeLabels[item.type] }}</span>
        </RouterLink>
      </li>
    </ul>
    <p v-else class="empty">{{ emptyText }}</p>
  </section>
</template>

<style scoped>
.plan-list {
  display: flex;
  flex-direction: column;
  padding: 6px 24px 16px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

h2 {
  margin: 18px 0 4px;
  font-size: 16px;
}

.count {
  color: var(--wg-text-muted);
  font-weight: 400;
}

ul {
  margin: 0;
  padding: 0;
  list-style: none;
}

.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 14px;
  padding: 12px 0;
  border-bottom: 1px solid var(--wg-border);
}

/* 已完成的一行：分隔线画在整条上，展开的答法也在线的上面 */
.done-item {
  border-bottom: 1px solid var(--wg-border);
}

.done-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.done-line .row {
  flex: 1;
  min-width: 0;
  border-bottom: none;
}

.answer-toggle {
  flex: none;
  min-height: 44px;
  padding: 0 4px;
  border: none;
  background: transparent;
  color: var(--wg-primary);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

.answer-toggle:hover {
  color: var(--wg-primary-hover);
}

.answer-toggle[aria-expanded='true'] {
  font-weight: 700;
}

.answer {
  margin-bottom: 12px;
}

.pick {
  color: var(--wg-text);
}

.pick:hover {
  color: var(--wg-primary-hover);
}

.title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
}

.title.next {
  font-weight: 700;
}

.done .title {
  color: var(--wg-text-muted);
}

.done.pick:hover .title {
  color: var(--wg-primary-hover);
}

.check {
  flex: none;
}

.tag {
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--wg-tag-bg);
  color: var(--wg-text-muted);
  font-size: 13px;
}

.rating {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 52px;
  font-size: 14px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.empty {
  padding: 12px 0;
  color: var(--wg-text-muted);
  font-size: 14px;
}
</style>
