<script setup lang="ts">
// 学习页 /session/:id（ui.md 5.3）：标题区、对话、底部输入。学完（会话结束）后同一页切换成收尾与评分（5.4）
import { computed, onMounted } from 'vue'
import { studyHeader } from '@/layouts/studyHeader'
import { typeLabels } from '@/utils/labels'
import ChatInput from './ChatInput.vue'
import ChatThread from './ChatThread.vue'
import FinishPanel from './FinishPanel.vue'
import { useAutoScroll, useChat } from './useChat'

const props = defineProps<{ id: string }>()
const sessionId = () => Number(props.id)

const { session, messages, streamingText, busy, error, loadError, refresh, reply, send, skipExplanation } =
  useChat(sessionId)

onMounted(async () => {
  await refresh()
  // 刚开始：老师还没开口，也没有在后台生成，请老师说第一句
  const s = session.value
  if (s && !s.ended && s.messages.length === 0 && !s.generating) {
    await reply()
  }
})

/** 标题区的标签：新学还是复习、类型。从今天的计划里找，找不到（不是从今天页进来的）就不显示 */
const tags = computed(() => {
  const plan = studyHeader.plan
  const kpId = session.value?.kpId
  if (!plan || kpId == null) {
    return []
  }
  const review = plan.reviews.find((i) => i.kpId === kpId)
  const fresh = plan.newItems.find((i) => i.kpId === kpId)
  const done = plan.completed.find((i) => i.kpId === kpId)
  const item = review ?? fresh ?? done
  if (!item) {
    return []
  }
  const isNew = fresh !== undefined || (done?.firstReview ?? false)
  return [isNew ? '新学' : '复习', typeLabels[item.type]]
})

/** 最后一条老师的回复要求完整作答，学生还没答：上面的对话折叠起来，免得照着抄（ui.md 5.3） */
const restating = computed(() => {
  const last = session.value?.messages.at(-1)
  return !session.value?.ended && last?.role === 'ASSISTANT' && last.marker === 'RESTATE'
})
const collapsed = computed(() => (restating.value ? messages.value.length - 1 : 0))
const rounds = computed(() => messages.value.slice(0, collapsed.value).filter((m) => m.role === 'USER').length)

/** 跳过讲解：老师开口以后、要求完整作答之前可以点 */
const canSkip = computed(
  () => !!session.value && !session.value.ended && messages.value.length > 0 && !restating.value && !busy.value,
)

useAutoScroll(() => [messages.value.length, streamingText.value])
</script>

<template>
  <div v-if="loadError" class="load-error" role="alert">
    <p>{{ loadError }}</p>
    <el-button @click="refresh">重试</el-button>
  </div>
  <el-skeleton v-else-if="!session" :rows="6" animated />
  <div v-else class="session-page">
    <section class="title-area">
      <div v-if="tags.length > 0" class="tags">
        <span v-for="tag in tags" :key="tag" class="tag">{{ tag }}</span>
      </div>
      <h1>{{ session.kpTitle }}</h1>
      <div v-if="canSkip" class="skip">
        <el-button @click="skipExplanation">这个我会，直接完整作答</el-button>
        <span class="skip-hint">跳过讲解，讲完直接评分；没讲全的地方，老师会点出来</span>
      </div>
    </section>

    <FinishPanel v-if="session.ended" :session="session" />
    <template v-else>
      <ChatThread
        :messages="messages"
        :streaming-text="streamingText"
        :collapsed="collapsed"
        :collapsed-text="`前面的 ${rounds} 轮对话已折叠`"
      />
      <div v-if="error" class="chat-error" role="alert">
        <span>{{ error.message }}</span>
        <el-button v-if="error.retryable" text type="primary" @click="reply">重试</el-button>
      </div>
      <div class="input-dock">
        <ChatInput
          :label="restating ? '完整作答：不看上面，把整个问题讲一遍' : '你的回答'"
          :busy="busy"
          @send="send"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.session-page {
  display: flex;
  flex-direction: column;
  gap: 32px;
}

.title-area {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tags {
  display: flex;
  gap: 8px;
}

.tag {
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--wg-tag-bg);
  color: var(--wg-text-muted);
  font-size: 13px;
}

h1 {
  font-size: 30px;
  line-height: 1.35;
}

.skip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 16px;
}

.skip-hint {
  color: var(--wg-text-muted);
  font-size: 13px;
}

.chat-error {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  border-radius: var(--wg-radius-card);
  background: var(--wg-warn-soft);
  font-size: 14px;
}

/* 输入区在页面底部，上方 1px 分割线（ui.md 4.2）；对话长了也一直在视野里 */
.input-dock {
  position: sticky;
  bottom: 0;
  margin: 0 -16px -32px;
  padding: 16px 16px 20px;
  border-top: 1px solid var(--wg-border);
  background: var(--wg-bg);
}

.load-error {
  display: flex;
  align-items: center;
  gap: 16px;
}

@media (max-width: 600px) {
  h1 {
    font-size: 24px;
  }
}
</style>
