import api from './client'
import { decodePageResponse } from './pagination'
import type { Id, PageResult } from '@/types'

export interface GovernanceMe {
  admin: boolean
  moderatedSectionIds: Id[]
  postingRestricted: boolean
  restrictedUntil: string | null
}

export interface GovernanceUser {
  id: Id
  username: string
  admin: boolean
  moderatedSectionIds: Id[]
  postingRestricted: boolean
  restrictedUntil: string | null
}

export interface GovernanceSection {
  id: Id
  name: string
  slug: string
  description: string | null
  rules: string | null
  visible: boolean
  archived: boolean
}

export interface GovernanceAuditEntry {
  id: Id
  actorId: Id
  action: string
  targetType: string
  targetId: Id
  reason: string
  occurredAt: string
  requestId: string
}

export interface GovernanceUserAdminPayload {
  enabled: boolean
  reason: string
}

export interface GovernanceModeratorSectionsPayload {
  sectionIds: Id[]
  reason: string
}

export interface GovernancePostingRestrictionPayload {
  restricted: boolean
  until: string | null
  reason: string
}

export interface GovernanceSectionPayload {
  name: string
  slug: string
  description: string
  rules: string
  visible: boolean
  archived: boolean
  reason: string
}

export class GovernanceResponseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'GovernanceResponseError'
  }
}

export class GovernanceValidationError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'GovernanceValidationError'
  }
}

type RecordValue = Record<string, unknown>

function responseError(endpoint: string, detail: string): GovernanceResponseError {
  return new GovernanceResponseError(`Invalid governance response for ${endpoint}: ${detail}`)
}

function asRecord(value: unknown, endpoint: string): RecordValue {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw responseError(endpoint, 'expected an object')
  }
  return value as RecordValue
}

function requiredField(record: RecordValue, field: string, endpoint: string): unknown {
  if (!Object.prototype.hasOwnProperty.call(record, field)) {
    throw responseError(endpoint, `missing ${field}`)
  }
  return record[field]
}

function decodeId(value: unknown, field: string, endpoint: string): Id {
  if (typeof value === 'number') {
    if (Number.isSafeInteger(value)) return value
  } else if (typeof value === 'string' && value.trim()) {
    return value
  }
  throw responseError(endpoint, `${field} must be a non-empty id`)
}

function decodeString(value: unknown, field: string, endpoint: string): string {
  if (typeof value === 'string') return value
  throw responseError(endpoint, `${field} must be a string`)
}

function decodeNullableString(value: unknown, field: string, endpoint: string): string | null {
  if (value === null) return null
  return decodeString(value, field, endpoint)
}

function decodeBoolean(value: unknown, field: string, endpoint: string): boolean {
  if (typeof value === 'boolean') return value
  throw responseError(endpoint, `${field} must be a boolean`)
}

function decodeIdArray(value: unknown, field: string, endpoint: string): Id[] {
  if (!Array.isArray(value)) throw responseError(endpoint, `${field} must be an array`)
  return value.map((item, index) => decodeId(item, `${field}[${index}]`, endpoint))
}

function decodeGovernanceMe(value: unknown): GovernanceMe {
  const endpoint = '/governance/me'
  const record = asRecord(value, endpoint)
  return {
    admin: decodeBoolean(requiredField(record, 'admin', endpoint), 'admin', endpoint),
    moderatedSectionIds: decodeIdArray(requiredField(record, 'moderatedSectionIds', endpoint), 'moderatedSectionIds', endpoint),
    postingRestricted: decodeBoolean(requiredField(record, 'postingRestricted', endpoint), 'postingRestricted', endpoint),
    restrictedUntil: decodeNullableString(requiredField(record, 'restrictedUntil', endpoint), 'restrictedUntil', endpoint),
  }
}

function decodeGovernanceUser(value: unknown): GovernanceUser {
  const endpoint = '/governance/users'
  const record = asRecord(value, endpoint)
  return {
    id: decodeId(requiredField(record, 'id', endpoint), 'id', endpoint),
    username: decodeString(requiredField(record, 'username', endpoint), 'username', endpoint),
    admin: decodeBoolean(requiredField(record, 'admin', endpoint), 'admin', endpoint),
    moderatedSectionIds: decodeIdArray(requiredField(record, 'moderatedSectionIds', endpoint), 'moderatedSectionIds', endpoint),
    postingRestricted: decodeBoolean(requiredField(record, 'postingRestricted', endpoint), 'postingRestricted', endpoint),
    restrictedUntil: decodeNullableString(requiredField(record, 'restrictedUntil', endpoint), 'restrictedUntil', endpoint),
  }
}

function decodeGovernanceSection(value: unknown): GovernanceSection {
  const endpoint = '/governance/sections'
  const record = asRecord(value, endpoint)
  return {
    id: decodeId(requiredField(record, 'id', endpoint), 'id', endpoint),
    name: decodeString(requiredField(record, 'name', endpoint), 'name', endpoint),
    slug: decodeString(requiredField(record, 'slug', endpoint), 'slug', endpoint),
    description: decodeNullableString(requiredField(record, 'description', endpoint), 'description', endpoint),
    rules: decodeNullableString(requiredField(record, 'rules', endpoint), 'rules', endpoint),
    visible: decodeBoolean(requiredField(record, 'visible', endpoint), 'visible', endpoint),
    archived: decodeBoolean(requiredField(record, 'archived', endpoint), 'archived', endpoint),
  }
}

