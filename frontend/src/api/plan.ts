import { get } from './http'
import type { KnowledgePointType, Rating } from './types'

export interface PlanItem {
  kpId: number
  title: string
  type: KnowledgePointType
}

/** 今天已完成的一个知识点 */
export interface CompletedItem extends PlanItem {
  /** 今天的评分；同一知识点评了多次时是最后一次 */
  rating: Rating
  /** 是否为新学，否则是复习 */
  firstReview: boolean
  /** 这次评分对应的学习会话，点开回顾；没有时为 null */
  sessionId: number | null
}

export interface UpcomingDay {
  /** 学习日，如 2026-11-03 */
  date: string
  count: number
}

/** 今天的计划，对应后端的 TodayPlan（决策 016、024） */
export interface TodayPlan {
  /** 学习日，如 2026-11-02。页面上的日期一律用它，不用浏览器的当前日期 */
  date: string
  /** 每天学几个，含复习 */
  dailyNewLimit: number
  newLearnedToday: number
  /** 到期复习，按学习顺序，不含今天已完成的；全部列出，可能超过每天学几个 */
  reviews: PlanItem[]
  /** 新知识，按学习顺序，只补足每天学几个剩下的名额 */
  newItems: PlanItem[]
  /** 今天已完成的，按完成时间排列 */
  completed: CompletedItem[]
  /** 从明天起 7 个学习日每天要复习的数量 */
  upcomingReviews: UpcomingDay[]
  /** 知识点总数；为 0 时显示空状态 */
  knowledgePointCount: number
  /** 今天的有效学习时长（分钟） */
  studiedMinutesToday: number
  /** 界面不再使用（#85），接口约定不删除字段，所以保留 */
  daysToFinish: number | null
}

export function fetchTodayPlan() {
  return get<TodayPlan>('/api/plan/today')
}
