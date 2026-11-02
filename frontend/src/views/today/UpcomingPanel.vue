<script setup lang="ts">
// 右侧栏“接下来 7 天要复习的”（ui.md 5.2）：7 根柱子，最多的一天用主色；下面是“每天新学”
import { computed } from 'vue'
import type { UpcomingDay } from '@/api/plan'
import { weekdayLabel } from '@/utils/date'
import DailyNewStepper from './DailyNewStepper.vue'

const props = defineProps<{ days: UpcomingDay[]; dailyNewLimit: number }>()
const emit = defineEmits<{ saved: [value: number] }>()

// 柱子最高 96px；有复习的至少 4px，免得 1 个和 0 个看起来一样
const MAX_HEIGHT = 96
const max = computed(() => Math.max(0, ...props.days.map((d) => d.count)))
const bars = computed(() =>
  props.days.map((d) => ({
    ...d,
    label: weekdayLabel(d.date),
    height: max.value === 0 || d.count === 0 ? 0 : Math.max(4, Math.round((d.count / max.value) * MAX_HEIGHT)),
    peak: d.count > 0 && d.count === max.value,
  })),
)
</script>

<template>
  <section class="upcoming">
    <h2>接下来 7 天要复习的</h2>
    <ol class="bars">
      <li v-for="bar in bars" :key="bar.date" class="bar-column">
        <span class="bar-count">{{ bar.count }}</span>
        <span class="bar" :class="{ peak: bar.peak }" :style="{ height: `${bar.height}px` }" />
        <span class="bar-label">{{ bar.label }}</span>
      </li>
    </ol>
    <div class="daily-new">
      <span>每天新学</span>
      <DailyNewStepper :value="dailyNewLimit" @saved="emit('saved', $event)" />
    </div>
  </section>
</template>

<style scoped>
.upcoming {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 22px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

h2 {
  font-size: 16px;
}

.bars {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 8px;
  align-items: end;
  height: 150px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.bar-column {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  height: 100%;
}

.bar-count {
  font-size: 13px;
}

.bar {
  width: 100%;
  max-width: 22px;
  border-radius: 4px;
  background: var(--wg-heat-2);
}

.bar.peak {
  background: var(--wg-primary);
}

.bar-label {
  color: var(--wg-text-muted);
  font-size: 12px;
}

.daily-new {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-top: 14px;
  border-top: 1px solid var(--wg-border);
  font-size: 15px;
}

.daily-new > span {
  line-height: 44px;
}
</style>
