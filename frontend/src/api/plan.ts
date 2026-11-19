import { get, post } from './http'
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
  /**
   * 新知识，依次是学到一半的、今天从知识库加入的、计划派发的（决策 052）。
   * 前两部分先占每天学几个的名额，比名额多时照样全部列出；派发的只补剩下的名额
   */
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
  /** 连续学习天数（决策 020）；今天还没完成时算到昨天为止 */
  currentStreak: number
  /** 本周的有效学习时长（分钟），从周一的学习日开始 */
  studiedMinutesThisWeek: number
}

export function fetchTodayPlan() {
  return get<TodayPlan>('/api/plan/today')
}

/** 加入今天的结果（#128，决策 052） */
export interface PickResult {
  /** 这次加入的 */
  added: number
  /** 本来就在今天的新学列表里的 */
  alreadyInToday: number
  /** 不是待学的，没有加入：学过的、暂停的、只收录的 */
  skipped: number
}

/** 把知识点加入今天：只加入其中待学的，只当天有效 */
export function pickToday(kpIds: number[]) {
  return post<PickResult>('/api/plan/today/picks', { kpIds })
}
