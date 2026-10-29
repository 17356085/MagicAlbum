export const UPLOAD_RESPONSE_UNCONFIRMED_MESSAGE = '图片上传结果未确认，重复上传可能产生多份图片'

export interface UploadImageResponse {
  url?: string
  path?: string
  [key: string]: unknown
}

export class UploadResponseUnconfirmedError extends Error {
  readonly code = 'UPLOAD_RESPONSE_UNCONFIRMED'

  constructor() {
    super(UPLOAD_RESPONSE_UNCONFIRMED_MESSAGE)
    this.name = 'UploadResponseUnconfirmedError'
    Object.setPrototypeOf(this, new.target.prototype)
  }
}

export function isUploadResponseUnconfirmedError(value: unknown): value is UploadResponseUnconfirmedError {
  if (value instanceof UploadResponseUnconfirmedError) return true
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as { code?: unknown; message?: unknown }
  return candidate.code === 'UPLOAD_RESPONSE_UNCONFIRMED'
    && candidate.message === UPLOAD_RESPONSE_UNCONFIRMED_MESSAGE
}

const MAX_UPLOAD_URL_LENGTH = 2048
const WHITESPACE_CHARACTER = /\s/
const SCHEME_PREFIX = /^[a-z][a-z\d+.-]*:/i
const HTTP_URL_PREFIX = /^https?:\/\//i
const URL_BASE = 'https://bluealbum.invalid/'

function isSafeUploadUrl(value: unknown): value is string {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_UPLOAD_URL_LENGTH) return false
  for (const character of value) {
    const code = character.charCodeAt(0)
    if (character === '\\' || WHITESPACE_CHARACTER.test(character)
      || code <= 0x1f || (code >= 0x7f && code <= 0x9f)) return false
  }

  // Only explicit HTTP(S) URLs may carry a scheme. All other schemes (for
  // example javascript:, data:, and file:) are unsafe for an image reference.
  if (SCHEME_PREFIX.test(value) && !HTTP_URL_PREFIX.test(value)) return false

  // A protocol-relative URL needs a non-empty authority. Rejecting a third
  // slash also avoids URL() treating `///host` as a different spelling.
  if (value.startsWith('//') && (value.length === 2 || value[2] === '/')) return false

  try {
    const parsed = new URL(value, URL_BASE)
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return false
    if (HTTP_URL_PREFIX.test(value) || value.startsWith('//')) return !!parsed.hostname
    return true
  } catch {
    return false
  }
}

/**
 * Confirm and copy a successful upload response without exposing malformed
 * server data to callers or media-state tracking.
 */
export function decodeUploadResponse(value: unknown): UploadImageResponse {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new UploadResponseUnconfirmedError()
  }

  const record = value as Record<string, unknown>
  // Keep the caller-visible `url || path` compatibility rule. In particular,
  // a truthy non-string `url` must not silently fall back to a valid `path`.
  const selectedUrl = record.url || record.path
  if (!isSafeUploadUrl(selectedUrl)) throw new UploadResponseUnconfirmedError()

  const normalized: UploadImageResponse = { ...record }
  if (typeof record.url === 'string') normalized.url = record.url
  else delete normalized.url
  if (typeof record.path === 'string') normalized.path = record.path
  else delete normalized.path

  // Canonicalize the selected legacy path so media tracking receives the same
  // shape as the backend's current { url } response.
  normalized.url = selectedUrl
  return normalized
}
