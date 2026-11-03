<script setup lang="ts">
// 掌握程度的色点（ui.md 2.1、3）：学过的和待学是实心圆，暂停、只收录是空心圆。
// 色点只是辅助，旁边总是配文字，所以对读屏隐藏
import { computed } from 'vue'
import type { Mastery } from '@/api/knowledgePoints'
import { masteryDots } from '@/utils/labels'

const props = defineProps<{ mastery: Mastery }>()

const style = computed(() => {
  const dot = masteryDots[props.mastery]
  return dot.fill ? { background: dot.fill } : { boxShadow: `inset 0 0 0 1.5px ${dot.ring}` }
})
</script>

<template>
  <span class="dot" :style="style" aria-hidden="true"></span>
</template>

<style scoped>
.dot {
  flex: none;
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
</style>
