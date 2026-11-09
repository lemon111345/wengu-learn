<script setup lang="ts">
// 对话区（ui.md 第 3 节“对话消息”）：老师的话按正文排版，上方写称呼；学生的话放在灰底块中，上方写“你”。
// 不用聊天气泡。前面的消息可以折叠成一行，点开再看
import { computed, ref, watch } from 'vue'
import type { MessageView } from '@/api/sessions'
import MessageContent from './MessageContent.vue'

const props = withDefaults(
  defineProps<{
    messages: MessageView[]
    /** 老师正在说的话，末尾显示光标；为 null 表示没有在流式输出 */
    streamingText?: string | null
    /** 前面折叠掉几条；0 表示不折叠 */
    collapsed?: number
    /** 折叠时提示的说法，如“前面的 6 轮对话已折叠” */
    collapsedText?: string
    /** 某些学生消息上方不写“你”，写别的（如“你的完整作答”），按消息 id */
    userLabels?: Record<number, string>
    teacherName?: string
  }>(),
  { streamingText: null, collapsed: 0, collapsedText: '', userLabels: () => ({}), teacherName: '老师' },
)

// 用户点开过折叠就一直展开；要折叠的范围变了（如进入了新的完整作答）时重新折叠
const expanded = ref(false)
watch(
  () => props.collapsed,
  () => (expanded.value = false),
)

const visible = computed(() => (expanded.value ? props.messages : props.messages.slice(props.collapsed)))
</script>

<template>
  <div class="thread">
    <button v-if="collapsed > 0 && !expanded" type="button" class="collapsed" @click="expanded = true">
      {{ collapsedText }}，点击展开
    </button>
    <template v-for="m in visible" :key="m.id">
      <!-- id 供问答记录的问题目录跳转 -->
      <div v-if="m.role === 'ASSISTANT'" :id="`message-${m.id}`" class="teacher">
        <span class="speaker">{{ teacherName }}</span>
        <MessageContent :content="m.content" formatted />
      </div>
      <div v-else :id="`message-${m.id}`" class="student">
        <span class="speaker">{{ userLabels[m.id] ?? '你' }}</span>
        <MessageContent :content="m.content" :formatted="false" />
      </div>
    </template>
    <div v-if="streamingText !== null" class="teacher" aria-live="polite">
      <span class="speaker">{{ teacherName }}</span>
      <div class="streaming">
        <MessageContent :content="streamingText" formatted />
        <span class="cursor" aria-hidden="true" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.thread {
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.speaker {
  display: block;
  margin-bottom: 6px;
  color: var(--wg-text-muted);
  font-size: 13px;
}

.student {
  scroll-margin-top: 16px;
  padding: 16px 18px;
  border-radius: var(--wg-radius-card);
  background: var(--wg-surface);
}

.streaming {
  position: relative;
}

/* 流式输出时末尾的主色竖线光标（ui.md 5.3）；减少动效时不闪 */
.cursor {
  display: inline-block;
  width: 2px;
  height: 1.1em;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--wg-primary);
  animation: blink 1s steps(1) infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .cursor {
    animation: none;
  }
}

.collapsed {
  align-self: flex-start;
  min-height: 44px;
  padding: 0 14px;
  border: 1px dashed var(--wg-border-strong);
  border-radius: var(--wg-radius-control);
  background: none;
  color: var(--wg-text-muted);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}
</style>
