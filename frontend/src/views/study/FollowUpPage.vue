<script setup lang="ts">
// 接着问 /session/:id/follow-up（ui.md 5.5），id 是原来的学习会话。
// 学生先问，老师只回答；离开只有两种方式：底部“返回评分页”和左上角“今天”
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import { fetchSession, startFollowUp, type SessionView } from '@/api/sessions'
import { useShortcut } from '@/shortcuts/useShortcut'
import { formatMonthDay } from '@/utils/date'
import { ratingColors, ratingLabels } from '@/utils/labels'
import ChatInput from './ChatInput.vue'
import ChatThread from './ChatThread.vue'
import { useAutoScroll, useChat } from './useChat'

const props = defineProps<{ id: string }>()
const router = useRouter()

/** 原来的学习会话：顶部卡片的标题、已评的分、展开刚才的对话 */
const study = ref<SessionView | null>(null)
const followUpId = ref(0)
const startError = ref<string | null>(null)
const showStudy = ref(false)

const { session, messages, streamingText, busy, error, refresh, reply, send } = useChat(() => followUpId.value)

onMounted(async () => {
  try {
    const [parent, followUp] = await Promise.all([fetchSession(Number(props.id)), startFollowUp(Number(props.id))])
    study.value = parent
    followUpId.value = followUp.id
    await refresh()
  } catch (e) {
    startError.value = e instanceof ApiError ? e.message : '没有进入接着问，请重试'
  }
})

function back() {
  void router.push(`/session/${props.id}`)
}

// Esc 返回评分页，焦点在输入框里时也生效
useShortcut('Escape', back, { allowInInput: true })
useAutoScroll(() => [messages.value.length, streamingText.value])
</script>

<template>
  <div v-if="startError" class="start-error" role="alert">
    <p>{{ startError }}</p>
    <el-button @click="back">返回评分页</el-button>
  </div>
  <el-skeleton v-else-if="!session || !study" :rows="6" animated />
  <div v-else class="follow-up">
    <section class="study-card">
      <div class="card-head">
        <div class="card-title">
          <span class="title">{{ study.kpTitle }}</span>
          <span v-if="study.rating" class="rated">
            <span class="dot" :style="{ background: ratingColors[study.rating.rating] }" />
            已评分：{{ ratingLabels[study.rating.rating] }}，{{ formatMonthDay(study.rating.date) }}再见
          </span>
          <span v-else class="rated">还没评分</span>
        </div>
        <button type="button" class="toggle" :aria-expanded="showStudy" @click="showStudy = !showStudy">
          {{ showStudy ? '收起刚才的对话' : '展开刚才的对话' }}
        </button>
      </div>
      <!-- 原地展开，不离开页面；只显示最后几条（完整作答和收尾点评），前面的可以再展开 -->
      <ChatThread
        v-if="showStudy"
        class="study-thread"
        :messages="study.messages"
        :collapsed="Math.max(0, study.messages.length - 3)"
        :collapsed-text="`前面的 ${Math.max(0, study.messages.length - 3)} 条对话已折叠`"
      />
    </section>

    <section class="intro">
      <h1>接着问</h1>
      <p>
        想问什么都可以，问到弄懂为止，不影响评分。老师只回答，不出题。
      </p>
    </section>

    <!-- 学生在前、老师在后 -->
    <ChatThread :messages="messages" :streaming-text="streamingText" />
    <div v-if="error" class="chat-error" role="alert">
      <span>{{ error.message }}</span>
      <el-button v-if="error.retryable" text type="primary" @click="reply">重试</el-button>
    </div>
    <p v-if="session.ended" class="limit">这次聊得比较多了，可以先回去，把聊到的新知识加入复习，明天接着学。</p>

    <div class="input-dock">
      <ChatInput
        label="继续问"
        placeholder="比如：ConcurrentHashMap 扩容时是怎么做的？"
        :busy="busy"
        :disabled="session.ended"
        @send="send"
      >
        <template #left>
          <el-button class="back" @click="back">← 返回评分页<kbd>Esc</kbd></el-button>
        </template>
      </ChatInput>
    </div>
  </div>
</template>

<style scoped>
.follow-up {
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.study-card {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 18px 22px;
  border-radius: var(--wg-radius-card);
  background: var(--wg-surface);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 16px;
}

.card-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.title {
  font-family: var(--wg-font-serif);
  font-size: 19px;
  font-weight: 700;
}

.rated {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--wg-text-muted);
  font-size: 13px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

/* 卡片本身是灰底，展开的对话里学生的话换成白底，才看得出来 */
.study-thread :deep(.student) {
  background: var(--wg-bg);
}

.toggle {
  min-height: 44px;
  border: none;
  background: none;
  color: var(--wg-primary);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

.intro {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

h1 {
  font-size: 26px;
}

.intro p,
.limit {
  color: var(--wg-text-muted);
  font-size: 14px;
  line-height: 1.75;
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

.input-dock {
  position: sticky;
  bottom: 0;
  margin: 0 -16px -32px;
  padding: 16px 16px 20px;
  border-top: 1px solid var(--wg-border);
  background: var(--wg-bg);
}

.back kbd {
  margin-left: 10px;
}

.start-error {
  display: flex;
  align-items: center;
  gap: 16px;
}
</style>
