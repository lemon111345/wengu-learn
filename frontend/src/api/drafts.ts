import { get, post } from './http'
import type { KnowledgePointType } from './types'

export type DraftStatus = 'RUNNING' | 'READY' | 'FAILED' | 'CONFIRMED'

/** 草稿列表中的一行，对应后端的 DraftSummary（决策 024） */
export interface DraftSummary {
  id: number
  sourceId: number | null
  /** 材料名称，材料查不到时为 null */
  sourceName: string | null
  status: DraftStatus
  /** 成功批次中的条目数 */
  itemCount: number
  createdAt: string
}

/** 草稿列表：未确认的全部返回，已确认的只返回最近 10 份，新的在前 */
export function fetchDrafts() {
  return get<DraftSummary[]>('/api/drafts')
}

export type BatchStatus = 'PENDING' | 'RUNNING' | 'READY' | 'FAILED'

export interface BatchView {
  /** 第几批，从 1 开始 */
  no: number
  status: BatchStatus
  /** 这一批的题号 */
  questionIds: number[]
  /** 失败时给用户看的原因，如“AI 模型拒绝了请求……”；原因不明时为 null（决策 032） */
  failureReason: string | null
}

export interface DraftItemView {
  id: number
  /** 学习顺序，从 1 开始 */
  order: number
  title: string
  summary: string
  type: KnowledgePointType
  sourceQuestionIds: number[]
  /** 确认时的处理；还没确认时为 null */
  decision: 'ACCEPTED' | 'REJECTED' | null
}

/** 草稿详情，对应后端的 DraftView（决策 012～015） */
export interface DraftView {
  id: number
  sourceId: number | null
  sourceName: string | null
  status: DraftStatus
  batches: BatchView[]
  /** 成功批次中的知识点，按学习顺序 */
  items: DraftItemView[]
  /** 被已有知识点覆盖的题 */
  covered: { questionId: number; titles: string[] }[]
  /** 没拆出知识点的题（#22） */
  uncovered: { questionId: number; text: string }[]
}

/** 发起抽取；questionIds 不传则全部。后台执行，立即返回抽取中的草稿 */
export function createDraft(sourceId: number, questionIds?: number[]) {
  return post<DraftView>('/api/drafts', { sourceId, questionIds })
}

export function fetchDraft(id: number) {
  return get<DraftView>(`/api/drafts/${id}`)
}

/** 重新抽取失败的批次（一次重试全部失败的批次） */
export function retryDraft(id: number) {
  return post<DraftView>(`/api/drafts/${id}/retry`)
}

/** 确认草稿：取消勾选的条目不生成知识点 */
export function confirmDraft(id: number, rejectedItemIds: number[]) {
  return post<DraftView>(`/api/drafts/${id}/confirm`, { rejectedItemIds })
}
