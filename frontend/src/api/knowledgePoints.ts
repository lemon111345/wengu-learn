// 知识库：列表、详情（#50，决策 030）和对知识点的操作（#25，决策 029）
import { get, patch, post } from './http'
import type { KnowledgePointType, Rating } from './types'

/** 掌握程度，对应后端的 Mastery。学过的按 FSRS 稳定性分四档，另有待学、暂停、只收录 */
export type Mastery = 'SOLID' | 'FAMILIAR' | 'FAIR' | 'SHAKY' | 'NEW' | 'SUSPENDED' | 'RECORD_ONLY'

export type LibrarySort = 'ORDER' | 'MASTERY' | 'NEXT_REVIEW'
export type SortDirection = 'ASC' | 'DESC'

/** 下次复习：学习日和距今天几个学习日（1 是明天，0 或负数是已经到期） */
export interface NextReview {
  date: string
  days: number
}

export interface LibraryItem {
  id: number
  title: string
  type: KnowledgePointType
  mastery: Mastery
  /** 只有学过的才有 */
  nextReview: NextReview | null
}

export interface LibraryView {
  /** 全部知识点数，即“共 Y 个” */
  total: number
  /** 各掌握程度的数量，只随类型筛选变化 */
  counts: Record<Mastery, number>
  /** 两种筛选都生效、已排好序 */
  items: LibraryItem[]
}

export interface LibraryQuery {
  mastery: Mastery | null
  type: KnowledgePointType | null
  sort: LibrarySort
  direction: SortDirection
}

export interface StudyRecord {
  /** 学习日 */
  date: string
  firstReview: boolean
  rating: Rating
  ratingAi: Rating | null
  /** 来自被合并的知识点时为它的标题 */
  mergedFrom: string | null
  /** 那次学习的会话，点开回顾；没有时为 null */
  sessionId: number | null
}

export interface SourceRef {
  sourceId: number
  name: string
  /** 题号；去重时补关联的材料没有题号 */
  questionIds: number[]
}

export interface KnowledgePointDetail {
  id: number
  title: string
  type: KnowledgePointType
  status: 'ACTIVE' | 'SUSPENDED'
  mastery: Mastery
  nextReview: NextReview | null
  summary: string
  /** AI 写的要点原文，“恢复 AI 版本”用；不是从草稿来的为 null */
  aiSummary: string | null
  summaryEdited: boolean
  /** 最近的在前，包括并进来的知识点的 */
  reviews: StudyRecord[]
  sources: SourceRef[]
}

export function fetchLibrary(query: LibraryQuery) {
  const params = new URLSearchParams({ sort: query.sort, direction: query.direction })
  if (query.mastery) {
    params.set('mastery', query.mastery)
  }
  if (query.type) {
    params.set('type', query.type)
  }
  return get<LibraryView>(`/api/knowledge-points?${params}`)
}

export function fetchKnowledgePoint(id: number) {
  return get<KnowledgePointDetail>(`/api/knowledge-points/${id}`)
}

/** 编辑：只改传了的字段，没有改动时后端什么也不做 */
export function updateKnowledgePoint(
  id: number,
  changes: { title?: string; type?: KnowledgePointType; summary?: string },
) {
  return patch<void>(`/api/knowledge-points/${id}`, changes)
}

// 以下都是批量操作：有一个 id 不符合时整批不执行（决策 029）

export function suspendKnowledgePoints(ids: number[]) {
  return post<void>('/api/knowledge-points/suspend', { ids })
}

export function resumeKnowledgePoints(ids: number[]) {
  return post<void>('/api/knowledge-points/resume', { ids })
}

/** 软删除，可以撤销 */
export function deleteKnowledgePoints(ids: number[]) {
  return post<void>('/api/knowledge-points/delete', { ids })
}

export function restoreKnowledgePoints(ids: number[]) {
  return post<void>('/api/knowledge-points/restore', { ids })
}

/** 打乱待学的顺序，同一道题拆出的保持相邻；不能恢复 */
export function shufflePendingKnowledgePoints() {
  return post<{ count: number }>('/api/knowledge-points/shuffle-pending')
}

/** 合并，不能撤销 */
export function mergeKnowledgePoints(primaryId: number, mergedIds: number[]) {
  return post<void>('/api/knowledge-points/merge', { primaryId, mergedIds })
}
