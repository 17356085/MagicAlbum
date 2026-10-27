type TimeInput = string | number | Date | null | undefined

function validDate(input: TimeInput): Date | null {
  if (input == null) return null
  const date = new Date(input)
  return Number.isFinite(date.getTime()) ? date : null
}

// 相对时间格式化（中文），示例："5分钟前"、"2小时前"、"3天前"
export function formatRelativeTime(input: TimeInput): string {
  const date = validDate(input)
  if (!date) return ''
  const t = date.getTime()
  const diffMs = Date.now() - t
  const future = diffMs < 0
  const suffix = future ? '后' : '前'
  const ms = Math.abs(diffMs)
  const sec = Math.floor(ms / 1000)
  if (sec < 60) return future ? '即将发生' : '刚刚'
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}分钟${suffix}`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr}小时${suffix}`
  const day = Math.floor(hr / 24)
  if (day < 7) return `${day}天${suffix}`
  if (day < 30) return `${Math.floor(day / 7)}周${suffix}`
  const month = Math.floor(day / 30)
  if (day < 365) return `${month}个月${suffix}`
  const year = Math.floor(day / 365)
  return `${year}年${suffix}`
}

// 绝对时间格式化（回退），用于必要场景
export function formatAbsoluteTime(input: TimeInput): string {
  const d = validDate(input)
  if (!d) return ''
  try {
    return d.toLocaleString()
  } catch (_) {
    return String(input || '')
  }
}

export function formatAbsoluteDate(input: TimeInput): string {
  const date = validDate(input)
  if (!date) return ''
  return date.toLocaleDateString()
}
