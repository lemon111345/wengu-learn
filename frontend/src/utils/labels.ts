// 后端枚举在界面上的名称和颜色
import type { KnowledgePointType, Rating } from '@/api/types'

export const typeLabels: Record<KnowledgePointType, string> = {
  CONCEPT: '原子概念',
  COMPARISON: '对比',
  MECHANISM: '原理机制',
  ENUMERATION: '列举',
  TROUBLESHOOTING: '排查实战',
  SYSTEM_DESIGN: '系统设计',
  EXPERIENCE: '个人经历',
}

export const ratingLabels: Record<Rating, string> = {
  AGAIN: '忘了',
  HARD: '困难',
  GOOD: '良好',
  EASY: '简单',
}

/** 评分的色点颜色：和掌握程度共用一条色阶，只用于色点，不用作文字颜色（ui.md 2.1） */
export const ratingColors: Record<Rating, string> = {
  AGAIN: 'var(--wg-level-1)',
  HARD: 'var(--wg-level-2)',
  GOOD: 'var(--wg-level-3)',
  EASY: 'var(--wg-level-4)',
}
