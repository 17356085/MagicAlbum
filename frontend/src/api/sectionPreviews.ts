import api from './client'

const ENDPOINT = '/threads/section-previews'
const MAX_SECTION_IDS = 50
const POSITIVE_DECIMAL_ID = /^(?:0*[1-9]\d*)$/
const ISO_DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:\d{2})$/

export type SectionPreviewId = string | number

export interface SectionPreviewItem {
  id: string
  title: string
  authorId: string
  authorUsername?: string
  authorNickname?: string
  createdAt: string
  coverUrl: string
}

export interface SectionPreview {
  sectionId: string
  total: number
  items: SectionPreviewItem[]
}

export class SectionPreviewRequestError extends Error {
  readonly code = 'SECTION_PREVIEW_REQUEST_INVALID'

  constructor(message: string) {
    super(message)
    this.name = 'SectionPreviewRequestError'
    Object.setPrototypeOf(this, new.target.prototype)
  }
}

export class SectionPreviewResponseError extends Error {
  readonly code = 'SECTION_PREVIEW_RESPONSE_INVALID'

  constructor(message: string) {
    super(message)
    this.name = 'SectionPreviewResponseError'
    Object.setPrototypeOf(this, new.target.prototype)
  }
}

type RecordValue = Record<string, unknown>

function hasOwn(record: RecordValue, field: string): boolean {
  return Object.prototype.hasOwnProperty.call(record, field)
}

function isRecord(value: unknown): value is RecordValue {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function canonicalPositiveId(value: unknown): string | undefined {
  if (typeof value === 'number') {
    if (Number.isSafeInteger(value) && value > 0) return String(value)
    return undefined
  }
  if (typeof value !== 'string' || !POSITIVE_DECIMAL_ID.test(value)) return undefined
  return value.replace(/^0+/, '')
}

function requestId(value: unknown, index: number): string {
  const canonical = canonicalPositiveId(value)
  if (!canonical) {
    throw new SectionPreviewRequestError(
      `Invalid section preview request: sectionIds[${index}] must be a positive decimal ID`,
    )
  }
  return canonical
}

function responseError(field: string, detail: string): SectionPreviewResponseError {
  return new SectionPreviewResponseError(`Invalid section preview response for ${ENDPOINT}: ${field} ${detail}`)
}

function requiredField(record: RecordValue, key: string, label = key): unknown {
  if (!hasOwn(record, key)) throw responseError(label, 'is missing')
  return record[key]
}

function responseId(value: unknown, field: string): string {
  const canonical = canonicalPositiveId(value)
  if (!canonical) throw responseError(field, 'must be a positive decimal ID')
  return canonical
}

function requiredString(record: RecordValue, key: string, label = key): string {
  const value = requiredField(record, key, label)
  if (typeof value !== 'string') throw responseError(label, 'must be a string')
  return value
}

function nullableString(record: RecordValue, key: string, label = key): string | undefined {
  const value = requiredField(record, key, label)
  if (value === null) return undefined
  if (typeof value !== 'string') throw responseError(label, 'must be a string or null')
  return value
}

function requiredTotal(record: RecordValue, key: string, label = key): number {
  const value = requiredField(record, key, label)
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw responseError(label, 'must be a non-negative safe integer')
  }
  return value
}

function requiredCreatedAt(record: RecordValue, key: string, label = key): string {
  const value = requiredString(record, key, label)
  const match = ISO_DATE_TIME.exec(value)
  if (!match || Number.isNaN(Date.parse(value))) {
    throw responseError(label, 'must be a valid ISO date-time string')
  }

  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const calendarDate = new Date(0)
  calendarDate.setUTCHours(0, 0, 0, 0)
  calendarDate.setUTCFullYear(year, month - 1, day)
  if (
    calendarDate.getUTCFullYear() !== year
    || calendarDate.getUTCMonth() !== month - 1
    || calendarDate.getUTCDate() !== day
  ) {
    throw responseError(label, 'must be a valid ISO date-time string')
  }
  return value
}

