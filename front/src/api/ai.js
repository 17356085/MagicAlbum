import client from './client'

export function getSummary(threadId) {
  return client.get(`/ai/summary/${threadId}`)
}

export function triggerSummary(threadId, force = false) {
  return client.post(`/ai/summary/${threadId}?force=${force}`)
}

// 注意：流式对话通常不使用 axios/client，而是使用 fetch 或 EventSource
// 这里提供一个辅助函数用于 SSE 连接
export function createChatStream(messages, onMessage, onError, onComplete) {
  const token = localStorage.getItem('token')
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
    
    const reader = response.body.getReader()
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
          } catch (e) {
            // ignore parse error
          }
        }
      }
    }
  }).catch(err => {
    if (onError) onError(err)
  })
}
