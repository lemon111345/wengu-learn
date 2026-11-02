<script setup lang="ts">
// 页面底部的输入区（ui.md 4.2、5.3）：标签、可以纵向拉伸的多行输入框、状态和发送按钮。
// Ctrl Enter（Mac 上 Cmd Enter）发送；左边的插槽放“返回评分页”这类次按钮
import { ref } from 'vue'
import { modKeyLabel, useShortcut } from '@/shortcuts/useShortcut'

const props = withDefaults(
  defineProps<{
    label: string
    placeholder?: string
    /** 老师正在回复：不能发，下方显示“老师正在回复” */
    busy?: boolean
    /** 不能输入（如会话已经结束） */
    disabled?: boolean
  }>(),
  { placeholder: '', busy: false, disabled: false },
)
const emit = defineEmits<{ send: [content: string] }>()

const text = ref('')

function send() {
  const content = text.value.trim()
  if (props.busy || props.disabled || content === '') {
    return
  }
  emit('send', content)
  text.value = ''
}

// 组合键在输入框中也生效
useShortcut('Mod+Enter', send)
</script>

<template>
  <div class="chat-input">
    <label for="chat-input" class="label">{{ label }}</label>
    <el-input
      id="chat-input"
      v-model="text"
      type="textarea"
      :autosize="{ minRows: 3, maxRows: 10 }"
      resize="vertical"
      :placeholder="busy ? '老师说完后再回答' : placeholder"
      :disabled="disabled"
    />
    <div class="actions">
      <slot name="left" />
      <span class="status" aria-live="polite">{{ busy ? '老师正在回复' : '' }}</span>
      <el-button type="primary" class="send" :disabled="busy || disabled || text.trim() === ''" @click="send">
        发送<kbd>{{ modKeyLabel }} Enter</kbd>
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.chat-input {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.label {
  color: var(--wg-text-muted);
  font-size: 14px;
}

.chat-input :deep(.el-textarea__inner) {
  padding: 12px 14px;
  font-size: 15px;
  line-height: 1.7;
}

.actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.status {
  flex: 1;
  color: var(--wg-text-muted);
  font-size: 13px;
}

.send kbd {
  margin-left: 10px;
}
</style>
