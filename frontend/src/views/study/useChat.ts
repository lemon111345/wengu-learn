/**
 * 学习对话和接着问共用的对话逻辑（决策 025、027）：读取会话、流式回复、出错与重试、刷新后接着等。
 *
 * 以后端存的会话为准：每次流式回复结束（完成、出错、中断）都重新取一次会话，拿到存库的消息、标记、会话是否结束；
 * 流式进行中只在界面上临时显示学生刚发的话和老师正在说的话。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ApiError } from '@/api/http'
import {
  fetchSession,
  streamMessage,
  streamReply,
  streamSkipExplanation,
  type MessageView,
  type SessionView,
} from '@/api/sessions'
import { StreamInterrupted, type StreamHandlers } from '@/api/sse'
import { setStudyMinutes } from '@/layouts/studyHeader'

/** 刷新时老师还在后台生成回复，每隔多久取一次会话 */
const POLL_INTERVAL = 2000

export interface ChatError {
  message: string
  /** 能不能点“重试”重新生成老师的回复 */
  retryable: boolean
}

export function useChat(sessionId: () => number) {
  const session = ref<SessionView | null>(null)
  /** 学生刚发、还没从后端取回的话 */
  const pendingUser = ref<string | null>(null)
  /** 老师正在说的话；为 null 表示没有在流式输出 */
  const streamingText = ref<string | null>(null)
  const error = ref<ChatError | null>(null)
  /** 第一次读取会话失败时的提示 */
  const loadError = ref<string | null>(null)

  let controller: AbortController | null = null
  let pollTimer: ReturnType<typeof setTimeout> | null = null

  /** 正在流式输出，或者老师在后台生成（刷新后接着等）：这时不能发消息 */
  const busy = computed(() => streamingText.value !== null || (session.value?.generating ?? false))

  /** 界面上显示的消息：存库的，加上学生刚发的 */
  const messages = computed<MessageView[]>(() => {
    const saved = session.value?.messages ?? []
    if (pendingUser.value === null) {
      return saved
    }
    return [...saved, { id: -1, role: 'USER', content: pendingUser.value, marker: null, createdAt: '' }]
  })

  /**
   * 最后一条是学生的话，又没有在生成：这条回复丢了（后端重启、生成中途失败），只能重试。
   * 后端这时不接受新消息，所以要给出重试按钮，否则对话就卡死了（#95）
   */
  function showRetryIfReplyLost() {
    const s = session.value
    if (s && !s.ended && !s.generating && s.messages.at(-1)?.role === 'USER' && !error.value?.retryable) {
      error.value = { message: '老师这条回复没有生成出来，可以点重试', retryable: true }
    }
  }

  async function refresh() {
    try {
      session.value = await fetchSession(sessionId())
      loadError.value = null
      setStudyMinutes(session.value.activeSeconds)
    } catch (e) {
      if (!session.value) {
        loadError.value = e instanceof ApiError ? e.message : '没有加载出来，请重试'
      }
      return
    }
    stopPolling()
    if (session.value.generating) {
      pollTimer = setTimeout(refresh, POLL_INTERVAL)
    } else {
      showRetryIfReplyLost()
    }
  }

  function stopPolling() {
    if (pollTimer !== null) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  /** 发起一次流式请求，结束后以后端的会话为准 */
  async function run(start: (handlers: StreamHandlers, signal: AbortSignal) => Promise<void>, userText?: string) {
    error.value = null
    pendingUser.value = userText ?? null
    streamingText.value = ''
    controller = new AbortController()
    const handlers: StreamHandlers = {
      onDelta: (text) => (streamingText.value = (streamingText.value ?? '') + text),
      onDone: () => {},
      onError: (message) => (error.value = { message, retryable: true }),
    }
    try {
      await start(handlers, controller.signal)
    } catch (e) {
      if (e instanceof StreamInterrupted) {
        // 后端照样把回复生成完、存进库（决策 025）：重新取会话时，要么已经有了，要么还在生成、接着等
        error.value = { message: '连接中断', retryable: true }
      } else {
        // 推送之前就被拒绝（如 409），学生的话也没有存下
        error.value = { message: e instanceof ApiError ? e.message : '发送失败，请重试', retryable: false }
      }
    } finally {
      controller = null
      await refresh()
      pendingUser.value = null
      streamingText.value = null
      // 回复其实已经存下、或者还在生成，就不用提示重试了
      const last = session.value?.messages.at(-1)
      if (error.value?.retryable && (session.value?.generating || last?.role === 'ASSISTANT')) {
        error.value = null
      }
    }
  }

  /** 老师的下一条回复：刚开始时的第一句，或者出错后重试 */
  function reply() {
    return run((h, signal) => streamReply(sessionId(), h, signal))
  }

  function send(content: string) {
    return run((h, signal) => streamMessage(sessionId(), content, h, signal), content)
  }

  function skipExplanation() {
    return run((h, signal) => streamSkipExplanation(sessionId(), h, signal), '这个我会，直接完整作答。')
  }

  onBeforeUnmount(() => {
    // 离开页面只断开连接，后端照样生成完、存进库
    controller?.abort()
    stopPolling()
    setStudyMinutes(null)
  })

  return { session, messages, streamingText, busy, error, loadError, refresh, reply, send, skipExplanation }
}

/** 新内容出现时，用户本来就在底部附近，就跟着滚到底部；在往上翻看的话不打扰 */
export function useAutoScroll(source: () => unknown) {
  watch(source, async () => {
    const el = document.scrollingElement
    if (!el || el.scrollHeight - el.scrollTop - el.clientHeight > 240) {
      return
    }
    await nextTick()
    el.scrollTop = el.scrollHeight
  })
}
