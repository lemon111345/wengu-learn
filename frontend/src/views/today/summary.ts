// 概况面板的文字和“下一个”，只依赖计划数据，不碰界面，规则见 ui.md 5.2
import type { PlanItem, TodayPlan } from '@/api/plan'

export interface NextItem extends PlanItem {
  /** 是否为新学，否则是复习 */
  isNew: boolean
}

/** 下一个要学的：先复习后新学，取第一个；今天的都学完了时为 null */
export function nextItem(plan: TodayPlan): NextItem | null {
  const review = plan.reviews[0]
  if (review) {
    return { ...review, isNew: false }
  }
  const fresh = plan.newItems[0]
  return fresh ? { ...fresh, isNew: true } : null
}

/** 大标题：还剩多少；都学完了、或者今天本来就没有要学的 */
export function overviewTitle(plan: TodayPlan): string {
  const reviews = plan.reviews.length
  const fresh = plan.newItems.length
  if (reviews > 0 && fresh > 0) {
    return `还剩 ${reviews} 个复习、${fresh} 个新知识`
  }
  if (reviews > 0) {
    return `还剩 ${reviews} 个复习`
  }
  if (fresh > 0) {
    return `还剩 ${fresh} 个新知识`
  }
  return plan.completed.length > 0 ? '今天的都学完了' : '今天没有要学的'
}

/** 说明行：还有要学的时写已完成多少和今天学了多久；都学完了时写今天学了多久和明天 */
export function overviewDescription(plan: TodayPlan): string {
  const remaining = plan.reviews.length + plan.newItems.length
  const minutes = plan.studiedMinutesToday > 0 ? `今天已学 ${plan.studiedMinutesToday} 分钟` : ''
  if (remaining === 0) {
    const tomorrow = plan.upcomingReviews[0]?.count ?? 0
    const next = tomorrow > 0 ? `明天有 ${tomorrow} 个要复习` : '明天没有要复习的'
    return minutes ? `${minutes}，${next}` : next
  }
  const doneReviews = plan.completed.filter((c) => !c.firstReview).length
  const doneNew = plan.completed.length - doneReviews
  const done = [
    doneReviews > 0 ? `复习 ${doneReviews} 个已完成` : '',
    doneNew > 0 ? `新学 ${doneNew} 个已完成` : '',
    minutes,
  ].filter((s) => s !== '')
  return done.length > 0 ? done.join('，') : `今天共 ${remaining} 个，先复习后学新的`
}
