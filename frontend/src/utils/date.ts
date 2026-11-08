// 学习日的显示。学习日由后端按用户时区和 day_start_hour 算好，如 '2026-11-02'；
// 这里只做格式化，不用浏览器的“今天”（凌晨 1 点时浏览器已经是第二天，学习日还是前一天）

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** 按 UTC 解析，避免浏览器时区把日期挪到前一天或后一天 */
function parse(date: string): Date {
  const [year, month, day] = date.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day))
}

/** 如“周一” */
export function weekdayLabel(date: string): string {
  return WEEKDAYS[parse(date).getUTCDay()]
}

/** 如“11月2日 周一” */
export function formatStudyDate(date: string): string {
  const d = parse(date)
  return `${d.getUTCMonth() + 1}月${d.getUTCDate()}日 ${weekdayLabel(date)}`
}

/** 如“11月7日”，用于“11月7日再见” */
export function formatMonthDay(date: string): string {
  const d = parse(date)
  return `${d.getUTCMonth() + 1}月${d.getUTCDate()}日`
}

/** 某个时刻（UTC 的 ISO 字符串）距现在多久，如“刚刚”“10 分钟前”“3 小时前”“2 天前” */
export function timeAgo(instant: string, now = Date.now()): string {
  const minutes = Math.floor((now - new Date(instant).getTime()) / 60_000)
  if (minutes < 1) {
    return '刚刚'
  }
  if (minutes < 60) {
    return `${minutes} 分钟前`
  }
  const hours = Math.floor(minutes / 60)
  return hours < 24 ? `${hours} 小时前` : `${Math.floor(hours / 24)} 天前`
}

/** 距今天几个学习日的说法：1 天是“明天”，其他是“3 天后”；今天或已经过去（隔几天后改分时）都是“今天” */
export function daysLater(days: number): string {
  if (days <= 0) {
    return '今天'
  }
  return days === 1 ? '明天' : `${days} 天后`
}