function decodeGovernanceAudit(value: unknown): GovernanceAuditEntry {
  const endpoint = '/governance/audit'
  const record = asRecord(value, endpoint)
  return {
    id: decodeId(requiredField(record, 'id', endpoint), 'id', endpoint),
    actorId: decodeId(requiredField(record, 'actorId', endpoint), 'actorId', endpoint),
    action: decodeString(requiredField(record, 'action', endpoint), 'action', endpoint),
    targetType: decodeString(requiredField(record, 'targetType', endpoint), 'targetType', endpoint),
    targetId: decodeId(requiredField(record, 'targetId', endpoint), 'targetId', endpoint),
    reason: decodeString(requiredField(record, 'reason', endpoint), 'reason', endpoint),
    occurredAt: decodeString(requiredField(record, 'occurredAt', endpoint), 'occurredAt', endpoint),
    requestId: decodeString(requiredField(record, 'requestId', endpoint), 'requestId', endpoint),
  }
}

function decodeGovernancePage<T>(
  data: unknown,
  page: number,
  size: number,
  endpoint: string,
  decodeItem: (value: unknown) => T,
): PageResult<T> {
  const decoded = decodePageResponse<unknown>(data, { page, size, endpoint })
  return { ...decoded, items: decoded.items.map(decodeItem) }
}

export function requireGovernanceReason(reason: string): string {
  const normalized = String(reason ?? '').trim()
  if (normalized.length < 1 || normalized.length > 500) {
    throw new GovernanceValidationError('操作理由必须填写，长度为 1–500 个字符。')
  }
  return normalized
}

function encodeId(id: Id): string {
  return encodeURIComponent(String(id))
}

export async function getGovernanceMe(): Promise<GovernanceMe> {
  const { data } = await api.get('/governance/me')
  return decodeGovernanceMe(data)
}

export async function listGovernanceUsers({
  q = '',
  page = 1,
  size = 20,
}: { q?: string; page?: number; size?: number } = {}): Promise<PageResult<GovernanceUser>> {
  const params: Record<string, string | number> = { page, size }
  if (q.trim()) params.q = q.trim()
  const { data } = await api.get('/governance/users', { params })
  return decodeGovernancePage(data, page, size, '/governance/users', decodeGovernanceUser)
}

export async function updateGovernanceUserAdmin(
  id: Id,
  payload: GovernanceUserAdminPayload,
): Promise<GovernanceUser> {
  const { data } = await api.put(`/governance/users/${encodeId(id)}/admin`, {
    enabled: payload.enabled,
    reason: requireGovernanceReason(payload.reason),
  })
  return decodeGovernanceUser(data)
}

export async function updateGovernanceModeratorSections(
  id: Id,
  payload: GovernanceModeratorSectionsPayload,
): Promise<GovernanceUser> {
  if (!Array.isArray(payload.sectionIds)) {
    throw new GovernanceValidationError('请选择有效的版主管理分区。')
  }
  const { data } = await api.put(`/governance/users/${encodeId(id)}/moderator-sections`, {
    sectionIds: [...payload.sectionIds],
    reason: requireGovernanceReason(payload.reason),
  })
  return decodeGovernanceUser(data)
}

export async function updateGovernancePostingRestriction(
  id: Id,
  payload: GovernancePostingRestrictionPayload,
): Promise<GovernanceUser> {
  const { data } = await api.put(`/governance/users/${encodeId(id)}/posting-restriction`, {
    restricted: payload.restricted,
    until: payload.until,
    reason: requireGovernanceReason(payload.reason),
  })
  return decodeGovernanceUser(data)
}

export async function listGovernanceSections(): Promise<GovernanceSection[]> {
  const { data } = await api.get('/governance/sections')
  if (!Array.isArray(data)) throw responseError('/governance/sections', 'expected an array')
  return data.map(decodeGovernanceSection)
}

function sectionPayload(payload: GovernanceSectionPayload): GovernanceSectionPayload {
  return {
    name: payload.name,
    slug: payload.slug,
    description: payload.description,
    rules: payload.rules,
    visible: payload.visible,
    archived: payload.archived,
    reason: requireGovernanceReason(payload.reason),
  }
}

export async function createGovernanceSection(payload: GovernanceSectionPayload): Promise<GovernanceSection> {
  const { data } = await api.post('/governance/sections', sectionPayload(payload))
  return decodeGovernanceSection(data)
}

export async function updateGovernanceSection(id: Id, payload: GovernanceSectionPayload): Promise<GovernanceSection> {
  const { data } = await api.put(`/governance/sections/${encodeId(id)}`, sectionPayload(payload))
  return decodeGovernanceSection(data)
}

export async function listGovernanceAudit({
  page = 1,
  size = 20,
}: { page?: number; size?: number } = {}): Promise<PageResult<GovernanceAuditEntry>> {
  const { data } = await api.get('/governance/audit', { params: { page, size } })
  return decodeGovernancePage(data, page, size, '/governance/audit', decodeGovernanceAudit)
}
