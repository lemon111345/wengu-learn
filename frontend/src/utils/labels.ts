// 后端枚举在界面上的名称和颜色
import type { Mastery } from '@/api/knowledgePoints'
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

/** 掌握程度，按筛选按钮的顺序（ui.md 5.9） */
export const masteryOrder: Mastery[] = ['SOLID', 'FAMILIAR', 'FAIR', 'SHAKY', 'NEW', 'SUSPENDED', 'RECORD_ONLY']

export const masteryLabels: Record<Mastery, string> = {
  SOLID: '牢固',
  FAMILIAR: '熟悉',
  FAIR: '一般',
  SHAKY: '生疏',
  NEW: '待学',
  SUSPENDED: '暂停',
  RECORD_ONLY: '只收录',
}

/**
 * 掌握程度的色点：和评分共用一条色阶（ui.md 2.1）。
 * fill 为实心圆的颜色；ring 为空心圆的描边颜色（暂停、只收录），两者只有一个
 */
export const masteryDots: Record<Mastery, { fill?: string; ring?: string }> = {
  SOLID: { fill: 'var(--wg-level-4)' },
  FAMILIAR: { fill: 'var(--wg-level-3)' },
  FAIR: { fill: 'var(--wg-level-2)' },
  SHAKY: { fill: 'var(--wg-level-1)' },
  NEW: { fill: 'var(--wg-level-new)' },
  SUSPENDED: { ring: 'var(--wg-level-paused)' },
  RECORD_ONLY: { ring: 'var(--wg-level-new)' },
}
