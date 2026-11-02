import { get } from './http'

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
