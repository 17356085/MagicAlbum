import api from './client'
import { decodePageResponse } from './pagination'
import type { Id, PageResult, TagStats } from '@/types'

interface ListPopularTagsQuery {
  sectionId?: Id | null
  size?: number
}

interface ListTagsQuery {
  q?: string
  sectionId?: Id | null
  page?: number
  size?: number
}

export interface TagsResponse extends PageResult<TagStats> {
  sectionId?: Id | null
}

interface PopularTagsResponse {
  items?: TagStats[]
  sectionId?: Id | null
  size?: number
  total?: number
}

function normalizeTags(items: TagStats[] | undefined): TagStats[] {
  return Array.isArray(items)
    ? items.map((tag) => ({
        ...tag,
        threadCount: Number(tag.threadCount || 0),
      }))
    : []
}

export async function listPopularTags({
  sectionId,
  size = 12,
}: ListPopularTagsQuery = {}): Promise<TagStats[]> {
  const params: Record<string, string | number> = { size }
  if (sectionId) params.sectionId = sectionId
  const { data } = await api.get<PopularTagsResponse>('/tags/popular', { params })
  return normalizeTags(data?.items)
}

export async function listTags({
  q,
  sectionId,
  page = 1,
  size = 20,
}: ListTagsQuery = {}): Promise<TagsResponse> {
  const params: Record<string, string | number> = { page, size }
  if (q && q.trim()) params.q = q.trim()
  if (sectionId) params.sectionId = sectionId
  const { data } = await api.get<unknown>('/tags', { params })
  const decoded = decodePageResponse<TagStats, { sectionId?: Id | null }>(data, { page, size, endpoint: '/tags' })
  return {
    ...decoded,
    items: normalizeTags(decoded.items),
  }
}
