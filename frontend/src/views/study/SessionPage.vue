<script setup lang="ts">
// 学习页 /session/:id（ui.md 5.3）：标题区、对话、底部输入。学完（会话结束）后同一页切换成收尾与评分（5.4）
import { computed, onMounted, ref } from 'vue'
import { studyHeader } from '@/layouts/studyHeader'
import { modKeyLabel, useShortcut } from '@/shortcuts/useShortcut'
import { typeLabels } from '@/utils/labels'
import ChatInput from './ChatInput.vue'
import ChatThread from './ChatThread.vue'
import FinishPanel from './FinishPanel.vue'
import { useAutoScroll, useChat } from './useChat'

const props = defineProps<{ id: string }>()
const sessionId = () => Number(props.id)

const { session, messages, streamingText, busy, error, loadError, refresh, reply, send, skipExplanation } =
  useChat(sessionId)

/** 点过“读完了，开始学” */
const started = ref(false)

/** 老师还没开口、也没在后台生成：刚开始 */
const notStarted = computed(() => {
  const s = session.value
  return !!s && !s.ended && s.messages.length === 0 && !s.generating
})

/** 源码知识点第一次学习、带前置卡：老师开口前先读卡（ui.md 5.3，#90） */
const readingCards = computed(() => notStarted.value && !started.value && session.value!.prerequisites.length > 0)

onMounted(async () => {
  await refresh()
  // 刚开始：请老师说第一句。要先读前置卡的，等点了“读完了，开始学”再请
  if (notStarted.value && !readingCards.value) {
    await reply()
  }
})

async function startStudy() {
  if (!readingCards.value) {
    return
  }
  started.value = true
  await reply()
}

// 读卡时输入框还没出现，Ctrl Enter 就是“读完了，开始学”
useShortcut('Mod+Enter', () => {
  if (readingCards.value) {
    void startStudy()
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
        <span class="skip-hint">跳过讲解，讲完直接评分；没讲全的地方，{{ session.teacherName }}会点出来</span>
      </div>
    </section>

    <!-- 前置卡：先读再开始；开始以后折叠在这里，想回头看可以展开 -->
    <section v-if="readingCards" class="cards">
      <h2>学之前先看这几张卡</h2>
      <p class="cards-hint">都是这道题要用到的基础概念，读完再开始，对话里没看懂的随时可以问</p>
      <div v-for="card in session.prerequisites" :key="card.concept" class="card">
        <p class="card-concept">{{ card.concept }}</p>
        <p class="card-text">{{ card.explanation }}</p>
      </div>
      <el-button type="primary" class="start" @click="startStudy">
        读完了，开始学<kbd>{{ modKeyLabel }} Enter</kbd>
      </el-button>
    </section>
    <details v-else-if="session.prerequisites.length > 0 && !session.ended" class="cards-fold">
      <summary>前置卡（{{ session.prerequisites.length }} 张）</summary>
      <div v-for="card in session.prerequisites" :key="card.concept" class="card">
        <p class="card-concept">{{ card.concept }}</p>
        <p class="card-text">{{ card.explanation }}</p>
      </div>
    </details>

    <FinishPanel v-if="session.ended" :session="session" />
    <template v-else-if="!readingCards">
      <ChatThread
        :messages="messages"
        :streaming-text="streamingText"
        :collapsed="collapsed"
        :collapsed-text="`前面的 ${rounds} 轮对话已折叠`"
        :teacher-name="session.teacherName"
      />
      <div v-if="error" class="chat-error" role="alert">
        <span>{{ error.message }}</span>
        <el-button v-if="error.retryable" text type="primary" @click="reply">重试</el-button>
      </div>
      <div class="input-dock">
        <ChatInput
          :label="restating ? '完整作答：不看上面，把整个问题讲一遍' : '你的回答'"
          :busy="busy"
          :teacher-name="session.teacherName"
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

.cards,
.cards-fold {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.cards h2 {
  font-size: 20px;
}

.cards-hint {
  color: var(--wg-text-muted);
  font-size: 14px;
}

.card {
  padding: 14px 16px;
  border-radius: var(--wg-radius-card);
  background: var(--wg-surface);
}

.card-concept {
  font-weight: 700;
}

.card-text {
  margin-top: 4px;
  font-size: 15px;
  line-height: 1.7;
}

.start {
  align-self: flex-start;
  height: 48px;
  padding: 0 22px;
  font-size: 16px;
}

.start kbd {
  margin-left: 12px;
}

.cards-fold summary {
  color: var(--wg-text-muted);
  font-size: 14px;
  cursor: pointer;
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
