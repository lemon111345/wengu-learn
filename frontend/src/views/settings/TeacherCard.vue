<script setup lang="ts">
// 老师的称呼（ui.md 5.10，决策 042）：失焦或按 Enter 时交给页面保存。
// 不用停手自动保存：打字中途清空时，后端恢复的“老师”会填回输入框，打断输入
import { computed, ref, watch } from 'vue'
import { TEACHER_NAME_MAX, type Settings } from '@/api/settings'
import SettingsCard from './SettingsCard.vue'

const props = defineProps<{ settings: Settings }>()
const emit = defineEmits<{ change: [changes: Partial<Settings>] }>()

const name = ref(props.settings.teacherName)
// 保存后跟着后端的值走：清空时变回“老师”，保存失败时变回原来的称呼
watch(
  () => props.settings.teacherName,
  (v) => (name.value = v),
)

const shownName = computed(() => name.value.trim() || '老师')

function commit() {
  if (name.value.trim() !== props.settings.teacherName) {
    emit('change', { teacherName: name.value })
  }
}
</script>

<template>
  <SettingsCard title="老师">
    <div class="field">
      <label class="name" for="settings-teacher-name">称呼</label>
      <el-input
        id="settings-teacher-name"
        v-model="name"
        class="input"
        :maxlength="TEACHER_NAME_MAX"
        @change="commit"
      />
      <span class="hint">对话里会显示为“{{ shownName }}”，讲解方式不变。</span>
    </div>
  </SettingsCard>
</template>

<style scoped>
.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.name {
  font-size: 15px;
  font-weight: 700;
}

.input {
  max-width: 280px;
}

.input :deep(.el-input__wrapper) {
  min-height: 44px;
}

.hint {
  color: var(--wg-text-muted);
  font-size: 14px;
  line-height: 1.6;
}
</style>
