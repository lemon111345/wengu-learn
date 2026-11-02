<script setup lang="ts">
// 导入的三步进度（ui.md 5.7）：粘贴或上传 / 确认题目 / 抽取知识点。程序自己知道走到了哪一步，所以可以显示
defineProps<{ current: 1 | 2 | 3 }>()

const steps = ['粘贴或上传', '确认题目', '抽取知识点']
</script>

<template>
  <ol class="steps" aria-label="导入进度">
    <li
      v-for="(label, i) in steps"
      :key="label"
      class="step"
      :class="{ done: i + 1 < current, active: i + 1 === current }"
      :aria-current="i + 1 === current ? 'step' : undefined"
    >
      <span class="no" aria-hidden="true">{{ i + 1 < current ? '✓' : i + 1 }}</span>
      {{ label }}
    </li>
  </ol>
</template>

<style scoped>
.steps {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.step {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--wg-text-muted);
  font-size: 14px;
}

/* 步骤之间的分隔 */
.step + .step::before {
  content: '/';
  margin-right: 6px;
  color: var(--wg-border-strong);
}

.no {
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--wg-tag-bg);
  font-size: 12px;
}

.step.active {
  color: var(--wg-text);
  font-weight: 700;
}

.step.active .no,
.step.done .no {
  background: var(--wg-primary);
  color: var(--wg-bg);
}
</style>
