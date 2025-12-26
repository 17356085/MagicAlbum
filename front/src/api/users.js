import api from './client'

const useMock = import.meta.env.VITE_USE_API_MOCK === 'true'

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export async function checkUsernameAvailable(username) {
  if (useMock) {
    await delay(300)
    const taken = username?.toLowerCase() === 'taken'
    return !taken
  }
  const { data } = await api.get('/users/availability', { params: { username } })
  return !!data?.available
}

export async function registerUser(payload) {
  if (useMock) {
    await delay(500)
    if (payload?.username?.toLowerCase() === 'taken') {
      const err = new Error('用户名不可重复')
      err.response = { status: 400, data: { message: '用户名不可重复' } }
      throw err
    }
    return {
      id: 1,
      username: payload.username,
      email: payload.email,
      phone: payload.phone,
    }
  }
  const { data } = await api.post('/users/register', payload)
  return data
}

export async function listUsers({ q, page = 1, size = 20 } = {}) {
  if (useMock) {
    await delay(300)
    const items = [
      { id: 1, username: 'alice', email: 'alice@example.com', phone: '13800000000' },
      { id: 2, username: 'bob', email: 'bob@example.com', phone: '13900000000' },
      { id: 3, username: 'charlie', email: 'charlie@example.com', phone: '13700000000' }
    ].filter(u => !q || String(u.username).toLowerCase().includes(String(q).toLowerCase()))
    return { items, page, size, total: items.length }
  }
  const params = {}
  if (q && String(q).trim()) params.q = q
  params.page = page
  params.size = size
  const { data } = await api.get('/users', { params })
  return data
}

// 获取当前登录用户信息，兼容不同后端路径与返回结构
export async function getCurrentUser() {
  if (useMock) {
    await delay(200)
    return {
      id: 1,
      username: 'mock_user',
      email: 'mock@example.com',
    }
  }
  const candidates = ['/users/me', '/auth/me', '/users/profile', '/users/current']
  for (const path of candidates) {
    try {
      const { data } = await api.get(path)
      const payload = data?.data ?? data?.result ?? data ?? null
      const user = payload?.user ?? payload?.profile ?? payload?.userInfo ?? payload ?? null
      if (user && typeof user === 'object') return user
    } catch (e) {
      // 继续尝试下一个候选路径
    }
  }
  return null
}

// 获取指定用户的公开资料（昵称、头像、主页、所在地、个人介绍、外链）
export async function getUserProfile(id) {
  const { data } = await api.get(`/users/${id}/profile`)
  return data
}