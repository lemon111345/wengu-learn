<script setup lang="ts">
// 概况面板（ui.md 5.2）：左边是还剩多少和进度，右边是“下一个”
import { computed } from 'vue'
import type { TodayPlan } from '@/api/plan'
import ProgressSegments from '@/components/ProgressSegments.vue'
import { typeLabels } from '@/utils/labels'
import { nextItem, overviewDescription, overviewTitle } from './summary'

const props = defineProps<{ plan: TodayPlan }>()
const emit = defineEmits<{ start: [kpId: number] }>()

const next = computed(() => nextItem(props.plan))
const done = computed(() => props.plan.completed.length)
const total = computed(() => done.value + props.plan.reviews.length + props.plan.newItems.length)
</script>

<template>
  <section class="overview">
    <div class="summary">
      <div class="text">
        <h1>{{ overviewTitle(plan) }}</h1>
        <p class="description">{{ overviewDescription(plan) }}</p>
      </div>
      <div v-if="total > 0" class="progress">
        <ProgressSegments :done="done" :total="total" />
        <span class="count">{{ done }} / {{ total }}</span>
      </div>
    </div>
    <div v-if="next" class="next">
      <span class="next-label">下一个</span>
      <span class="next-title">{{ next.title }}</span>
      <div class="tags">
        <span class="tag">{{ next.isNew ? '新学' : '复习' }}</span>
        <span class="tag">{{ typeLabels[next.type] }}</span>
      </div>
      <el-button type="primary" class="start" @click="emit('start', next.kpId)">
        {{ done > 0 ? '继续学习' : '开始学习' }}<kbd>Enter</kbd>
      </el-button>
    </div>
  </section>
</template>

<style scoped>
.overview {
  display: flex;
  flex-wrap: wrap;
  gap: 28px;
  padding: 28px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

.summary {
  flex: 1 1 280px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 18px;
}

.text {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

h1 {
  font-size: 34px;
  line-height: 1.3;
}

.description {
  color: var(--wg-text-muted);
  font-size: 15px;
}

.progress {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.count {
  color: var(--wg-text-muted);
  font-size: 13px;
}

.next {
  flex: 1 1 320px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 22px;
  background: var(--wg-surface);
  border-radius: var(--wg-radius-card);
}

.next-label {
  color: var(--wg-text-muted);
  font-size: 13px;
}

.next-title {
  font-size: 19px;
  font-weight: 700;
  line-height: 1.5;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag {
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--wg-tag-bg);
  color: var(--wg-text-muted);
  font-size: 13px;
}

.start {
  align-self: flex-start;
  height: 48px;
  margin-top: 4px;
  padding: 0 22px;
  font-size: 16px;
}

.start kbd {
  margin-left: 12px;
}
</style>
