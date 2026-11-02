<script setup lang="ts">
// 分段进度条（ui.md 第 3 节）：一段代表一个知识点，已完成用主色，当前（下一个）浅蓝，未开始灰色。
// 今天页高 10，学习页顶栏高 6
import { computed } from 'vue'

const props = withDefaults(defineProps<{ done: number; total: number; height?: number }>(), { height: 10 })

const segments = computed(() =>
  Array.from({ length: props.total }, (_, i) => (i < props.done ? 'done' : i === props.done ? 'current' : 'todo')),
)
</script>

<template>
  <div class="segments" role="img" :aria-label="`今天共 ${total} 个，已完成 ${done} 个`">
    <span
      v-for="(s, i) in segments"
      :key="i"
      class="segment"
      :class="s"
      :style="{ height: `${height}px`, borderRadius: `${height / 2}px` }"
    />
  </div>
</template>

<style scoped>
.segments {
  display: flex;
  gap: 5px;
}

.segment {
  flex: 1;
  background: var(--wg-tag-bg);
}

.segment.done {
  background: var(--wg-primary);
}

.segment.current {
  background: var(--wg-heat-2);
}
</style>
