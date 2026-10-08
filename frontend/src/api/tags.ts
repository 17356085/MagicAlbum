import api from './client'
import type { Id, TagStats } from '@/types'

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

interface TagsResponse {
  items?: TagStats[]
  page?: number
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
  const { data } = await api.get<TagsResponse>('/tags/popular', { params })
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
  const { data } = await api.get<TagsResponse>('/tags', { params })
  return {
    items: normalizeTags(data?.items),
    page: Number(data?.page ?? page),
    size: Number(data?.size ?? size),
    total: Number(data?.total ?? data?.items?.length ?? 0),
  }
}
