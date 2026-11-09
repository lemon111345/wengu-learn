import { get, post } from './http'

/** 识别或整理出的一道题 */
export interface SourceQuestion {
  /** 题号，从 1 开始，按原文顺序 */
  id: number
  text: string
  /** 追问，没有时为空 */
  followUps: string[]
}

/**
 * 资料类型（#26）：
 * - QUESTION 题目清单：从原文中识别出题目（逐字保留）
 * - DOCUMENT 讲解资料：笔记、文章等没有现成题目的，根据内容整理出题目
 */
export type SourceType = 'QUESTION' | 'DOCUMENT'

export interface ImportedSource {
  id: number
  name: string
  type: SourceType
  questions: SourceQuestion[]
}

/** 上传文件的大小上限，和后端一致 */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024

/** 上传时可以选的文件类型 */
export const UPLOAD_ACCEPT = '.pdf,.docx,.md,.markdown,.txt'

/** 导入粘贴的资料：同步调用模型，只有题目时几秒到几十秒，10 万字带答案的资料最长约 95 秒 */
export function importSource(name: string, content: string, type: SourceType) {
  return post<ImportedSource>('/api/sources', { name, content, type })
}

/** 上传文件导入：后端提取文字后和粘贴的资料一样处理。名称为空时后端用文件名 */
export function uploadSource(file: File, name: string, type: SourceType) {
  const form = new FormData()
  form.append('file', file)
  form.append('type', type)
  if (name.trim() !== '') {
    form.append('name', name.trim())
  }
  return post<ImportedSource>('/api/sources/upload', form)
}

/** 截图识别的限制，和后端 ImageRecognitionService 一致（决策 044） */
export const MAX_IMAGES = 5
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024
export const MAX_IMAGE_TOTAL_BYTES = 10 * 1024 * 1024
export const IMAGE_ACCEPT = 'image/png,image/jpeg,image/webp'

/** 截图识别的进度和结果 */
export interface ImageRecognitionView {
  status: 'RUNNING' | 'DONE' | 'FAILED'
  /** 识别出的文字，只有 DONE 时有 */
  text: string | null
  /** 给用户看的失败原因，只有 FAILED 时有 */
  message: string | null
}

/** 提交截图，按顺序识别：马上返回任务 id，用 fetchImageRecognition 查结果 */
export function startImageRecognition(files: File[]) {
  const form = new FormData()
  files.forEach((file) => form.append('files', file))
  return post<{ taskId: string }>('/api/sources/image-recognitions', form)
}

export function fetchImageRecognition(taskId: string) {
  return get<ImageRecognitionView>(`/api/sources/image-recognitions/${taskId}`)
}
