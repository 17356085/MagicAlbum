import client from './client'
import { getStoredAccessToken } from '@/utils/authStorage'
import type { Id } from '@/types'
import type { AxiosResponse } from 'axios'

export interface AiChatMessage {
  role: string
  content: string
}

export interface AiSummaryData {
  summary?: string
  status?: string
}

export interface AiSummaryResponse {
  data?: AiSummaryData
  [key: string]: unknown
}

type ChatStreamMessageHandler = (data: unknown) => void
type ChatStreamErrorHandler = (error: unknown) => void
type ChatStreamCompleteHandler = () => void

export function getSummary(threadId: Id): Promise<AxiosResponse<AiSummaryResponse>> {
  return client.get(`/ai/summary/${threadId}`)
}

export function triggerSummary(threadId: Id, force = false): Promise<AxiosResponse<unknown>> {
  return client.post(`/ai/summary/${threadId}?force=${force}`)
}

// 注意：流式对话通常不使用 axios/client，而是使用 fetch 或 EventSource
// 这里提供一个辅助函数用于 SSE 连接
export function createChatStream(
  messages: AiChatMessage[],
  onMessage?: ChatStreamMessageHandler,
  onError?: ChatStreamErrorHandler,
  onComplete?: ChatStreamCompleteHandler,
): void {
  const token = getStoredAccessToken()
  const apiBase = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'
  
  fetch(`${apiBase}/ai/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': token ? `Bearer ${token}` : '',
      'Accept': 'text/event-stream'
    },
    body: JSON.stringify({ messages })
  }).then(async response => {
    if (!response.ok) {
      throw new Error(response.statusText)
    }

    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('流式响应不可用')
    }
    const decoder = new TextDecoder()
    let buffer = '' // 添加缓冲区，用于处理跨 chunk 的断行
    
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        if (onComplete) onComplete()
        break
      }
      
      const chunk = decoder.decode(value, { stream: true })
      buffer += chunk
      const lines = buffer.split('\n')
      
      // 保留最后一行（可能是不完整的）
      buffer = lines.pop() || ''
      
      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed) continue
        if (trimmed.startsWith('data:')) {
          try {
            const jsonStr = trimmed.slice(5).trim()
            if (jsonStr === '[DONE]') {
               // 某些 SSE 实现会发送 [DONE] 标记
               continue
            }
            const data = JSON.parse(jsonStr)
            if (onMessage) onMessage(data)
          } catch (_) {
            // ignore parse error
          }
        }
      }
    }
  }).catch((err: unknown) => {
    if (onError) onError(err)
  })
}
