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
  dailyNewLimit: number
  newLearnedToday: number
  /** 到期复习，按学习顺序，不含今天已完成的 */
  reviews: PlanItem[]
  /** 新知识，按学习顺序 */
  newItems: PlanItem[]
  /** 今天已完成的，按完成时间排列 */
  completed: CompletedItem[]
  /** 从明天起 7 个学习日每天要复习的数量 */
  upcomingReviews: UpcomingDay[]
  /** 知识点总数；为 0 时显示空状态 */
  knowledgePointCount: number
}

export function fetchTodayPlan() {
  return get<TodayPlan>('/api/plan/today')
}
