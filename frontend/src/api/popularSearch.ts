import api from './client'
import type { SearchType } from '@/components/header/searchHistory'

const endpoint = '/search/popular'

export async function listPopularSearches(type: SearchType): Promise<string[]> {
  const { data } = await api.get<unknown>(endpoint, { params: { type } })
  if (!Array.isArray(data)) return []
  return data.filter((value): value is string => typeof value === 'string' && Boolean(value.trim())).slice(0, 10)
}

export async function recordPopularSearch(type: SearchType, query: string): Promise<void> {
  await api.post(endpoint, { type, query })
}
