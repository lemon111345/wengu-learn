// 学习缺口的处理结果（ui.md 5.4，决策 053）：把草稿的条目和 covered 对回到每一条缺口上
import type { DraftView } from '@/api/drafts'

export type GapOutcome =
  /** 没勾 */
  | { kind: 'skipped' }
  /** 还在整理，或者草稿没取到 */
  | { kind: 'pending' }
  /** 生成了知识点；一条缺口可能拆成几个，也可能和别的缺口合并成一个 */
  | { kind: 'added'; titles: string[] }
  /** 被已有知识点覆盖，没有新生成 */
  | { kind: 'existing'; titles: string[] }
  /** 两样都没有：模型认为它不够单独成为一个知识点 */
  | { kind: 'none' }

export interface GapResult {
  text: string
  picked: boolean
  outcome: GapOutcome
}

/**
 * 每条缺口的结果。后端把勾选的缺口按下标从小到大编成第 1、2、3 题（决策 053），
 * 所以第 k 个勾选的缺口就是第 k 题，用题号去对条目的 sourceQuestionIds 和 covered。
 *
 * @param picks 勾选的下标，从小到大；还没处理时为 null，这时全部按“没勾”算
 * @param draft 已经确认的草稿；还没确认（整理中、失败）或没取到时为 null，勾选的都是 pending
 */
export function gapResults(gaps: string[], picks: number[] | null, draft: DraftView | null): GapResult[] {
  return gaps.map((text, index) => {
    const position = picks?.indexOf(index) ?? -1
    if (position < 0) {
      return { text, picked: false, outcome: { kind: 'skipped' } }
    }
    if (!draft || draft.status !== 'CONFIRMED') {
      return { text, picked: true, outcome: { kind: 'pending' } }
    }
    const questionId = position + 1
    const added = draft.items.filter((item) => item.sourceQuestionIds.includes(questionId)).map((item) => item.title)
    if (added.length > 0) {
      return { text, picked: true, outcome: { kind: 'added', titles: added } }
    }
    const covered = draft.covered.find((c) => c.questionId === questionId)
    if (covered) {
      return { text, picked: true, outcome: { kind: 'existing', titles: covered.titles } }
    }
    return { text, picked: true, outcome: { kind: 'none' } }
  })
}
