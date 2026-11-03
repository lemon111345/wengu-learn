// 时区的显示名，如“中国标准时间（UTC+8）”。只用于显示和选择，日期的换算一律在后端（决策 034）

export interface TimezoneOption {
  /** IANA 时区，如 Asia/Shanghai，保存的就是它 */
  id: string
  /** 如“中国标准时间（UTC+8）” */
  label: string
  /** 现在相对 UTC 的分钟数，用于排序；有夏令时的地区会随日期变化 */
  offsetMinutes: number
}

/** 取 Intl 格式化结果中的时区名部分 */
function zonePart(id: string, style: 'long' | 'shortOffset', now: Date): string {
  const parts = new Intl.DateTimeFormat('zh-CN', { timeZone: id, timeZoneName: style }).formatToParts(now)
  return parts.find((p) => p.type === 'timeZoneName')?.value ?? ''
}

/** “GMT+8”“GMT-3:30”“GMT” → 分钟数 */
function parseOffset(text: string): number {
  const match = /([+-])(\d{1,2})(?::(\d{2}))?/.exec(text)
  if (!match) {
    return 0
  }
  const minutes = Number(match[2]) * 60 + Number(match[3] ?? 0)
  return match[1] === '-' ? -minutes : minutes
}

function formatOffset(minutes: number): string {
  if (minutes === 0) {
    return 'UTC'
  }
  const sign = minutes > 0 ? '+' : '-'
  const abs = Math.abs(minutes)
  const rest = abs % 60
  return `UTC${sign}${Math.floor(abs / 60)}${rest ? ':' + String(rest).padStart(2, '0') : ''}`
}

export function timezoneOption(id: string, now = new Date()): TimezoneOption {
  try {
    const offsetMinutes = parseOffset(zonePart(id, 'shortOffset', now))
    // 没有中文名的时区，Intl 给的是“GMT+03:00”这样的写法，这时用 IANA 名中的城市部分
    const longName = zonePart(id, 'long', now)
    const name = !longName || longName.startsWith('GMT') ? id.split('/').pop()!.replaceAll('_', ' ') : longName
    return { id, label: `${name}（${formatOffset(offsetMinutes)}）`, offsetMinutes }
  } catch {
    // 浏览器不认识这个时区（后端存的值比浏览器的列表新）：原样显示
    return { id, label: id, offsetMinutes: 0 }
  }
}

/** 浏览器支持的全部时区，按 UTC 偏移、再按显示名排序；current 不在列表里时也加进去 */
export function timezoneOptions(current: string): TimezoneOption[] {
  const now = new Date()
  const ids = new Set(Intl.supportedValuesOf('timeZone'))
  ids.add(current)
  return [...ids]
    .map((id) => timezoneOption(id, now))
    .sort((a, b) => a.offsetMinutes - b.offsetMinutes || a.label.localeCompare(b.label, 'zh-CN'))
}
