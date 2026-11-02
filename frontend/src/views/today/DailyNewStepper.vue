<script setup lang="ts">
// “每天新学”的加减按钮。改了之后点“保存”才生效，“取消”恢复原值：
// 这个数一改，今天的新知识列表就跟着变，自动保存会让列表随着点击来回跳
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/http'
import { updateSettings } from '@/api/settings'

// 和后端 UpdateSettingsRequest 的校验一致
const MIN = 0
const MAX = 50

const props = withDefaults(
  defineProps<{
    value: number
    /** 放在一行的右边时靠右（今天页），单独一块时靠左（空状态） */
    align?: 'start' | 'end'
  }>(),
  { align: 'end' },
)
const emit = defineEmits<{ saved: [value: number] }>()

const draft = ref(props.value)
const saving = ref(false)

// 保存后父组件重新拉取计划，传进来新值
watch(
  () => props.value,
  (v) => (draft.value = v),
)

function change(delta: number) {
  draft.value = Math.min(MAX, Math.max(MIN, draft.value + delta))
}

async function save() {
  saving.value = true
  try {
    const settings = await updateSettings({ dailyNewLimit: draft.value })
    emit('saved', settings.dailyNewLimit)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败，请重试')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="stepper" :class="`align-${align}`">
    <div class="controls">
      <button type="button" class="step" aria-label="减少一个" :disabled="draft <= MIN" @click="change(-1)">−</button>
      <span class="value" aria-live="polite">{{ draft }}</span>
      <button type="button" class="step" aria-label="增加一个" :disabled="draft >= MAX" @click="change(1)">+</button>
    </div>
    <div v-if="draft !== value" class="confirm">
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      <el-button text :disabled="saving" @click="draft = value">取消</el-button>
    </div>
  </div>
</template>

<style scoped>
.stepper {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.align-start {
  align-items: flex-start;
}

.align-end {
  align-items: flex-end;
}

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

.confirm {
  display: flex;
  gap: 4px;
}

.confirm .el-button {
  height: 40px;
}
</style>
