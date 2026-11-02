<script setup lang="ts">
// 学习模式（ui.md 4.2）：隐藏侧栏，只做眼前这一件事。
// 顶栏：左边“← 今天”，中间当天的分段进度，右边“本次 X 分钟”
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { fetchTodayPlan } from '@/api/plan'
import ProgressSegments from '@/components/ProgressSegments.vue'
import { studyHeader } from './studyHeader'

const route = useRoute()

// 进度取不到时不显示，不影响学习
async function loadProgress() {
  try {
    studyHeader.plan = await fetchTodayPlan()
  } catch {
    studyHeader.plan = null
  }
}

onMounted(loadProgress)
// 进入下一个知识点、评完分时重新取
watch(() => [route.params.id, studyHeader.progressVersion], loadProgress)

const done = computed(() => studyHeader.plan?.completed.length ?? 0)
const total = computed(() => {
  const plan = studyHeader.plan
  return plan ? done.value + plan.reviews.length + plan.newItems.length : 0
})
</script>

<template>
  <div class="study">
    <header class="study-header">
      <RouterLink to="/" class="back">← 今天</RouterLink>
      <div v-if="total > 0" class="progress">
        <ProgressSegments class="segments" :done="done" :total="total" :height="6" />
        <span class="count">{{ done }} / {{ total }}</span>
      </div>
      <span class="minutes">{{ studyHeader.minutes === null ? '' : `本次 ${studyHeader.minutes} 分钟` }}</span>
    </header>
    <div class="study-column">
      <RouterView />
    </div>
  </div>
</template>

<style scoped>
.study {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.study-header {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 12px 24px;
  min-height: 60px;
  padding: 10px 32px;
  border-bottom: 1px solid var(--wg-border);
}

.back {
  justify-self: start;
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  color: var(--wg-text-muted);
}

.progress {
  display: flex;
  align-items: center;
  gap: 12px;
}

.segments {
  width: 220px;
}

.count {
  color: var(--wg-text-muted);
  font-size: 13px;
  white-space: nowrap;
}

.minutes {
  justify-self: end;
  color: var(--wg-text-muted);
  font-size: 14px;
  white-space: nowrap;
}

/* 读和写都需要合适的行宽，留白是有意的 */
.study-column {
  flex: 1;
  width: 100%;
  max-width: 760px;
  margin: 0 auto;
  padding: 32px 16px;
  display: flex;
  flex-direction: column;
}

@media (max-width: 600px) {
  .study-header {
    padding: 10px 16px;
  }

  .segments {
    width: 96px;
  }
}
</style>
