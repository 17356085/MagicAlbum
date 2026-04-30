export function getCurrentLine(value: string, position: number): { start: number; end: number; text: string } {
  const start = value.lastIndexOf('\n', Math.max(0, position - 1)) + 1
  const nextBreak = value.indexOf('\n', position)
  const end = nextBreak >= 0 ? nextBreak : value.length
  return {
    start,
    end,
    text: value.slice(start, end),
  }
}

export function normalizeOrderedListAround(value: string, anchorStart: number): string {
  const lines = value.split('\n')
  let charIndex = 0
  let lineIndex = 0

  for (let i = 0; i < lines.length; i += 1) {
    const lineLength = lines[i].length
    if (anchorStart <= charIndex + lineLength) {
      lineIndex = i
      break
    }
    charIndex += lineLength + 1
  }

  const orderedPattern = /^(\s*)(\d+)\.\s(.*)$/
  const currentMatch = lines[lineIndex]?.match(orderedPattern)
  if (!currentMatch) return value

  const indent = currentMatch[1] || ''
  let start = lineIndex
  let end = lineIndex

  while (start > 0) {
    const prevMatch = lines[start - 1].match(orderedPattern)
    if (!prevMatch || prevMatch[1] !== indent) break
    start -= 1
  }

  while (end + 1 < lines.length) {
    const nextMatch = lines[end + 1].match(orderedPattern)
    if (!nextMatch || nextMatch[1] !== indent) break
    end += 1
  }

  let order = 1
  for (let i = start; i <= end; i += 1) {
    const match = lines[i].match(orderedPattern)
    if (!match || match[1] !== indent) continue
    lines[i] = `${indent}${order}. ${match[3]}`
    order += 1
  }

  return lines.join('\n')
}

function getLineIndexAtPosition(value: string, position: number): number {
  let lineIndex = 0
  for (let i = 0; i < Math.min(position, value.length); i += 1) {
    if (value[i] === '\n') lineIndex += 1
  }
  return lineIndex
}

export function normalizeAllOrderedLists(value: string, cursor: number): { value: string; cursor: number } {
  const lines = value.split('\n')
  const orderedPattern = /^(\s*)(\d+)\.\s(.*)$/
  const cursorLineIndex = getLineIndexAtPosition(value, cursor)

  let nextCursor = cursor
  let charIndex = 0
  let i = 0

  while (i < lines.length) {
    const match = lines[i].match(orderedPattern)
    if (!match) {
      charIndex += lines[i].length + 1
      i += 1
      continue
    }

    const indent = match[1] || ''
    const start = i
    let end = i

    while (end + 1 < lines.length) {
      const nextMatch = lines[end + 1].match(orderedPattern)
      if (!nextMatch || nextMatch[1] !== indent) break
      end += 1
    }

    let localCharIndex = charIndex
    for (let index = start; index <= end; index += 1) {
      const currentMatch = lines[index].match(orderedPattern)
      if (!currentMatch) continue

      const oldLine = lines[index]
      const newLine = `${indent}${index - start + 1}. ${currentMatch[3]}`
      const delta = newLine.length - oldLine.length

      lines[index] = newLine
      if (index < cursorLineIndex) {
        nextCursor += delta
      } else if (index === cursorLineIndex) {
        const cursorColumn = cursor - localCharIndex
        if (cursorColumn > 0) nextCursor += delta
      }

      localCharIndex += oldLine.length + 1
    }

    for (let index = start; index <= end; index += 1) {
      charIndex += lines[index].length + 1
    }
    i = end + 1
  }

  return {
    value: lines.join('\n'),
    cursor: Math.max(0, nextCursor),
  }
}
