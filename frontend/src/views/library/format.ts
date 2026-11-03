// 知识库中的文案：下次复习、学习记录、来源
import type { LibraryItem, SourceRef, StudyRecord } from '@/api/knowledgePoints'
import { formatMonthDay } from '@/utils/date'
import { ratingLabels } from '@/utils/labels'

/**
 * “下次复习”一列和详情顶部的文字。warn 为 true 时用 --wg-warn：明天到期（ui.md 5.9），
 * 以及已经到期、在今天计划里的
 */
export function nextReviewText(item: Pick<LibraryItem, 'mastery' | 'nextReview'>): { text: string; warn: boolean } {
  const next = item.nextReview
  if (next) {
    if (next.days <= 0) {
      return { text: '今天', warn: true }
    }
    return next.days === 1 ? { text: '明天', warn: true } : { text: formatMonthDay(next.date), warn: false }
  }
  switch (item.mastery) {
    case 'NEW':
      return { text: '等待学习', warn: false }
    case 'SUSPENDED':
      return { text: '已暂停', warn: false }
    default:
      // 只收录：M1 中这两类暂时不能学（ui.md 第 8 节）
      return { text: '以后支持', warn: false }
  }
}

/** 如“新学，良好（AI 建议：良好）” */
export function studyRecordText(record: StudyRecord): string {
  const text = `${record.firstReview ? '新学' : '复习'}，${ratingLabels[record.rating]}`
  return record.ratingAi ? `${text}（AI 建议：${ratingLabels[record.ratingAi]}）` : text
}

/** 如“Java 后端面试题，第 1、3 题”；没有题号时只有材料名 */
export function sourceText(source: SourceRef): string {
  return source.questionIds.length > 0 ? `${source.name}，第 ${source.questionIds.join('、')} 题` : source.name
}
