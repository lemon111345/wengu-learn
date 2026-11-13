import { get, post } from './http'
import { postStream, type StreamHandlers } from './sse'
import type { Rating } from './types'

export type SessionType = 'STUDY' | 'FOLLOW_UP' | 'QUESTION'

/** 老师回复中的标记：RESTATE 要求完整作答，END 学习结束 */
export type Marker = 'RESTATE' | 'END'

export interface MessageView {
  id: number
  role: 'USER' | 'ASSISTANT'
  /** 不含标记 */
  content: string
  marker: Marker | null
  createdAt: string
}

/** 一种评分对应的下次复习日期 */
export interface RatingOption {
  rating: Rating
  /** 下次复习的学习日，如 2026-11-07 */
  date: string
  /** 距今天几个学习日 */
  days: number
}

/** 学习会话和全部消息，对应后端的 SessionView（决策 025） */
export interface SessionView {
  id: number
  type: SessionType
  kpId: number | null
  /** 接着问的会话指向原来的学习会话 */
  parentSessionId: number | null
  kpTitle: string | null
  /** 学习对话已经结束（学习结束或回答到上限）；接着问到达提问上限 */
  ended: boolean
  skippedExplanation: boolean
  /** 老师还在后台生成回复（如刷新了页面），稍后再取 */
  generating: boolean
  /** 有效时长（秒） */
  activeSeconds: number
  /** 已经评过分时的评分和下次复习日期 */
  rating: RatingOption | null
  /** 已经评过分时，这次评分还能不能修改（决策 039） */
  ratingEditable: boolean
  /** 学习会话的接着问会话，没有接着问时为 null；回顾时用（#82） */
  followUpSessionId: number | null
  messages: MessageView[]
  /** 用户现在设置的老师称呼（决策 042） */
  teacherName: string
  /** 学习结束后从收尾中切出的面试参考答法；没结束、不是学习会话、收尾里没有找到时为 null（决策 047） */
  referenceAnswer: string | null
}

/** AI 评分建议（决策 026） */
export interface RatingSuggestionView {
  rating: Rating
  analysis: string
  /** 没掌握的内容，下次复习会重点问 */
  weakPoints: string[]
  outOfScopeGaps: string[]
}

/** 开始学习一个知识点；最近的会话还没走完（没结束、或者没评分）时返回那个会话 */
export function startSession(kpId: number) {
  return post<SessionView>('/api/sessions', { kpId })
}

export function fetchSession(id: number) {
  return get<SessionView>(`/api/sessions/${id}`)
}

/** 进入接着问：id 是学习会话 */
export function startFollowUp(studySessionId: number) {
  return post<SessionView>(`/api/sessions/${studySessionId}/follow-up`)
}

/** 老师的下一条回复：刚开始时的第一句，或者出错后重试 */
export function streamReply(id: number, handlers: StreamHandlers, signal?: AbortSignal) {
  return postStream(`/api/sessions/${id}/reply`, undefined, handlers, signal)
}

export function streamMessage(id: number, content: string, handlers: StreamHandlers, signal?: AbortSignal) {
  return postStream(`/api/sessions/${id}/messages`, { content }, handlers, signal)
}

/** “这个我会，直接完整作答” */
export function streamSkipExplanation(id: number, handlers: StreamHandlers, signal?: AbortSignal) {
  return postStream(`/api/sessions/${id}/skip-explanation`, undefined, handlers, signal)
}

/** AI 评分建议：第一次调用要等模型十几到二十几秒 */
export function fetchRatingSuggestion(id: number) {
  return post<RatingSuggestionView>(`/api/sessions/${id}/rating-suggestion`)
}

/** 已经生成的 AI 评分建议，没有时为 null。只读，不调用模型，回顾时用（#82） */
export function fetchSavedRatingSuggestion(id: number) {
  return get<RatingSuggestionView | null>(`/api/sessions/${id}/rating-suggestion`)
}

/** 四种评分的下次复习日期，按忘了、困难、良好、简单排列 */
export function fetchRatingPreview(id: number) {
  return get<RatingOption[]>(`/api/sessions/${id}/rating-preview`)
}

export function rate(id: number, rating: Rating) {
  return post<RatingOption>(`/api/sessions/${id}/rating`, { rating })
}
