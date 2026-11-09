import { get, patch } from './http'

export interface Settings {
  /** IANA 时区，如 Asia/Shanghai */
  timezone: string
  /** 新的一天从几点开始，0～23 */
  dayStartHour: number
  /** 每天学几个，含复习，0～50。字段名没有改（决策 016 的修订） */
  dailyNewLimit: number
  /** 老师的称呼，只改变显示名称和模型的自称（决策 042） */
  teacherName: string
}

/** 称呼的长度上限，和后端 UserSettingsService 的校验一致 */
export const TEACHER_NAME_MAX = 10

/** 每天学几个的范围，和后端 UpdateSettingsRequest 的校验一致 */
export const DAILY_NEW_MIN = 0
export const DAILY_NEW_MAX = 50

export function fetchSettings() {
  return get<Settings>('/api/settings')
}

/** 只修改传了的字段，返回修改后的全部设置 */
export function updateSettings(changes: Partial<Settings>) {
  return patch<Settings>('/api/settings', changes)
}
