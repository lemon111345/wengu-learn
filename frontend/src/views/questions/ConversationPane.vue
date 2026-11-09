<script setup lang="ts">
// 问答记录的右栏（ui.md 5.12）：问题目录、对话和输入框，沿用接着问的会话和对话逻辑。
// 打开时只读状态，不建会话；没问过的学习记录在第一次发送时才创建（决策 043）
import { computed, nextTick, onMounted, ref } from 'vue'
import { ApiError } from '@/api/http'
import { fetchFollowUpStatus, type FollowUpStatus } from '@/api/followUps'
import { startFollowUp } from '@/api/sessions'
import ChatInput from '@/views/study/ChatInput.vue'
import ChatThread from '@/views/study/ChatThread.vue'
import { useAutoScroll, useChat } from '@/views/study/useChat'

const props = defineProps<{ studySessionId: number }>()
const emit = defineEmits<{ asked: [] }>()

const status = ref<FollowUpStatus | null>(null)
const statusError = ref<string | null>(null)
const startError = ref<string | null>(null)
/** 接着问的会话；还没有时为 0 */
const followUpId = ref(0)
/** 打开时折叠的条数。只在打开时算一次，之后发的新消息不折叠，也不会把已经展开的又折叠回去 */
const foldedCount = ref(0)

const { messages, streamingText, busy, error, loadError, refresh, reply, send, session } = useChat(() => followUpId.value)

async function load() {
  statusError.value = null
  try {
    const loaded = await fetchFollowUpStatus(props.studySessionId)
    followUpId.value = loaded.followUpSessionId ?? 0
    if (followUpId.value !== 0) {
      await refresh()
      foldedCount.value = loaded.dueToday ? (session.value?.messages.length ?? 0) : 0
    }
    status.value = loaded
  } catch (e) {
    statusError.value = e instanceof ApiError ? e.message : '这段对话没有加载出来，请重试'
  }
}

onMounted(load)

/** 问题目录：已经存下的学生消息，学生刚发、还没取回的（id 为负）不列 */
const questions = computed(() => messages.value.filter((m) => m.role === 'USER' && m.id > 0))

/** 点目录时先把折叠的展开，等页面更新后再跳过去 */
async function jump(messageId: number) {
  foldedCount.value = 0
  await nextTick()
  document.getElementById(`message-${messageId}`)?.scrollIntoView({ block: 'start' })
}

async function ask(content: string) {
  startError.value = null
  if (followUpId.value === 0) {
    try {
      followUpId.value = (await startFollowUp(props.studySessionId)).id
    } catch (e) {
      startError.value = e instanceof ApiError ? e.message : '发送失败，请重试'
      return
    }
  }
  await send(content)
  // 问满上限后输入框要换成说明；取不到时保留原来的状态，下次操作后端也会拒绝
  status.value = await fetchFollowUpStatus(props.studySessionId).catch(() => status.value)
  emit('asked')
}

useAutoScroll(() => [messages.value.length, streamingText.value])
</script>

<template>
  <section v-if="statusError || loadError" class="load-error" role="alert">
    <p>{{ statusError ?? loadError }}</p>
    <el-button @click="load">重试</el-button>
  </section>
  <el-skeleton v-else-if="!status" :rows="8" animated aria-busy="true" />
  <article v-else class="pane">
    <RouterLink class="all" to="/questions">← 全部对话</RouterLink>
    <h2>{{ status.kpTitle ?? '这个知识点已经不在了' }}</h2>

    <details v-if="questions.length >= 2" class="directory" open>
      <summary>问了 {{ questions.length }} 个问题</summary>
      <ol>
        <li v-for="q in questions" :key="q.id">
          <button type="button" @click="jump(q.id)">{{ q.content }}</button>
        </li>
      </ol>
    </details>

    <p v-if="foldedCount > 0" class="due-tip">今天要复习这个知识点，建议复习后再看</p>

    <ChatThread
      v-if="messages.length > 0 || streamingText !== null"
      :messages="messages"
      :streaming-text="streamingText"
      :collapsed="foldedCount"
      :collapsed-text="`已有的 ${foldedCount} 条对话已折叠`"
      :teacher-name="status.teacherName"
    />
    <p v-else-if="status.canContinue" class="empty">
      想问什么都可以，问到弄懂为止，不影响评分。{{ status.teacherName }}只回答，不出题。
    </p>

    <div v-if="startError" class="chat-error" role="alert">
      <span>{{ startError }}</span>
    </div>
    <div v-if="error" class="chat-error" role="alert">
      <span>{{ error.message }}</span>
      <el-button v-if="error.retryable" text type="primary" @click="reply">重试</el-button>
    </div>

    <div v-if="status.canContinue" class="input-dock">
      <ChatInput
        label="继续问"
        placeholder="比如：ConcurrentHashMap 扩容时是怎么做的？"
        :busy="busy"
        :teacher-name="status.teacherName"
        @send="ask"
      />
    </div>
    <p v-else class="read-only">{{ status.readOnlyReason }}</p>
  </article>
</template>

<style scoped>
.load-error {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
}

.pane {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* 只有窄屏分成两屏时才需要回到列表 */
.all {
  display: none;
  align-items: center;
  min-height: 44px;
  color: var(--wg-text-muted);
  font-size: 14px;
}

h2 {
  font-family: var(--wg-font-serif);
  font-size: 26px;
  line-height: 1.4;
}

.directory {
  padding: 14px 18px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-card);
}

.directory summary {
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
}

.directory ol {
  max-height: 220px;
  margin: 8px 0 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}

.directory button {
  display: block;
  width: 100%;
  min-height: 44px;
  overflow: hidden;
  border: none;
  background: none;
  color: var(--wg-text);
  font: inherit;
  font-size: 14px;
  text-align: left;
  white-space: nowrap;
  text-overflow: ellipsis;
  cursor: pointer;
}

.directory button:hover {
  color: var(--wg-primary);
}

.due-tip {
  padding: 10px 16px;
  border-radius: var(--wg-radius-card);
  background: var(--wg-warn-soft);
  font-size: 14px;
}

.empty,
.read-only {
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
  padding: 16px 0 20px;
  border-top: 1px solid var(--wg-border);
  background: var(--wg-bg);
}

@media (max-width: 900px) {
  .all {
    display: inline-flex;
  }
}
</style>
