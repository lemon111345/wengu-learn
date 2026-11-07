<script setup lang="ts">
// 加减按钮：只负责显示和改数字，什么时候保存由使用者决定
// （今天页改了要点“保存”，设置页自动保存，见 ui.md 设计原则 3）
const props = defineProps<{
  min: number
  max: number
  /** 读屏软件读的名称，如“每天学几个” */
  label: string
}>()

const model = defineModel<number>({ required: true })

function change(delta: number) {
  model.value = Math.min(props.max, Math.max(props.min, model.value + delta))
}
</script>

<template>
  <div class="controls" role="group" :aria-label="label">
    <button type="button" class="step" aria-label="减少一个" :disabled="model <= min" @click="change(-1)">−</button>
    <span class="value" aria-live="polite">{{ model }}</span>
    <button type="button" class="step" aria-label="增加一个" :disabled="model >= max" @click="change(1)">+</button>
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  align-items: center;
  gap: 10px;
}

.step {
  width: 44px;
  height: 44px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-control);
  background: var(--wg-bg);
  color: var(--wg-text);
  font: inherit;
  font-size: 20px;
  cursor: pointer;
}

.step:hover:not(:disabled) {
  border-color: var(--wg-border-strong);
}

.step:disabled {
  color: var(--wg-border-strong);
  cursor: not-allowed;
}

.value {
  min-width: 28px;
  font-size: 22px;
  font-weight: 700;
  text-align: center;
}
</style>
