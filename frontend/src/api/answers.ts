import { get } from './http'
import type { Rating } from './types'

/** 答法速查的一条，对应后端的 AnswerSummary（#112） */
export interface AnswerSummary {
  kpId: number
  kpTitle: string
  /** 最近一次已结束的学习会话，“看完整对话”打开它 */
  sessionId: number
  /** 收尾里没有找到时为 null（决策 047） */
  referenceAnswer: string | null
  /** 学完还没评分时为 null */
  rating: Rating | null
  /** 这次学习结束的学习日 */
  studiedDate: string
  /** 知识点今天要复习、还没复习：默认折叠 */
  dueToday: boolean
}

/** 学过的知识点各一条，最近学的在前 */
export function fetchAnswers() {
  return get<AnswerSummary[]>('/api/answers')
}
