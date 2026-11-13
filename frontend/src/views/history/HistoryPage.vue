<script setup lang="ts">
// 回顾一次学习 /history/:id（ui.md 5.11，#82）：只读。参考答法最前（#111），然后是评分和 AI 评价，后面是当时的对话和接着问。
// 只用只读的接口：打开页面不调用模型，也不会新建接着问的会话
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import { fetchSavedRatingSuggestion, fetchSession, type RatingSuggestionView, type SessionView } from '@/api/sessions'
import PageHeader from '@/components/PageHeader.vue'
import { useShortcut } from '@/shortcuts/useShortcut'
import { formatMonthDay } from '@/utils/date'
import { ratingColors, ratingLabels } from '@/utils/labels'
import ChatThread from '@/views/study/ChatThread.vue'
import ReferenceAnswer from './ReferenceAnswer.vue'

const props = defineProps<{ id: string }>()
const router = useRouter()

const session = ref<SessionView | null>(null)
const suggestion = ref<RatingSuggestionView | null>(null)
const followUp = ref<SessionView | null>(null)
const loadError = ref<string | null>(null)

async function load() {
  loadError.value = null
  try {
    const id = Number(props.id)
    const [study, saved] = await Promise.all([fetchSession(id), fetchSavedRatingSuggestion(id)])
    if (study.type !== 'STUDY') {
      loadError.value = '这不是一次学习记录'
      return
    }
    followUp.value = study.followUpSessionId === null ? null : await fetchSession(study.followUpSessionId)
    suggestion.value = saved
    session.value = study
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '这次学习没有加载出来，请重试'
  }
}

onMounted(load)

/** 进入过接着问但没有提问时，不显示这一块 */
const followUpMessages = computed(() => followUp.value?.messages ?? [])

function back() {
  // 直接打开这个地址时没有上一页，回到今天
  if (window.history.state?.back) {
    router.back()
  } else {
    void router.push('/')
  }
}

useShortcut('Escape', back)
</script>

<template>
  <PageHeader>
    <template #start>
      <button type="button" class="back" @click="back">← 返回<kbd>Esc</kbd></button>
    </template>
  </PageHeader>
  <div class="wg-page-body">
    <section v-if="loadError" class="load-error" role="alert">
      <p>{{ loadError }}</p>
      <el-button @click="load">重试</el-button>
    </section>
    <el-skeleton v-else-if="!session" :rows="8" animated aria-busy="true" />
    <article v-else class="history">
      <h1>{{ session.kpTitle ?? '这个知识点已经不在了' }}</h1>

      <!-- 参考答法放在最前面（ui.md 5.11，#111）；没有找到时不显示，答法仍在下面的对话里 -->
      <section v-if="session.referenceAnswer" class="answer">
        <h2>面试参考答法</h2>
        <ReferenceAnswer :session-id="session.id" :answer="session.referenceAnswer" :show-link="false" />
      </section>

      <section class="evaluation">
        <div class="mine">
          <p v-if="session.rating">
            <span class="dot" :style="{ background: ratingColors[session.rating.rating] }" />
            你的评分：{{ ratingLabels[session.rating.rating] }}，安排在 {{ formatMonthDay(session.rating.date) }} 复习
          </p>
          <p v-else>这次学习还没有评分</p>
          <RouterLink v-if="session.ratingEditable" :to="`/session/${session.id}`">修改评分</RouterLink>
        </div>
        <div v-if="suggestion" class="suggestion">
          <p>
            <strong>AI 建议：{{ ratingLabels[suggestion.rating] }}。</strong>{{ suggestion.analysis }}
          </p>
          <p v-if="suggestion.weakPoints.length > 0" class="weak">没掌握的：{{ suggestion.weakPoints.join('；') }}。</p>
        </div>
        <p v-else class="weak">这次没有 AI 评价</p>
      </section>

      <section class="thread">
        <h2>当时的对话</h2>
        <ChatThread
          v-if="session.messages.length > 0"
          :messages="session.messages"
          :teacher-name="session.teacherName"
        />
        <p v-else class="weak">这次没有留下对话</p>
      </section>

      <section v-if="followUpMessages.length > 0" class="thread">
        <h2>接着问</h2>
        <ChatThread :messages="followUpMessages" :teacher-name="session.teacherName" />
      </section>

      <!-- 回顾页本身只读，继续问在问答记录里（决策 043） -->
      <RouterLink v-if="session.ended" class="continue" :to="`/questions/${session.id}`">继续问</RouterLink>
    </article>
  </div>
</template>

<style scoped>
.back {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  border: none;
  background: none;
  color: var(--wg-text-muted);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

.back:hover {
  color: var(--wg-text);
}

.load-error {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
}

.history {
  display: flex;
  flex-direction: column;
  gap: 32px;
  max-width: 728px;
  margin: 0 auto;
}

h1 {
  font-family: var(--wg-font-serif);
  font-size: 28px;
  line-height: 1.4;
}

h2 {
  font-size: 18px;
}

.continue {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  font-size: 15px;
}

.evaluation {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 24px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

.mine {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 16px;
  font-size: 15px;
}

.mine p {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.suggestion {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px 18px;
  border-radius: var(--wg-radius-card);
  background: var(--wg-surface);
  font-size: 14px;
  line-height: 1.75;
}

.weak {
  color: var(--wg-text-muted);
  font-size: 14px;
}

.answer {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.thread {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
</style>
