import { get, patch } from './http'

export interface Settings {
  /** IANA 时区，如 Asia/Shanghai */
  timezone: string
  /** 新的一天从几点开始，0～23 */
  dayStartHour: number
  /** 每天新学几个，0～50 */
  dailyNewLimit: number
}

/** 每天新学的范围，和后端 UpdateSettingsRequest 的校验一致 */
export const DAILY_NEW_MIN = 0
export const DAILY_NEW_MAX = 50

export function fetchSettings() {
  return get<Settings>('/api/settings')
}

/** 只修改传了的字段，返回修改后的全部设置 */
export function updateSettings(changes: Partial<Settings>) {
  return patch<Settings>('/api/settings', changes)
}
