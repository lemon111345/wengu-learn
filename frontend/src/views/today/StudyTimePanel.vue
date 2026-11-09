<script setup lang="ts">
// 右侧栏“学习时长”（ui.md 5.2，决策 041）：今天、本周、连续学习天数，三个数都由后端算好
defineProps<{ todayMinutes: number; weekMinutes: number; streak: number }>()

function duration(minutes: number): string {
  if (minutes < 60) {
    return `${minutes} 分钟`
  }
  const hours = `${Math.floor(minutes / 60)} 小时`
  const rest = minutes % 60
  return rest === 0 ? hours : `${hours} ${rest} 分钟`
}
</script>

<template>
  <section class="study-time">
    <h2>学习时长</h2>
    <dl>
      <div>
        <dt>今天</dt>
        <dd>{{ duration(todayMinutes) }}</dd>
      </div>
      <div>
        <dt>本周</dt>
        <dd>{{ duration(weekMinutes) }}</dd>
      </div>
      <div>
        <dt>连续学习</dt>
        <dd>{{ streak }} 天</dd>
      </div>
    </dl>
  </section>
</template>

<style scoped>
.study-time {
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

dl {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 0;
  font-size: 15px;
}

dl > div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

dt {
  color: var(--wg-text-muted);
}

dd {
  margin: 0;
}
</style>
