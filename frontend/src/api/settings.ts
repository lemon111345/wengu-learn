import { patch } from './http'

export interface Settings {
  /** IANA 时区，如 Asia/Shanghai */
  timezone: string
  /** 新的一天从几点开始，0～23 */
  dayStartHour: number
  /** 每天新学几个，0～50 */
  dailyNewLimit: number
}

/** 只修改传了的字段，返回修改后的全部设置 */
export function updateSettings(changes: Partial<Settings>) {
  return patch<Settings>('/api/settings', changes)
}
