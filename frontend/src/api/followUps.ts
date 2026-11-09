import { get } from './http'

/** 问答记录列表的一行，对应后端的 FollowUpSummary（决策 043） */
export interface FollowUpSummary {
  /** 原来的学习会话，问答记录的地址用它 */
  studySessionId: number
  followUpSessionId: number
  /** 已合并或删除的知识点也有标题 */
  kpTitle: string | null
  lastQuestion: string
  questionCount: number
  /** 最后一次活动的学习日 */
  lastActiveDate: string
  canContinue: boolean
  /** 知识点今天要复习、还没复习 */
  dueToday: boolean
}

/** 一次学习的接着问状态，对应后端的 FollowUpStatus */
export interface FollowUpStatus {
  studySessionId: number
  /** 还没有接着问时为 null，第一次发送时才创建 */
  followUpSessionId: number | null
  kpTitle: string | null
  canContinue: boolean
  /** 不能继续问的原因，可以直接显示；能继续问时为 null */
  readOnlyReason: string | null
  dueToday: boolean
  teacherName: string
}

/** 有提问的接着问，最近活动的在前；keyword 匹配知识点标题、问题和回答 */
export function fetchFollowUps(keyword: string) {
  return get<FollowUpSummary[]>(keyword ? `/api/follow-ups?keyword=${encodeURIComponent(keyword)}` : '/api/follow-ups')
}

/** 只查不建：打开一条对话时用 */
export function fetchFollowUpStatus(studySessionId: number) {
  return get<FollowUpStatus>(`/api/sessions/${studySessionId}/follow-up-status`)
}
