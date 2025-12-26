// 相对时间格式化（中文），示例："5分钟前"、"2小时前"、"3天前"
export function formatRelativeTime(input) {
  const t = new Date(input).getTime()
  if (!isFinite(t)) return ''
  const diffMs = Date.now() - t
  const future = diffMs < 0
  const ms = Math.abs(diffMs)
  const sec = Math.floor(ms / 1000)
  if (sec < 60) return future ? '即将发生' : '刚刚'
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}分钟前`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr}小时前`
  const day = Math.floor(hr / 24)
  if (day < 7) return `${day}天前`
  const week = Math.floor(day / 7)
  if (week < 4) return `${week}周前`
  const month = Math.floor(day / 30)
  if (month < 12) return `${month}个月前`
  const year = Math.floor(day / 365)
  return `${year}年前`
}

// 绝对时间格式化（回退），用于必要场景
export function formatAbsoluteTime(input) {
  const d = new Date(input)
  try {
    return d.toLocaleString()
  } catch (_) {
    return String(input || '')
  }
}