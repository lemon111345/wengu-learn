<script setup lang="ts">
// “每天学几个”（含复习）的加减按钮。改了之后点“保存”才生效，“取消”恢复原值：
// 这个数一改，今天的新知识列表就跟着变，自动保存会让列表随着点击来回跳
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import NumberStepper from '@/components/NumberStepper.vue'
import { ApiError } from '@/api/http'
import { DAILY_NEW_MAX, DAILY_NEW_MIN, updateSettings } from '@/api/settings'

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
    <NumberStepper v-model="draft" :min="DAILY_NEW_MIN" :max="DAILY_NEW_MAX" label="每天学几个" />
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

.confirm {
  display: flex;
  gap: 4px;
}

.confirm .el-button {
  height: 40px;
}
</style>