function decodeItem(value: unknown, sectionIndex: number, itemIndex: number): SectionPreviewItem {
  if (!isRecord(value)) {
    throw responseError(`items[${sectionIndex}][${itemIndex}]`, 'must be an object')
  }
  return {
    id: responseId(requiredField(value, 'id'), `items[${sectionIndex}][${itemIndex}].id`),
    title: requiredString(value, 'title', `items[${sectionIndex}][${itemIndex}].title`),
    authorId: responseId(requiredField(value, 'authorId'), `items[${sectionIndex}][${itemIndex}].authorId`),
    authorUsername: nullableString(value, 'authorUsername', `items[${sectionIndex}][${itemIndex}].authorUsername`),
    authorNickname: nullableString(value, 'authorNickname', `items[${sectionIndex}][${itemIndex}].authorNickname`),
    createdAt: requiredCreatedAt(value, 'createdAt', `items[${sectionIndex}][${itemIndex}].createdAt`),
    coverUrl: requiredString(value, 'coverUrl', `items[${sectionIndex}][${itemIndex}].coverUrl`),
  }
}

function decodeResponse(data: unknown, requestedIds: string[]): SectionPreview[] {
  if (!Array.isArray(data)) throw responseError('response', 'must be an array')

  const bySection = new Map<string, SectionPreview>()
  data.forEach((value, sectionIndex) => {
    if (!isRecord(value)) throw responseError(`[${sectionIndex}]`, 'must be an object')

    const sectionId = responseId(requiredField(value, 'sectionId'), `[${sectionIndex}].sectionId`)
    if (!requestedIds.includes(sectionId)) throw responseError(`[${sectionIndex}].sectionId`, 'is not requested')
    if (bySection.has(sectionId)) throw responseError(`[${sectionIndex}].sectionId`, 'is duplicated')

    const total = requiredTotal(value, 'total', `[${sectionIndex}].total`)
    const rawItems = requiredField(value, 'items', `[${sectionIndex}].items`)
    if (!Array.isArray(rawItems)) throw responseError(`[${sectionIndex}].items`, 'must be an array')
    const expectedItems = Math.min(total, 4)
    if (rawItems.length !== expectedItems) {
      throw responseError(`[${sectionIndex}].items`, `must contain exactly ${expectedItems} items`)
    }

    const itemIds = new Set<string>()
    const items = rawItems.map((item, itemIndex) => {
      const decoded = decodeItem(item, sectionIndex, itemIndex)
      if (itemIds.has(decoded.id)) {
        throw responseError(`[${sectionIndex}].items`, `contains duplicate thread id ${decoded.id}`)
      }
      itemIds.add(decoded.id)
      return decoded
    })
    bySection.set(sectionId, { sectionId, total, items })
  })

  if (bySection.size !== requestedIds.length) {
    const missing = requestedIds.find((sectionId) => !bySection.has(sectionId))
    throw responseError('sectionId', `is missing requested section ${missing}`)
  }

  return requestedIds.map((sectionId) => bySection.get(sectionId) as SectionPreview)
}

/**
 * Fetch the bounded latest-thread previews for all visible sections in one
 * request. IDs are canonicalized before transport so unsafe numbers can never
 * be stringified into a lossy request parameter.
 */
export async function listSectionPreviews(sectionIds: readonly SectionPreviewId[]): Promise<SectionPreview[]> {
  if (!Array.isArray(sectionIds)) {
    throw new SectionPreviewRequestError('Invalid section preview request: sectionIds must be an array')
  }
  if (sectionIds.length > MAX_SECTION_IDS) {
    throw new SectionPreviewRequestError(`Invalid section preview request: at most ${MAX_SECTION_IDS} section IDs are allowed`)
  }
  if (sectionIds.length === 0) return []

  const normalizedIds = [...new Set(sectionIds.map(requestId))]
  const { data } = await api.get(ENDPOINT, { params: { sectionIds: normalizedIds.join(',') } })
  return decodeResponse(data, normalizedIds)
}
