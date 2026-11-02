import { post } from './http'

/** 切分出的一道题 */
export interface SourceQuestion {
  /** 题号，从 1 开始，按原文顺序 */
  id: number
  text: string
  /** 追问，没有时为空 */
  followUps: string[]
}

export interface ImportedSource {
  id: number
  name: string
  questions: SourceQuestion[]
}

/** 导入面试题并切分：同步调用模型，只有题目时几秒到几十秒，10 万字带答案的资料最长约 95 秒 */
export function importQuestions(name: string, content: string) {
  return post<ImportedSource>('/api/sources', { name, content })
}
