<script setup lang="ts">
// 删除后底部的提示：“已删除 N 个  撤销”，几秒后自动消失（ui.md 5.9）。
// 不用 ElMessage：它显示在页面顶部，而且里面放按钮要拼 VNode。
// 后端不限撤销的时间（决策 029），这里的时长只是入口留多久
import { onBeforeUnmount, watch } from 'vue'

/** 提示停留的时间 */
const DURATION_MS = 6000

const props = defineProps<{
  /** 要显示的提示；每次换一个新对象，计时重新开始。null 表示不显示 */
  notice: { text: string } | null
}>()
const emit = defineEmits<{ undo: []; close: [] }>()

let timer: ReturnType<typeof setTimeout> | undefined

watch(
  () => props.notice,
  (notice) => {
    clearTimeout(timer)
    if (notice) {
      timer = setTimeout(() => emit('close'), DURATION_MS)
    }
  },
)

onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <!-- role="status"：读屏软件会读出“已删除”，不打断当前的操作 -->
  <div class="toast-region" role="status">
    <div v-if="notice" class="toast">
      <span>{{ notice.text }}</span>
      <button type="button" class="undo" @click="emit('undo')">撤销</button>
    </div>
  </div>
</template>

<style scoped>
.toast-region {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 24px;
  z-index: 2000;
  display: flex;
  justify-content: center;
  padding: 0 16px;
  pointer-events: none;
}

/* 弹出层：白底、描边、弹窗的阴影（ui.md 2.3） */
.toast {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 6px 8px 6px 20px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-card);
  background: var(--wg-bg);
  box-shadow: var(--wg-shadow-pop);
  font-size: 15px;
  pointer-events: auto;
}

.undo {
  min-height: 40px;
  padding: 0 12px;
  border: none;
  border-radius: var(--wg-radius-control);
  background: var(--wg-primary-soft);
  color: var(--wg-primary);
  font: inherit;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
}
</style>
