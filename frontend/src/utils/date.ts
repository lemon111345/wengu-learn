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
