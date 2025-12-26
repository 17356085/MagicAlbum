import api from './client'

const useMock = import.meta.env.VITE_USE_API_MOCK === 'true'

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function normalizeAuthResponse(resp) {
  const root = resp || {}
  const payload = root.data ?? root.result ?? root
  const accessToken =
    payload.accessToken ?? payload.access_token ?? payload.token ?? payload.jwt ?? payload.id_token
  const user = payload.user ?? payload.profile ?? payload.userInfo ?? payload.account ?? null
  return { accessToken, user }
}

export async function loginWithPhonePassword({ phone, password }) {
  if (useMock) {
    await delay(400)
    return {
      accessToken: 'mock-token-' + Math.random().toString(36).slice(2),
      user: {
        id: Math.floor(Math.random() * 10000),
        username: 'user_' + phone.slice(-4),
        phone,
        avatarUrl: '',
      },
    }
  }
  try {
    const { data } = await api.post('/auth/login', { phone, password })
    // 规范化后端返回结构为 { accessToken, user }
    return normalizeAuthResponse(data)
  } catch (e) {
    // 非 Mock 模式：直接抛出错误，避免写入伪令牌
    throw e
  }
}

export async function loginWithEmailPassword({ email, password }) {
  if (useMock) {
    await delay(400)
    return {
      accessToken: 'mock-token-' + Math.random().toString(36).slice(2),
      user: {
        id: Math.floor(Math.random() * 10000),
        username: email.split('@')[0],
        email,
        avatarUrl: '',
      },
    }
  }
  try {
    const { data } = await api.post('/auth/login', { email, password })
    return normalizeAuthResponse(data)
  } catch (e) {
    // 非 Mock 模式：直接抛出错误，避免写入伪令牌
    throw e
  }
}